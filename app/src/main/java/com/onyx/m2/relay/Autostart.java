package com.onyx.m2.relay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class Autostart extends BroadcastReceiver {
    private static final String TAG = "Autostart";

    @Override
    public void onReceive(Context context, Intent arg1) {
        Log.i(TAG, "Received boot broadcast, starting service");
        Intent intent = new Intent(context, RelayService.class);
        context.startForegroundService(intent);
    }
}
