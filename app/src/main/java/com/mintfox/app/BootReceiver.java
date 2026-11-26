package com.mintfox.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        if (!a.equals(Intent.ACTION_BOOT_COMPLETED)
                && !a.equals("android.intent.action.QUICKBOOT_POWERON")) return;
        Intent svc = new Intent(context, ScanService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
        else context.startService(svc);
    }
}
