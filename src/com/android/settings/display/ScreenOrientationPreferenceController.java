/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.settings.core.PreferenceControllerMixin;
import com.android.settingslib.core.AbstractPreferenceController;

/**
 * Controller for a fixed "Screen orientation" ListPreference, for fixed displays without an
 * orientation sensor (e.g. a monitor on a single-board computer). It writes the rotation directly to
 * {@link Settings.System#USER_ROTATION} and locks {@link Settings.System#ACCELEROMETER_ROTATION}
 * off so the chosen orientation takes effect immediately.
 */
public class ScreenOrientationPreferenceController extends AbstractPreferenceController implements
        PreferenceControllerMixin, Preference.OnPreferenceChangeListener {

    private static final String KEY_SCREEN_ORIENTATION = "screen_orientation_fixed";

    public ScreenOrientationPreferenceController(Context context) {
        super(context);
    }

    @Override
    public String getPreferenceKey() {
        return KEY_SCREEN_ORIENTATION;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void updateState(Preference preference) {
        final ListPreference pref = (ListPreference) preference;
        final int rotation = Settings.System.getInt(
                mContext.getContentResolver(), Settings.System.USER_ROTATION, 0);
        pref.setValue(String.valueOf(rotation));
        updateSummary(pref, rotation);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final int rotation = Integer.parseInt((String) newValue);
        // Lock auto-rotate off (no sensor anyway) so the fixed rotation is honoured.
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0);
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.USER_ROTATION, rotation);
        updateSummary((ListPreference) preference, rotation);
        return true;
    }

    private void updateSummary(ListPreference pref, int rotation) {
        final int index = pref.findIndexOfValue(String.valueOf(rotation));
        if (index >= 0 && pref.getEntries() != null) {
            pref.setSummary(pref.getEntries()[index]);
        }
    }
}
