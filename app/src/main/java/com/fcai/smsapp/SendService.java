package com.fcai.smsapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
 * 后台群发服务：循环 领任务 -> 发短信 -> 回报
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
        while (running) {
            try {
                register(devId);
                JSONObject claim = httpPost("/claim", new JSONObject().put("deviceId", devId));
                lastOkAt = System.currentTimeMillis();
                if (!claim.optBoolean("success")) { sleep(5000); continue; }
                JSONObject task = claim.optJSONObject("task");
                if (task == null) { sleep(5000); continue; }  // 无任务

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

                sleep(3000);  // 防风控间隔
            } catch (Exception e) {
                lastErrAt = System.currentTimeMillis();
                appendLog("错误: " + e.getMessage());
                sleep(5000);
            }
        }
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
