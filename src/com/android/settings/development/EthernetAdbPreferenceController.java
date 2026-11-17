/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.development;

import android.content.Context;
import android.os.SystemProperties;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.android.settings.core.PreferenceControllerMixin;
import com.android.settingslib.development.DeveloperOptionsPreferenceController;

/**
 * Toggles classic adb over TCP (network adb) at runtime, for boards reached over
 * Ethernet. Wireless debugging covers Wi-Fi only.
 *
 * <p>Only init may set adbd's port and restart adbd, so this sets two properties
 * that the device applies from init: {@code persist.sys.adb.tcp.enable}
 * ("true"/"false"), which init maps to {@code service.adb.tcp.port} (5555 or 0),
 * then {@code sys.adb.tcp.restart=1}, on which init restarts adbd if it is
 * running and clears the flag. Both are system properties (persist.sys. and
 * sys.), which Settings may set; the device ships the init actions (an rc file
 * outside /vendor, as vendor init actions may not trigger on system
 * properties). Key authorisation still applies.
 */
public class EthernetAdbPreferenceController extends
        DeveloperOptionsPreferenceController implements Preference.OnPreferenceChangeListener,
        PreferenceControllerMixin {

    private static final String ETHERNET_ADB_KEY = "ethernet_adb_debugging";

    @VisibleForTesting
    static final String ADB_TCP_ENABLE_PROPERTY = "persist.sys.adb.tcp.enable";
    @VisibleForTesting
    static final String ADB_TCP_RESTART_PROPERTY = "sys.adb.tcp.restart";

    public EthernetAdbPreferenceController(Context context) {
        super(context);
    }

    @Override
    public String getPreferenceKey() {
        return ETHERNET_ADB_KEY;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        setEnabled((Boolean) newValue);
        return true;
    }

    @Override
    public void updateState(Preference preference) {
        ((TwoStatePreference) mPreference).setChecked(
                SystemProperties.getBoolean(ADB_TCP_ENABLE_PROPERTY, false));
    }

    @Override
    protected void onDeveloperOptionsSwitchDisabled() {
        super.onDeveloperOptionsSwitchDisabled();
        setEnabled(false);
        ((TwoStatePreference) mPreference).setChecked(false);
    }

    private static void setEnabled(boolean enabled) {
        SystemProperties.set(ADB_TCP_ENABLE_PROPERTY, enabled ? "true" : "false");
        // Set after the port, so init applies the port before restarting adbd.
        SystemProperties.set(ADB_TCP_RESTART_PROPERTY, "1");
    }
}
