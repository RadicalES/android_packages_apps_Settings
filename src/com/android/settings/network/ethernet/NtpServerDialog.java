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

package com.android.settings.network.ethernet;

import android.app.Dialog;
import android.app.settings.SettingsEnums;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.NtpTrustedTime;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.android.settings.R;
import com.android.settings.core.instrumentation.InstrumentedDialogFragment;

/**
 * Dialog that edits the NTP server used for time sync, writing {@link
 * Settings.Global#NTP_SERVER}.
 *
 * <p>Sites on a static address or behind a locked-down network commonly cannot reach the AOSP
 * default ({@code config_ntpServers}) and run their own time server instead. Android does not use
 * the DHCP NTP option (code 42), so without this there is no way to point a device at it. A board
 * without a battery-backed RTC that cannot sync starts at the epoch, and TLS then fails because
 * every certificate looks not yet valid.
 *
 * <p>An empty value clears the override so the AOSP default applies again.
 */
public class NtpServerDialog extends InstrumentedDialogFragment {

    private EditText mServerView;

    public static NtpServerDialog newInstance() {
        return new NtpServerDialog();
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.SETTINGS_NETWORK_CATEGORY;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = getContext();
        final View view = LayoutInflater.from(context).inflate(R.layout.ethernet_ntp_server, null);
        mServerView = view.findViewById(R.id.ethernet_ntp_server);
        mServerView.setText(getConfiguredServer(context));

        final TextView defaultView = view.findViewById(R.id.ethernet_ntp_default);
        defaultView.setText(
                getString(R.string.ethernet_ntp_dialog_default, getDefaultServers(context)));

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.ethernet_ntp_title)
                .setView(view)
                .setPositiveButton(R.string.save, null /* set below to avoid auto-dismiss */)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            final Button save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                if (submit()) {
                    dialog.dismiss();
                }
            });
        });
        return dialog;
    }

    /** The value the user has set, or null/empty when the AOSP default is in use. */
    static String getConfiguredServer(Context context) {
        return Settings.Global.getString(
                context.getContentResolver(), Settings.Global.NTP_SERVER);
    }

    /** The built-in default(s) from {@code config_ntpServers}, for display only. */
    static String getDefaultServers(Context context) {
        final String[] servers = context.getResources()
                .getStringArray(com.android.internal.R.array.config_ntpServers);
        return String.join(", ", servers);
    }

    private boolean submit() {
        final Context context = getContext();
        final String value = mServerView.getText().toString().trim();

        // Validate with the framework's own parser rather than a local rule: NtpTrustedTime
        // silently ignores a setting it cannot parse and falls back to config_ntpServers, so a
        // value this dialog accepts but the framework rejects would look applied and do nothing.
        if (!TextUtils.isEmpty(value) && NtpTrustedTime.parseNtpServerSetting(value) == null) {
            Toast.makeText(context, R.string.ethernet_ntp_invalid, Toast.LENGTH_LONG).show();
            return false;
        }

        final boolean saved = Settings.Global.putString(context.getContentResolver(),
                Settings.Global.NTP_SERVER, TextUtils.isEmpty(value) ? null : value);
        Toast.makeText(context,
                saved ? R.string.ethernet_ntp_saved : R.string.ethernet_ntp_save_failed,
                Toast.LENGTH_LONG).show();

        if (saved && getTargetFragment() instanceof EthernetSettings) {
            ((EthernetSettings) getTargetFragment()).onNtpServerUpdated();
        }
        return saved;
    }
}
