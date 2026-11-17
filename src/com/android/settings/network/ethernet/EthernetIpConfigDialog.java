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
import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.LinkAddress;
import android.net.StaticIpConfiguration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.android.settings.R;
import com.android.settings.core.instrumentation.InstrumentedDialogFragment;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog that edits the Ethernet {@link IpConfiguration}: DHCP or static (IP address, gateway,
 * network prefix length, DNS1 and DNS2). No proxy handling. The IP validation logic is adapted
 * from {@code WifiConfigController2} with proxy support stripped out.
 */
public class EthernetIpConfigDialog extends InstrumentedDialogFragment {

    private static final String ARG_INTERFACE = "interface";

    private static final int IP_SETTINGS_DHCP = 0;
    private static final int IP_SETTINGS_STATIC = 1;

    private Spinner mIpSettingsSpinner;
    private View mStaticIpFields;
    private EditText mIpAddressView;
    private EditText mGatewayView;
    private EditText mNetworkPrefixLengthView;
    private EditText mDns1View;
    private EditText mDns2View;

    private EthernetInterfaceHelper mEthernetHelper;
    private StaticIpConfiguration mStaticIpConfiguration;

    public static EthernetIpConfigDialog newInstance(String interfaceName) {
        final EthernetIpConfigDialog dialog = new EthernetIpConfigDialog();
        final Bundle args = new Bundle();
        args.putString(ARG_INTERFACE, interfaceName);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.SETTINGS_NETWORK_CATEGORY;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = getContext();
        mEthernetHelper = new EthernetInterfaceHelper(context);

        final View view = LayoutInflater.from(context).inflate(R.layout.ethernet_ip_config, null);
        mIpSettingsSpinner = view.findViewById(R.id.ethernet_ip_settings);
        mStaticIpFields = view.findViewById(R.id.ethernet_static_ip_fields);
        mIpAddressView = view.findViewById(R.id.ethernet_ipaddress);
        mGatewayView = view.findViewById(R.id.ethernet_gateway);
        mNetworkPrefixLengthView = view.findViewById(R.id.ethernet_network_prefix_length);
        mDns1View = view.findViewById(R.id.ethernet_dns1);
        mDns2View = view.findViewById(R.id.ethernet_dns2);

        final ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                context, R.array.wifi_ip_settings, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mIpSettingsSpinner.setAdapter(adapter);

        loadCurrentConfiguration();

        mIpSettingsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                updateFieldVisibility();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateFieldVisibility();

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.wifi_ip_settings)
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

    private void loadCurrentConfiguration() {
        final IpConfiguration config = mEthernetHelper.getConfiguration();
        if (config == null || config.getIpAssignment() != IpAssignment.STATIC) {
            mIpSettingsSpinner.setSelection(IP_SETTINGS_DHCP);
            return;
        }
        mIpSettingsSpinner.setSelection(IP_SETTINGS_STATIC);
        final StaticIpConfiguration staticConfig = config.getStaticIpConfiguration();
        if (staticConfig == null) {
            return;
        }
        final LinkAddress linkAddress = staticConfig.getIpAddress();
        if (linkAddress != null) {
            mIpAddressView.setText(linkAddress.getAddress().getHostAddress());
            mNetworkPrefixLengthView.setText(Integer.toString(linkAddress.getPrefixLength()));
        }
        if (staticConfig.getGateway() != null) {
            mGatewayView.setText(staticConfig.getGateway().getHostAddress());
        }
        final List<InetAddress> dnsServers = staticConfig.getDnsServers();
        if (dnsServers.size() > 0) {
            mDns1View.setText(dnsServers.get(0).getHostAddress());
        }
        if (dnsServers.size() > 1) {
            mDns2View.setText(dnsServers.get(1).getHostAddress());
        }
    }

    private void updateFieldVisibility() {
        final boolean isStatic =
                mIpSettingsSpinner.getSelectedItemPosition() == IP_SETTINGS_STATIC;
        mStaticIpFields.setVisibility(isStatic ? View.VISIBLE : View.GONE);
    }

