/*
 * Copyright (C) 2026 RadicalES
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.settings.display;

import android.content.Context;
import android.os.BatteryManager;
import android.provider.Settings;

import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.android.settings.core.PreferenceControllerMixin;
import com.android.settingslib.core.AbstractPreferenceController;

/**
 * Controller for a "Keep screen on" SwitchPreference in Display settings.
 *
 * When enabled, the screen never dims or turns off while the device is powered. The
 * reliable lever is {@link Settings.System#SCREEN_OFF_TIMEOUT} (set
 * effectively-infinite); {@link Settings.Global#STAY_ON_WHILE_PLUGGED_IN} alone does
 * NOT stop the idle dim, so both are set. Disabling restores a normal 1-minute
 * timeout. Brightness is a separate, user-adjustable setting and is not touched.
 */
public class KeepScreenOnPreferenceController extends AbstractPreferenceController implements
        PreferenceControllerMixin, Preference.OnPreferenceChangeListener {

    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";

    // Effectively "never" (~24.8 days); the timeout is never reached in practice.
    private static final int NEVER_TIMEOUT = Integer.MAX_VALUE;
    // Timeout restored when the toggle is turned off.
    private static final int NORMAL_TIMEOUT_MS = 60_000;
    // "On" if the timeout is set beyond any real idle period (> 1 hour).
    private static final int ON_THRESHOLD_MS = 60 * 60 * 1000;

    // Stay on whenever powered by any source.
    private static final int STAY_ON_ALL =
            BatteryManager.BATTERY_PLUGGED_AC
                    | BatteryManager.BATTERY_PLUGGED_USB
                    | BatteryManager.BATTERY_PLUGGED_WIRELESS;

    public KeepScreenOnPreferenceController(Context context) {
        super(context);
    }

    @Override
    public String getPreferenceKey() {
        return KEY_KEEP_SCREEN_ON;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void updateState(Preference preference) {
        final int timeout = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, NORMAL_TIMEOUT_MS);
        ((TwoStatePreference) preference).setChecked(timeout > ON_THRESHOLD_MS);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final boolean on = (Boolean) newValue;
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, on ? NEVER_TIMEOUT : NORMAL_TIMEOUT_MS);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, on ? STAY_ON_ALL : 0);
        return true;
    }
}
