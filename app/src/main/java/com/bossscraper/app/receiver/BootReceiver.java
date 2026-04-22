package com.bossscraper.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启动接收器（可选功能，需用户手动授权）
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // 开机后可在此启动后台服务（Android 10+ 有限制）
        }
    }
}
