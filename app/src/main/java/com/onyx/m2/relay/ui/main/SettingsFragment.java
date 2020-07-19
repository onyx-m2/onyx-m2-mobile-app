package com.onyx.m2.relay.ui.main;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;


import com.onyx.m2.relay.R;

public class SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings, rootKey);

        EditTextPreference.OnBindEditTextListener passwordEditText = new EditTextPreference.OnBindEditTextListener() {
            @Override
            public void onBindEditText(@NonNull EditText editText) {
                editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
        };
        EditTextPreference serverPin = findPreference("server_pin");
        serverPin.setOnBindEditTextListener(passwordEditText);
        EditTextPreference homeWifiPassword = findPreference("home_wifi_password");
        homeWifiPassword.setOnBindEditTextListener(passwordEditText);
        EditTextPreference mobileWifiPassword = findPreference("mobile_wifi_password");
        mobileWifiPassword.setOnBindEditTextListener(passwordEditText);
    }
}

