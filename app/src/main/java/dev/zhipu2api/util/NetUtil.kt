package dev.zhipu2api.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {

    /** 返回本机第一个非回环 IPv4 地址，失败返回 127.0.0.1 */
    fun getLocalIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }
}