    private boolean submit() {
        final Context context = getContext();
        final IpConfiguration ipConfig = new IpConfiguration();
        if (mIpSettingsSpinner.getSelectedItemPosition() == IP_SETTINGS_STATIC) {
            final int result = validateIpConfigFields();
            if (result != 0) {
                Toast.makeText(context, result, Toast.LENGTH_LONG).show();
                return false;
            }
            ipConfig.setIpAssignment(IpAssignment.STATIC);
            ipConfig.setStaticIpConfiguration(mStaticIpConfiguration);
        } else {
            ipConfig.setIpAssignment(IpAssignment.DHCP);
            ipConfig.setStaticIpConfiguration(null);
        }

        final boolean ok = mEthernetHelper.writeConfiguration(ipConfig);
        Toast.makeText(context,
                ok ? R.string.ethernet_config_saved : R.string.ethernet_config_failed,
                Toast.LENGTH_SHORT).show();
        if (ok && getTargetFragment() instanceof EthernetSettings) {
            ((EthernetSettings) getTargetFragment()).onIpConfigurationUpdated();
        }
        return ok;
    }

    private Inet4Address getIPv4Address(String text) {
        try {
            return (Inet4Address) InetAddresses.parseNumericAddress(text);
        } catch (IllegalArgumentException | ClassCastException e) {
            return null;
        }
    }

    /**
     * Validates the static IP fields and, on success, stores the built
     * {@link StaticIpConfiguration} in {@link #mStaticIpConfiguration}.
     *
     * @return {@code 0} on success, otherwise a string resource id describing the error.
     */
    private int validateIpConfigFields() {
        final String ipAddr = mIpAddressView.getText().toString();
        if (TextUtils.isEmpty(ipAddr)) {
            return R.string.wifi_ip_settings_invalid_ip_address;
        }
        final Inet4Address inetAddr = getIPv4Address(ipAddr);
        if (inetAddr == null || inetAddr.equals(Inet4Address.ANY)) {
            return R.string.wifi_ip_settings_invalid_ip_address;
        }

        final StaticIpConfiguration.Builder builder = new StaticIpConfiguration.Builder();

        final int networkPrefixLength;
        try {
            networkPrefixLength =
                    Integer.parseInt(mNetworkPrefixLengthView.getText().toString());
            if (networkPrefixLength < 0 || networkPrefixLength > 32) {
                return R.string.wifi_ip_settings_invalid_network_prefix_length;
            }
        } catch (NumberFormatException e) {
            return R.string.wifi_ip_settings_invalid_network_prefix_length;
        }
        try {
            builder.setIpAddress(new LinkAddress(inetAddr, networkPrefixLength));
        } catch (IllegalArgumentException e) {
            return R.string.wifi_ip_settings_invalid_ip_address;
        }

        final String gateway = mGatewayView.getText().toString();
        if (!TextUtils.isEmpty(gateway)) {
            final InetAddress gatewayAddr = getIPv4Address(gateway);
            if (gatewayAddr == null || gatewayAddr.isMulticastAddress()) {
                return R.string.wifi_ip_settings_invalid_gateway;
            }
            builder.setGateway(gatewayAddr);
        }

        final ArrayList<InetAddress> dnsServers = new ArrayList<>();
        final String dns1 = mDns1View.getText().toString();
        if (!TextUtils.isEmpty(dns1)) {
            final InetAddress dnsAddr = getIPv4Address(dns1);
            if (dnsAddr == null) {
                return R.string.wifi_ip_settings_invalid_dns;
            }
            dnsServers.add(dnsAddr);
        }
        final String dns2 = mDns2View.getText().toString();
        if (!TextUtils.isEmpty(dns2)) {
            final InetAddress dnsAddr = getIPv4Address(dns2);
            if (dnsAddr == null) {
                return R.string.wifi_ip_settings_invalid_dns;
            }
            dnsServers.add(dnsAddr);
        }
        builder.setDnsServers(dnsServers);

        mStaticIpConfiguration = builder.build();
        return 0;
    }
}
