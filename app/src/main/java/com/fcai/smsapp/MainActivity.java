package com.fcai.smsapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int PERM_REQ = 100;
    private SharedPreferences sp;
    private TextView tvStatus, tvStat, tvLog, tvDeviceId;
    private Button btnToggle;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences("sms", MODE_PRIVATE);

        tvStatus = findViewById(R.id.tvStatus);
        tvStat = findViewById(R.id.tvStat);
        tvLog = findViewById(R.id.tvLog);
        tvDeviceId = findViewById(R.id.tvDeviceId);
        btnToggle = findViewById(R.id.btnToggle);

        String devId = sp.getString("deviceId", null);
        if (devId == null) {
            devId = "d" + System.currentTimeMillis() % 100000000 + (int)(Math.random()*900+100);
            sp.edit().putString("deviceId", devId).apply();
        }
        tvDeviceId.setText("本机编号：" + devId);

        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, PERM_REQ);
        }

        btnToggle.setOnClickListener(v -> {
            if (SendService.isRunning()) {
                stopService(new Intent(this, SendService.class));
            } else {
                startService(new Intent(this, SendService.class));
            }
        });
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
        updateUI();
    }

    private void updateUI() {
        boolean running = SendService.isRunning();
        tvStatus.setText("状态：" + (running ? "运行中" : "已停止"));
        btnToggle.setText(running ? "停止群发" : "开始群发");
        int sent = sp.getInt("sent", 0), fail = sp.getInt("fail", 0);
        tvStat.setText("已发 " + sent + " 条 · 失败 " + fail + " 条");
        tvLog.setText(sp.getString("log", ""));
    }
}
