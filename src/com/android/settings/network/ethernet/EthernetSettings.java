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

import android.app.settings.SettingsEnums;
import android.content.Context;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.StaticIpConfiguration;
import android.net.RouteInfo;
import android.text.TextUtils;

import androidx.preference.Preference;

import com.android.settings.R;
import com.android.settings.dashboard.DashboardFragment;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Ethernet IP configuration screen. Reachable from Network &amp; internet via the connected
 * Ethernet preference. Allows switching between DHCP and static IP settings.
 */
public class EthernetSettings extends DashboardFragment {

    private static final String TAG = "EthernetSettings";

    private static final String KEY_STATUS = "ethernet_status";
    private static final String KEY_IP_SETTINGS = "ethernet_ip_settings";
    private static final String KEY_CURRENT_IP = "ethernet_current_ip";
    private static final String KEY_CURRENT_GATEWAY = "ethernet_current_gateway";
    private static final String KEY_CURRENT_DNS = "ethernet_current_dns";
    private static final String KEY_NTP_SERVER = "ethernet_ntp_server";
    private static final String DIALOG_TAG = "EthernetIpConfigDialog";
    private static final String NTP_DIALOG_TAG = "NtpServerDialog";

    private EthernetInterfaceHelper mEthernetHelper;
    private Preference mStatusPreference;
    private Preference mIpSettingsPreference;
    private Preference mCurrentIpPreference;
    private Preference mCurrentGatewayPreference;
    private Preference mCurrentDnsPreference;
    private Preference mNtpServerPreference;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mEthernetHelper = new EthernetInterfaceHelper(context);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSummaries();
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (KEY_IP_SETTINGS.equals(preference.getKey())) {
            final EthernetIpConfigDialog dialog =
                    EthernetIpConfigDialog.newInstance(mEthernetHelper.getInterfaceName());
            dialog.setTargetFragment(this, 0 /* requestCode */);
            dialog.show(getFragmentManager(), DIALOG_TAG);
            return true;
        }
        if (KEY_NTP_SERVER.equals(preference.getKey())) {
            final NtpServerDialog dialog = NtpServerDialog.newInstance();
            dialog.setTargetFragment(this, 0 /* requestCode */);
            dialog.show(getFragmentManager(), NTP_DIALOG_TAG);
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    /** Called by the IP config dialog after a successful write to refresh the summaries. */
    public void onIpConfigurationUpdated() {
        updateSummaries();
    }

    /** Called by the NTP server dialog after a successful write to refresh the summary. */
    public void onNtpServerUpdated() {
        updateSummaries();
    }

    private void updateSummaries() {
        if (mStatusPreference == null) {
            mStatusPreference = findPreference(KEY_STATUS);
        }
        if (mIpSettingsPreference == null) {
            mIpSettingsPreference = findPreference(KEY_IP_SETTINGS);
        }
        if (mCurrentIpPreference == null) {
            mCurrentIpPreference = findPreference(KEY_CURRENT_IP);
        }
        if (mCurrentGatewayPreference == null) {
            mCurrentGatewayPreference = findPreference(KEY_CURRENT_GATEWAY);
        }
        if (mCurrentDnsPreference == null) {
            mCurrentDnsPreference = findPreference(KEY_CURRENT_DNS);
        }
        if (mNtpServerPreference == null) {
            mNtpServerPreference = findPreference(KEY_NTP_SERVER);
        }
        if (mNtpServerPreference != null) {
            final String ntpServer = NtpServerDialog.getConfiguredServer(getContext());
            mNtpServerPreference.setSummary(TextUtils.isEmpty(ntpServer)
                    ? getString(R.string.ethernet_ntp_summary_default,
                            NtpServerDialog.getDefaultServers(getContext()))
                    : ntpServer);
        }
        final String iface = mEthernetHelper.getInterfaceName();
        final IpConfiguration config = mEthernetHelper.getConfiguration();
        final boolean isStatic = config != null
                && config.getIpAssignment() == IpAssignment.STATIC;
        if (mStatusPreference != null) {
            mStatusPreference.setSummary(
                    TextUtils.isEmpty(iface) ? getString(R.string.summary_placeholder) : iface);
        }
        if (mIpSettingsPreference != null) {
            final String[] modes = getResources().getStringArray(R.array.wifi_ip_settings);
            mIpSettingsPreference.setSummary(modes[isStatic ? 1 : 0]);
        }
        updateCurrentAddresses(mEthernetHelper.getLinkProperties(), config);
    }

    /** Populates the read-only "current configuration" section from live {@link LinkProperties}. */
    private void updateCurrentAddresses(LinkProperties linkProperties, IpConfiguration config) {
        String ipText = null;
        String gatewayText = null;
        String dnsText = null;

        if (linkProperties != null) {
            final List<String> ipAddresses = new ArrayList<>();
            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                if (linkAddress.getAddress() instanceof Inet4Address) {
                    ipAddresses.add(linkAddress.getAddress().getHostAddress()
                            + "/" + linkAddress.getPrefixLength());
                }
            }
            if (!ipAddresses.isEmpty()) {
                ipText = TextUtils.join("\n", ipAddresses);
            }

            for (RouteInfo route : linkProperties.getRoutes()) {
                if (route.isDefaultRoute() && route.getGateway() != null) {
                    gatewayText = route.getGateway().getHostAddress();
                    break;
                }
            }

            final List<String> dnsServers = new ArrayList<>();
            for (InetAddress dns : linkProperties.getDnsServers()) {
                dnsServers.add(dns.getHostAddress());
            }
            if (!dnsServers.isEmpty()) {
                dnsText = TextUtils.join("\n", dnsServers);
            }
        }

        // With no live link (cable unplugged, or configuring a device before it is on the network)
        // fall back to the configured static addresses so the user still sees what is set.
        if (config != null && config.getIpAssignment() == IpAssignment.STATIC
                && config.getStaticIpConfiguration() != null) {
            final StaticIpConfiguration s = config.getStaticIpConfiguration();
            if (ipText == null && s.getIpAddress() != null) {
                ipText = s.getIpAddress().getAddress().getHostAddress()
                        + "/" + s.getIpAddress().getPrefixLength();
            }
            if (gatewayText == null && s.getGateway() != null) {
                gatewayText = s.getGateway().getHostAddress();
            }
            if (dnsText == null && !s.getDnsServers().isEmpty()) {
                final List<String> staticDns = new ArrayList<>();
                for (InetAddress dns : s.getDnsServers()) {
                    staticDns.add(dns.getHostAddress());
                }
                dnsText = TextUtils.join("\n", staticDns);
            }
        }

        final String placeholder = getString(R.string.summary_placeholder);
        if (mCurrentIpPreference != null) {
            mCurrentIpPreference.setSummary(ipText != null ? ipText : placeholder);
        }
        if (mCurrentGatewayPreference != null) {
            mCurrentGatewayPreference.setSummary(gatewayText != null ? gatewayText : placeholder);
        }
        if (mCurrentDnsPreference != null) {
            mCurrentDnsPreference.setSummary(dnsText != null ? dnsText : placeholder);
        }
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.ethernet_settings;
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.SETTINGS_NETWORK_CATEGORY;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }
}
