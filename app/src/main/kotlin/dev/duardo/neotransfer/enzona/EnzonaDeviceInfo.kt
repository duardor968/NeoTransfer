package dev.duardo.neotransfer.enzona

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address

/** Recovered APK fields and absence literals; no privileged identifiers or location permissions. */
internal class EnzonaDeviceInfo(context: Context) {
    private val context = context.applicationContext

    fun fields(): Map<String, String> {
        var networkType = "UNKNOWN"
        var ip = "ip_not_available"
        try {
            // Root adds ACCESS_NETWORK_STATE. Missing access leaves observed absence values intact.
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            val capabilities = network?.let { manager.getNetworkCapabilities(it) }
            networkType = when {
                network == null && manager != null -> "NO_CONNECTION"
                capabilities == null -> "UNKNOWN"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
                else -> "OTHER"
            }
            ip = network?.let { manager.getLinkProperties(it) }?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.address?.hostAddress ?: ip
        } catch (_: SecurityException) { /* No request for an additional/privileged permission. */ }
        return linkedMapOf(
            "accessType" to "MOBILE",
            "subtype_name" to networkType,
            "extra_info" to "network_info_not_available",
            "ip_address_mobile" to ip,
            "version_release" to available(Build.VERSION.RELEASE, "version_unknown"),
            // serialNumber is a DEVICE serial, inaccessible to an ordinary SDK35+ application.
            "sim_serial_number" to "sim_not_available",
            "host" to available(Build.HOST, "host_not_available"),
            "imei" to available(Build.ID, "imei_not_available"),
            "model" to available(Build.MODEL, "model_not_available"),
            "board" to available(Build.BOARD, "board_not_available"),
            "manufacturer" to available(Build.MANUFACTURER, "manufacturer_not_available"),
        )
    }

    private fun available(value: String?, absent: String) =
        value?.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: absent
}
