package com.fcai.smsapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：用户上次点过"开始群发"，重启手机后自动把服务拉起来
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        boolean auto = c.getSharedPreferences("sms", Context.MODE_PRIVATE).getBoolean("autostart", false);
        if (auto) c.startService(new Intent(c, SendService.class));
    }
}
