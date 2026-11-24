package com.endiq.turtlelauncher.utils.http

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class NetworkUtils {
    companion object {
        /**
         * @return whether the network is currently connected
         */
        @JvmStatic
        fun isNetworkAvailable(context: Context): Boolean {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            // Any transport that can reach the internet counts: Wi-Fi, mobile data, Ethernet,
            // USB tethering, VPN and Bluetooth tethering. The old check chained `?:` with `||`,
            // which Kotlin parses as `wifi ?: (false || false || false)`, so a phone on mobile
            // data only (or behind a VPN) was reported as offline and the launcher skipped every
            // download and network-backed step of the launch.
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }
}