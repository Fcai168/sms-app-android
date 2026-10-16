package com.fcai.smsapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int PERM_REQ = 100;
    private SharedPreferences sp;
    private TextView tvStatus, tvSent, tvFail, tvUptime, tvLog, tvDeviceId, tvBadge;
    private View dotStatus;
    private Button btnToggle, btnClear;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            updateUI();
            mainHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences("sms", MODE_PRIVATE);

        tvStatus = findViewById(R.id.tvStatus);
        tvSent = findViewById(R.id.tvSent);
        tvFail = findViewById(R.id.tvFail);
        tvUptime = findViewById(R.id.tvUptime);
        tvLog = findViewById(R.id.tvLog);
        tvDeviceId = findViewById(R.id.tvDeviceId);
        tvBadge = findViewById(R.id.tvBadge);
        dotStatus = findViewById(R.id.dotStatus);
        btnToggle = findViewById(R.id.btnToggle);
        btnClear = findViewById(R.id.btnClear);

        String devId = sp.getString("deviceId", null);
        if (devId == null) {
            devId = "d" + System.currentTimeMillis() % 100000000 + (int)(Math.random()*900+100);
            sp.edit().putString("deviceId", devId).apply();
        }
        tvDeviceId.setText("本机编号 " + devId);

        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, PERM_REQ);
        }

        btnToggle.setOnClickListener(v -> {
            if (SendService.isRunning()) {
                stopService(new Intent(this, SendService.class));
                updateUI();
            } else {
                askBatteryExemption();
                sp.edit().putBoolean("autostart", true).apply();
                startService(new Intent(this, SendService.class));
                updateUI();
            }
        });
        btnClear.setOnClickListener(v -> {
            sp.edit().putInt("sent", 0).putInt("fail", 0).putString("log", "").apply();
            Toast.makeText(this, "统计和日志已清零", Toast.LENGTH_SHORT).show();
            updateUI();
        });
    }

    /** 申请电池优化豁免, 锁屏后服务不容易被杀 */
    private void askBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return; // 已豁免
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "短信权限已授权", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "必须授权短信权限才能群发", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.post(uiTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(uiTicker);
    }

    private void updateUI() {
        boolean running = SendService.isRunning();
        tvStatus.setText(running ? "运行中" : "已停止");
        dotStatus.setBackgroundResource(running ? R.drawable.dot_green : R.drawable.dot_off);
        if (running) {
            tvBadge.setText("服务在线");
            tvBadge.setTextColor(0xFF7DFFB8);
            tvBadge.setBackgroundResource(R.drawable.badge_green);
        } else {
            tvBadge.setText("服务离线");
            tvBadge.setTextColor(0xFFFF9E9E);
            tvBadge.setBackgroundResource(R.drawable.badge_red);
        }
        btnToggle.setBackgroundResource(running ? R.drawable.btn_glass : R.drawable.btn_green);
        btnToggle.setText(running ? "停 止 群 发" : "开 始 群 发");

        tvSent.setText(String.valueOf(sp.getInt("sent", 0)));
        tvFail.setText(String.valueOf(sp.getInt("fail", 0)));
        tvLog.setText(sp.getString("log", "暂无日志"));

        long up = SendService.lastStartAt();
        if (running && up > 0) {
            long s = (System.currentTimeMillis() - up) / 1000;
            tvUptime.setText(s >= 3600 ? (s/3600) + "h" + ((s%3600)/60) + "m" : s/60 + "m" + s%60 + "s");
        } else {
            tvUptime.setText("--");
        }
    }
}
