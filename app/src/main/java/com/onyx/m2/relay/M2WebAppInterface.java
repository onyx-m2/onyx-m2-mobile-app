package com.onyx.m2.relay;

import android.util.Log;
import android.webkit.JavascriptInterface;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONException;

public class M2WebAppInterface {
    private static final String TAG = "M2WebAppInterface";

    /** Send a command to M2 using direct interface. */
    @JavascriptInterface
    public void sendCommand(String array) {
        Log.d(TAG, "sendCommand: " + array);
        try {
            JSONArray json = new JSONArray(array);
            byte[] data = new byte[json.length()];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) json.getInt(i);
            }
            EventBus.getDefault().post(new M2Command(data));
        }
        catch (JSONException e) {
            Log.e(TAG, "Invalid command: " + array);
        }
    }
}