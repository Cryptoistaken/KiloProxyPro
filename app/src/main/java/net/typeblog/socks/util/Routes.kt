package net.typeblog.socks.util

import android.content.Context
import android.net.VpnService
import net.typeblog.socks.R
import net.typeblog.socks.util.Constants.ROUTE_ALL
import net.typeblog.socks.util.Constants.ROUTE_CHN
import net.typeblog.socks.util.Constants.ROUTE_RU
import net.typeblog.socks.util.Constants.ROUTE_RU_CHN

object Routes {
    @JvmStatic
    fun addRoutes(context: Context, builder: VpnService.Builder, name: String) {
        addRoutes(context, builder, name, null)
    }

    /**
     * Same as addRoutes, but when excludeIp is set the covering supernet
     * (if any) is replaced by carve-outs so that one host address routes
     * outside the tunnel. Used by the hev engine path: the Builder DNS
     * server is excluded so plain DNS goes direct to the real resolver
     * instead of dying on SOCKS UDP at TCP-only proxies. Same privacy
     * posture as the stock pdnsd path, which also resolves directly.
     */
    @JvmStatic
    fun addRoutes(context: Context, builder: VpnService.Builder, name: String, excludeIp: String?) {
        val routes = ArrayList<String>()
        when (name) {
            ROUTE_ALL -> routes.add("0.0.0.0/0")
            ROUTE_CHN -> routes.addAll(context.resources.getStringArray(R.array.simple_route).toList())
            ROUTE_RU -> routes.addAll(context.resources.getStringArray(R.array.ru_route).toList())
            ROUTE_RU_CHN -> {
                routes.addAll(context.resources.getStringArray(R.array.simple_route).toList())
                routes.addAll(context.resources.getStringArray(R.array.ru_route).toList())
            }
            else -> routes.add("0.0.0.0/0")
        }

        val finalRoutes = if (excludeIp.isNullOrEmpty()) routes else excludeIpv4(routes, excludeIp)
        for (r in finalRoutes) {
            val cidr = r.split("/")

            // Cannot handle 127.0.0.0/8
            if (cidr.size == 2 && !cidr[0].startsWith("127")) {
                try {
                    builder.addRoute(cidr[0], cidr[1].toInt())
                } catch (e: Exception) {
                    // Ignore invalid routes
                }
            }
        }
    }

    /**
     * Returns route list covering the same space minus a single host /32.
     * Pure computation, no Android calls, so unit tests cover it on JVM.
     * A covering supernet a.b.c.d/n is replaced by the sibling halves down
     * to /32 (at most 32 entries for 0.0.0.0/0); untouched lists pass
     * through as-is, including unparseable entries.
     */
    @JvmStatic
    fun excludeIpv4(routes: List<String>, ip: String): List<String> {
        val target = ipv4ToLong(ip) ?: return routes
        val out = ArrayList<String>()
        for (r in routes) {
            val parts = r.split("/")
            if (parts.size != 2) {
                out.add(r)
                continue
            }
            val base = ipv4ToLong(parts[0].trim())
            if (base == null) {
                out.add(r)
                continue
            }
            val len = parts[1].trim().toIntOrNull()
            if (len == null || len < 0 || len > 32) {
                out.add(r)
                continue
            }
            if (len == 32) {
                if (base != target) out.add(r)
                continue
            }
            if (!subnetCovers(base, len, target)) {
                out.add(r)
                continue
            }
            // Split down until the target half is a lone /32, keeping every
            // sibling along the way.
            var curBase: Long = base
            var curLen: Int = len
            while (curLen < 32) {
                val halfSize = 1L shl (32 - curLen - 1)
                val mid = curBase + halfSize
                if (target < mid) {
                    out.add("${longToIpv4(mid)}/${curLen + 1}")
                    curLen += 1
                } else {
                    out.add("${longToIpv4(curBase)}/${curLen + 1}")
                    curBase = mid
                    curLen += 1
                }
            }
        }
        return out
    }

    private fun subnetCovers(base: Long, len: Int, target: Long): Boolean {
        if (len == 0) return true
        val mask = (-1L shl (32 - len)) and 0xFFFFFFFFL
        return (base and mask) == (target and mask)
    }

    private fun ipv4ToLong(ip: String): Long? {
        return try {
            val p = ip.trim().split(".")
            if (p.size != 4) return null
            var v = 0L
            for (oct in p) {
                val o = oct.toInt()
                if (o < 0 || o > 255) return null
                v = (v shl 8) or o.toLong()
            }
            v
        } catch (_: Exception) {
            null
        }
    }

    private fun longToIpv4(v: Long): String {
        return "${(v ushr 24) and 0xFFL}.${(v ushr 16) and 0xFFL}.${(v ushr 8) and 0xFFL}.${v and 0xFFL}"
    }
}
