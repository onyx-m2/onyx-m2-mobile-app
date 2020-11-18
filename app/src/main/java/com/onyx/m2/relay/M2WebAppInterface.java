package com.onyx.m2.relay;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.preference.PreferenceManager;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONException;

public class M2WebAppInterface {
    private static final String TAG = "M2WebAppInterface";
    private Context context;

    public M2WebAppInterface(Context context) {
        this.context = context;
    }

    /** Get a preference value. This allows the web app to have the same access to the
     *  configuration as the native side does. */
    @JavascriptInterface
    public String getPreference(String name) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        return settings.getString(name, "");
    }

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