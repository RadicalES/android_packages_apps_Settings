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

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.EthernetManager;
import android.net.EthernetManager.InterfaceStateListener;
import android.net.EthernetNetworkUpdateRequest;
import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.StaticIpConfiguration;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper that wraps {@link EthernetManager} to discover the Ethernet interface and to read/write
 * its {@link IpConfiguration}, plus {@link ConnectivityManager} to read the live assigned
 * addresses.
 *
 * <p>The interface name and its persisted {@link IpConfiguration} are sourced from
 * {@link EthernetManager#addInterfaceStateListener}, whose callback is a {@code @SystemApi} and is
 * therefore visible to Settings (unlike the hidden {@code getConfiguration} /
 * {@code getAvailableInterfaces} methods). The framework reports every existing interface
 * (including link-down ones) synchronously when the listener is added, so with an inline executor
 * we can capture the state without blocking.
 *
 * <p>The {@code @SystemApi} listener only reports interfaces that are <em>present</em>
 * (not {@code STATE_ABSENT}). When the cable is unplugged the Ethernet interface can go absent,
 * so the listener yields no configuration and the UI would fall back to showing DHCP with empty
 * fields -- even though a static config is persisted and will re-apply on reconnect. To keep the
 * real settings visible while the interface is absent (unplugged cable, or pre-configuring a
 * unit that is not yet on a network) we mirror the {@link IpConfiguration} into a small private
 * {@link SharedPreferences} cache and fall back to it whenever the live read yields nothing.
 *
 * <p>The currently assigned addresses (which for DHCP are not part of the {@link IpConfiguration})
 * are read from the Ethernet network's {@link LinkProperties} via public
 * {@link ConnectivityManager} APIs.
 */
public class EthernetInterfaceHelper {

    private static final String TAG = "EthernetIfaceHelper";
    private static final String DEFAULT_INTERFACE = "eth0";

    // Private cache so the persisted config survives the interface going absent / Settings restarts.
    private static final String PREFS = "ethernet_config";
    private static final String K_MODE = "mode";        // "static" | "dhcp"
    private static final String K_IP = "ip";
    private static final String K_PREFIX = "prefix";
    private static final String K_GATEWAY = "gateway";
    private static final String K_DNS = "dns";          // comma-separated

    private final EthernetManager mEthernetManager;
    private final ConnectivityManager mConnectivityManager;
    private final SharedPreferences mPrefs;
    private String mInterfaceName;
    private IpConfiguration mConfiguration;

    public EthernetInterfaceHelper(Context context) {
        mEthernetManager = context.getSystemService(EthernetManager.class);
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        refresh();
    }

    /** Returns the name of the Ethernet interface, e.g. {@code eth0}. */
    public String getInterfaceName() {
        if (mInterfaceName == null) {
            refresh();
        }
        return mInterfaceName;
    }

    /**
     * Reads the {@link IpConfiguration} of the Ethernet interface. When the interface is present
     * the live value is returned (and cached); when it is absent -- e.g. the cable is unplugged, a
     * bad static config took the link down, or the unit is being pre-configured off-network -- the
     * last cached value is returned so the UI still shows the real (e.g. static) settings instead
     * of defaulting to DHCP. Returns {@code null} only if nothing has ever been read or written.
     */
    public IpConfiguration getConfiguration() {
        refresh();
        if (mConfiguration != null) {
            cacheConfiguration(mConfiguration);
            return mConfiguration;
        }
        return cachedConfiguration();
    }

    /** Persist a copy of the config so it survives the interface going absent / Settings restarts. */
    private void cacheConfiguration(IpConfiguration cfg) {
        if (mPrefs == null || cfg == null) {
            return;
        }
        final SharedPreferences.Editor e = mPrefs.edit();
        if (cfg.getIpAssignment() == IpAssignment.STATIC && cfg.getStaticIpConfiguration() != null) {
            final StaticIpConfiguration s = cfg.getStaticIpConfiguration();
            e.putString(K_MODE, "static");
            e.putString(K_IP, (s.getIpAddress() != null)
                    ? s.getIpAddress().getAddress().getHostAddress() : "");
            e.putInt(K_PREFIX, (s.getIpAddress() != null) ? s.getIpAddress().getPrefixLength() : 24);
            e.putString(K_GATEWAY, (s.getGateway() != null) ? s.getGateway().getHostAddress() : "");
            final StringBuilder dns = new StringBuilder();
            for (InetAddress d : s.getDnsServers()) {
                if (dns.length() > 0) {
                    dns.append(",");
                }
                dns.append(d.getHostAddress());
            }
            e.putString(K_DNS, dns.toString());
        } else {
            e.putString(K_MODE, "dhcp");
        }
        e.apply();
    }

    /** Rebuild the cached {@link IpConfiguration}, or {@code null} if nothing was cached. */
    private IpConfiguration cachedConfiguration() {
        if (mPrefs == null) {
            return null;
        }
        final String mode = mPrefs.getString(K_MODE, null);
        if (mode == null) {
            return null;
        }
        final IpConfiguration cfg = new IpConfiguration();
        if ("static".equals(mode)) {
            try {
                final StaticIpConfiguration.Builder b = new StaticIpConfiguration.Builder();
                final String ip = mPrefs.getString(K_IP, "");
                if (!ip.isEmpty()) {
                    b.setIpAddress(new LinkAddress(
                            InetAddresses.parseNumericAddress(ip), mPrefs.getInt(K_PREFIX, 24)));
                }
                final String gw = mPrefs.getString(K_GATEWAY, "");
                if (!gw.isEmpty()) {
                    b.setGateway(InetAddresses.parseNumericAddress(gw));
                }
                final List<InetAddress> dnsList = new ArrayList<>();
                for (String d : mPrefs.getString(K_DNS, "").split(",")) {
                    if (!d.trim().isEmpty()) {
                        dnsList.add(InetAddresses.parseNumericAddress(d.trim()));
                    }
                }
                b.setDnsServers(dnsList);
                cfg.setIpAssignment(IpAssignment.STATIC);
                cfg.setStaticIpConfiguration(b.build());
            } catch (Exception e) {
                Log.w(TAG, "Failed to rebuild cached Ethernet config", e);
                return null;
            }
        } else {
            cfg.setIpAssignment(IpAssignment.DHCP);
        }
        return cfg;
    }

    /**
     * Returns the {@link LinkProperties} of the connected Ethernet network (holding the live
     * assigned IP/gateway/DNS), or {@code null} if no Ethernet network is available.
     */
    public LinkProperties getLinkProperties() {
        if (mConnectivityManager == null) {
            return null;
        }
        try {
            for (Network network : mConnectivityManager.getAllNetworks()) {
                final NetworkCapabilities caps =
                        mConnectivityManager.getNetworkCapabilities(network);
                if (caps != null
                        && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return mConnectivityManager.getLinkProperties(network);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read Ethernet link properties", e);
        }
        return null;
    }

    private void refresh() {
        if (mEthernetManager == null) {
            if (mInterfaceName == null) {
                mInterfaceName = DEFAULT_INTERFACE;
            }
            return;
        }
        final String[] ifaceHolder = new String[1];
        final IpConfiguration[] configHolder = new IpConfiguration[1];
        final InterfaceStateListener listener = (iface, state, role, cfg) -> {
            if (ifaceHolder[0] == null && state != EthernetManager.STATE_ABSENT) {
                ifaceHolder[0] = iface;
                configHolder[0] = cfg;
            }
        };
        try {
            mEthernetManager.addInterfaceStateListener(Runnable::run, listener);
            mEthernetManager.removeInterfaceStateListener(listener);
        } catch (Exception e) {
            Log.w(TAG, "Failed to query Ethernet interface state", e);
        }
        if (ifaceHolder[0] != null) {
            mInterfaceName = ifaceHolder[0];
            mConfiguration = configHolder[0];
        } else if (mInterfaceName == null) {
            mInterfaceName = DEFAULT_INTERFACE;
        }
    }

    /**
     * Writes the given {@link IpConfiguration} to the Ethernet interface. Requires the
     * {@code MANAGE_ETHERNET_NETWORKS} signature permission.
     *
     * <p>The config is applied even when the interface is currently absent (the framework queues
     * it and applies it when the interface appears), so a static config can be pre-set off-network.
     * We mirror it into the local cache so the UI reflects it immediately, before any reconnect.
     *
     * @return {@code true} if the request was submitted without throwing.
     */
    public boolean writeConfiguration(IpConfiguration ipConfiguration) {
        if (mEthernetManager == null) {
            return false;
        }
        try {
            final EthernetNetworkUpdateRequest request =
                    new EthernetNetworkUpdateRequest.Builder()
                            .setIpConfiguration(ipConfiguration)
                            .build();
            mEthernetManager.updateConfiguration(
                    getInterfaceName(), request, Runnable::run, null /* callback */);
            cacheConfiguration(ipConfiguration);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to update Ethernet configuration", e);
            return false;
        }
    }
}
