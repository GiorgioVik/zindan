package net.typeblog.shelter.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.regex.Pattern

/**
 * Lean local VPN/tunnel detection (no network I/O). Same signals MacroDroid-style triggers use.
 */
object VpnTunnelDetector {
    private const val TAG = "VpnTunnelDetector"

    private val TUNNEL_NAME = Pattern.compile(
        "^(tun\\d+|wg\\d+|ppp\\d+|tap\\d+|vpn\\d+|utun\\d+|rmnet_data\\d+)$",
        Pattern.CASE_INSENSITIVE
    )

    fun isVpnActive(context: Context): Boolean {
        return try {
            hasTunnelInterface()
                    || hasVpnNetworkTransport(context)
                    || activeNetworkMissingNotVpnCapability(context)
        } catch (e: Exception) {
            Log.w(TAG, "VPN detection failed", e)
            false
        }
    }

    fun logDiagnostics(context: Context) {
        val tun = hasTunnelInterface()
        val transport = hasVpnNetworkTransport(context)
        val active = activeNetworkMissingNotVpnCapability(context)
        Log.i(
            TAG,
            "vpn diag tun=$tun transport=$transport activeNet=$active => ${tun || transport || active}"
        )
    }

    private fun hasTunnelInterface(): Boolean {
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name ?: continue
                if (TUNNEL_NAME.matcher(name.lowercase(Locale.US)).matches()) {
                    Log.d(TAG, "tunnel iface: $name")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Interface scan failed", e)
        }
        return false
    }

    private fun hasVpnNetworkTransport(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    Log.d(TAG, "TRANSPORT_VPN network=$network")
                    return true
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val transportInfo = caps.transportInfo
                    if (transportInfo != null
                        && "android.net.VpnTransportInfo" == transportInfo.javaClass.name
                    ) {
                        Log.d(TAG, "VpnTransportInfo network=$network")
                        return true
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        val legacy = cm.activeNetworkInfo
        if (legacy != null && legacy.type == ConnectivityManager.TYPE_VPN) {
            Log.d(TAG, "legacy TYPE_VPN active")
            return true
        }
        return false
    }

    private fun activeNetworkMissingNotVpnCapability(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val missing = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (missing) {
            Log.d(TAG, "active network lacks NOT_VPN")
        }
        return missing
    }
}
