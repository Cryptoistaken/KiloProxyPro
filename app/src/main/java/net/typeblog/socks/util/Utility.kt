package net.typeblog.socks.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import net.typeblog.socks.SocksVpnService
import net.typeblog.socks.util.Constants.INTENT_APP_BYPASS
import net.typeblog.socks.util.Constants.INTENT_APP_LIST
import net.typeblog.socks.util.Constants.INTENT_DNS
import net.typeblog.socks.util.Constants.INTENT_DNS_PORT
import net.typeblog.socks.util.Constants.INTENT_IPV6_PROXY
import net.typeblog.socks.util.Constants.INTENT_NAME
import net.typeblog.socks.util.Constants.INTENT_PER_APP
import net.typeblog.socks.util.Constants.INTENT_PORT
import net.typeblog.socks.util.Constants.INTENT_ROUTE
import net.typeblog.socks.util.Constants.INTENT_SERVER
import net.typeblog.socks.util.Constants.INTENT_USERNAME
import net.typeblog.socks.util.Constants.INTENT_PASSWORD
import net.typeblog.socks.util.Constants.INTENT_UDP_GW
import net.typeblog.socks.util.Constants.PREF_ADV_APP_BYPASS
import net.typeblog.socks.util.Constants.PREF_ADV_APP_LIST
import net.typeblog.socks.util.Constants.PREF_ADV_PER_APP
import net.typeblog.socks.util.Constants.ACCEL_PRIMARY_KILOIP

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import org.json.JSONObject

data class IpInfo(
    val ip: String,
    val countryCode: String,
    val country: String = "",
    val regionName: String = "",
    val city: String = "",
    val isp: String = "",
    val org: String = "",
    val asName: String = "",
    val timezone: String = ""
)

object Utility {
    private val TAG = Utility::class.java.simpleName

    @JvmStatic
    fun extractFile(context: Context) {
        // No longer needed: we run libpdnsd.so and libtun2socks.so directly from nativeLibraryDir
    }

    @JvmStatic
    fun exec(cmd: String): Int {
        return try {
            Log.d(TAG, "Executing: $cmd")
            val p = Runtime.getRuntime().exec(cmd)
            BufferedReader(InputStreamReader(p.errorStream)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    Log.e(TAG, "STDERR: $line")
                    line = br.readLine()
                }
            }
            val ret = p.waitFor()
            Log.d(TAG, "Process exited with: $ret")
            ret
        } catch (e: Exception) {
            Log.e(TAG, "exec failed", e)
            -1
        }
    }

    @JvmStatic
    fun exec(cmd: Array<String>): Int {
        return try {
            Log.d(TAG, "Executing: ${cmd.contentToString()}")
            val pb = ProcessBuilder(*cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    Log.d(TAG, "exec: $line")
                    line = br.readLine()
                }
            }
            val ret = p.waitFor()
            Log.d(TAG, "Process '${cmd.firstOrNull() ?: "?"}' exited with: $ret")
            ret
        } catch (e: Exception) {
            Log.e(TAG, "exec failed for cmd: ${cmd.contentToString()}", e)
            -1
        }
    }

    @JvmStatic
    fun killPidFile(f: String) {
        val file = File(f)
        if (!file.exists()) return
        val str = StringBuilder()
        try {
            FileInputStream(file).use { i ->
                val buf = ByteArray(512)
                var len = i.read(buf, 0, 512)
                while (len > 0) {
                    str.append(String(buf, 0, len))
                    len = i.read(buf, 0, 512)
                }
            }
        } catch (_: Exception) {
            return
        }
        try {
            val pid = str.toString().trim().replace("\n", "").toInt()
            Runtime.getRuntime().exec("kill $pid").waitFor()
            file.delete()
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun join(list: List<String>?, separator: String): String {
        if (list == null || list.isEmpty()) return ""
        val ret = StringBuilder()
        for (s in list) {
            ret.append(s).append(separator)
        }
        return ret.substring(0, ret.length - separator.length)
    }

    @JvmStatic
    fun makePdnsdConf(context: Context, dns: String, port: Int, upstream: String? = null) {
        val dir = context.filesDir.absolutePath
        val conf = String.format(context.getString(net.typeblog.socks.R.string.pdnsd_conf), dir, dir, upstream ?: dns, port)
        val f = File("$dir/pdnsd.conf")
        if (f.exists()) f.delete()
        try {
            FileOutputStream(f).use { out ->
                out.write(conf.toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
        }
        val cache = File("$dir/pdnsd.cache")
        if (!cache.exists()) {
            try { cache.createNewFile() } catch (_: Exception) {}
        }
    }

    /**
     * Writes the hev-socks5-tunnel YAML config and returns its path.
     * hev takes the TUN fd directly via JNI, so no sendfd/dnsgw/udpgw
     * options exist: DNS flows through the tunnel to the Builder DNS
     * server (8.8.8.8) over SOCKS, and UDP uses native UDP ASSOCIATE.
     * Synchronous, call from a background thread. Plain ASCII only.
     */
    @JvmStatic
    fun makeHevConf(dir: String, serverIp: String?, port: Int, user: String?, passwd: String?, ipv6: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("tunnel:")
        sb.appendLine("  name: tun0")
        sb.appendLine("  mtu: 1500")
        sb.appendLine("  ipv4: 10.10.10.2")
        if (ipv6) sb.appendLine("  ipv6: 'fdfe:dcba:9876::2'")
        sb.appendLine("socks5:")
        sb.appendLine("  port: $port")
        sb.appendLine("  address: '${serverIp ?: ""}'")
        sb.appendLine("  udp: 'udp'")
        if (!user.isNullOrEmpty()) {
            sb.appendLine("  username: '$user'")
            sb.appendLine("  password: '${passwd ?: ""}'")
        }
        sb.appendLine("misc:")
        sb.appendLine("  log-level: warn")
        sb.appendLine("  connect-timeout: 10000")
        sb.appendLine("  udp-read-write-timeout: 60000")
        val f = File("$dir/hev.yml")
        if (f.exists()) f.delete()
        try {
            FileOutputStream(f).use { out ->
                out.write(sb.toString().toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
        }
        return f.absolutePath
    }

    @JvmStatic
    fun startVpn(context: Context, profile: Profile) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // Single source of truth: global SplitTunnelingScreen prefs. Profile perapp is legacy; global takes precedence when enabled.
        val globalPerApp = prefs.getBoolean(PREF_ADV_PER_APP, false)
        val perApp = profile.isPerApp() || globalPerApp
        val bypass: Boolean
        val appList: String
        if (globalPerApp) {
            bypass = prefs.getBoolean(PREF_ADV_APP_BYPASS, false)
            appList = prefs.getString(PREF_ADV_APP_LIST, "") ?: ""
        } else if (profile.isPerApp()) {
            bypass = profile.isBypassApp()
            appList = profile.getAppList()
        } else {
            bypass = false
            appList = ""
        }

        val i = Intent(context, SocksVpnService::class.java)
            .putExtra(INTENT_NAME, profile.getName())
            .putExtra(INTENT_SERVER, profile.getServer())
            .putExtra(INTENT_PORT, profile.getPort())
            .putExtra(INTENT_ROUTE, profile.getRoute())
            .putExtra(INTENT_DNS, profile.getDns())
            .putExtra(INTENT_DNS_PORT, profile.getDnsPort())
            .putExtra(INTENT_PER_APP, perApp)
            .putExtra(INTENT_IPV6_PROXY, profile.hasIPv6())

        if (perApp) {
            i.putExtra(INTENT_APP_BYPASS, bypass)
                .putExtra(INTENT_APP_LIST, appList.split("\n").filter { it.isNotEmpty() }.toTypedArray())
        }

        i.putExtra(INTENT_USERNAME, profile.getUsername())
        i.putExtra(INTENT_PASSWORD, profile.getPassword())

        if (profile.hasUDP()) {
            i.putExtra(INTENT_UDP_GW, profile.getUDPGW())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i)
        } else {
            context.startService(i)
        }
    }

    @JvmStatic
    fun checkPublicIp(): IpInfo? {
        return checkPublicIp(null, 0, null, null)
    }

    @JvmStatic
    fun checkPublicIp(server: String?, port: Int, username: String?, password: String?): IpInfo? {
        // Stock behavior: kiloip first, trace as fallback. Always fresh.
        return checkWith(server, port, username, password, ACCEL_PRIMARY_KILOIP, true)
    }

    /**
     * Checker with Advanced Settings selection. primary is ACCEL_PRIMARY_*
     * ("trace" = fast IP and country at connect time, "kiloip" = full
     * details). both = run the other one after: as enrichment when the
     * primary succeeds, as fallback when it fails.
     */
    @JvmStatic
    fun checkWith(
        server: String?,
        port: Int,
        username: String?,
        password: String?,
        primary: String,
        both: Boolean
    ): IpInfo? {
        val first: (String?, Int, String?, String?) -> IpInfo? =
            if (primary == ACCEL_PRIMARY_KILOIP) ::fetchKiloIp else ::fetchTrace
        val second: (String?, Int, String?, String?) -> IpInfo? =
            if (primary == ACCEL_PRIMARY_KILOIP) ::fetchTrace else ::fetchKiloIp
        first(server, port, username, password)?.let { return it }
        if (!both) return null
        return second(server, port, username, password)
    }

    private fun fetchKiloIp(server: String?, port: Int, username: String?, password: String?): IpInfo? =
        fetchCheckText(KILO_IP_URL, "kiloip", server, port, username, password, ::parseKiloIp)

    private fun fetchTrace(server: String?, port: Int, username: String?, password: String?): IpInfo? =
        fetchCheckText(TRACE_URL, "trace", server, port, username, password, ::parseTrace)

    private fun fetchCheckText(
        url: String,
        tag: String,
        server: String?,
        port: Int,
        username: String?,
        password: String?,
        parse: (String) -> IpInfo?
    ): IpInfo? {
        var conn: HttpURLConnection? = null
        var authSet = false
        return try {
            conn = if (server.isNullOrEmpty()) {
                URL(url).openConnection() as HttpURLConnection
            } else {
                URL(url).openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress(server, port))) as HttpURLConnection
            }
            if (!server.isNullOrEmpty() && !username.isNullOrEmpty()) {
                val user = username
                val pass = password ?: ""
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(user, pass.toCharArray())
                })
                authSet = true
            }
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val text = try {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } catch (_: Exception) {
                return null
            }
            parse(text)
        } catch (e: Exception) {
            Log.d("Utility", "checkPublicIp($tag) failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
            if (authSet) Authenticator.setDefault(null)
        }
    }

    private fun parseKiloIp(text: String): IpInfo? {
        return try {
            val obj = JSONObject(text)
            val ip = obj.optString("ip")
            if (ip.isEmpty()) return null
            IpInfo(
                ip = ip,
                countryCode = obj.optString("countryCode"),
                country = obj.optString("country"),
                regionName = obj.optString("regionName"),
                city = obj.optString("city"),
                isp = obj.optString("isp"),
                org = obj.optString("org"),
                asName = obj.optString("asName"),
                timezone = obj.optString("timezone")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTrace(text: String): IpInfo? {
        return try {
            var ip = ""
            var loc = ""
            text.lineSequence().forEach { line ->
                when {
                    line.startsWith("ip=") -> ip = line.substringAfter("=").trim()
                    line.startsWith("loc=") -> loc = line.substringAfter("=").trim()
                }
            }
            if (ip.isEmpty()) return null
            IpInfo(ip = ip, countryCode = loc)
        } catch (_: Exception) {
            null
        }
    }

    private const val KILO_IP_URL = "https://kiloproxy.traderspopy.workers.dev/"
    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    // Canonical usage-stats key suffix: same in :vpn (writer) and UI (reader).
    @JvmStatic
    fun usageSuffix(name: String): String =
        try { java.net.URLEncoder.encode(name, "UTF-8") } catch (_: Exception) { name.hashCode().toString() }

    @JvmStatic
    fun countryCodeToFlag(countryCode: String): String {
        if (countryCode.length != 2) return "\uD83C\uDF10"
        val first = String(Character.toChars(0x1F1E6 - 'A'.code + countryCode[0].uppercaseChar().code))
        val second = String(Character.toChars(0x1F1E6 - 'A'.code + countryCode[1].uppercaseChar().code))
        return "$first$second"
    }

    /**
     * Reads the per-interface transmit/receive byte counters for the VPN tunnel.
     *
     * Because tun2socks forwards packets natively (Java never sees individual
     * packets), the only reliable cross-process counter is the kernel's own
     * sysfs statistics for the tun interface created by VpnService. We locate
     * it by the unique tunnel address (10.10.10.1) we assign and sum its RX/TX.
     *
     * @return Pair(rxBytes, txBytes) on success, null if the interface is missing.
     */
    @JvmStatic
    fun readTunBytes(): Pair<Long, Long>? {
        return try {
            val iface = findTunInterface() ?: return null
            val rxFile = File("/sys/class/net/$iface/statistics/rx_bytes")
            val txFile = File("/sys/class/net/$iface/statistics/tx_bytes")
            if (!rxFile.exists() || !txFile.exists()) return null
            val rx = rxFile.readText().trim().toLongOrNull() ?: return null
            val tx = txFile.readText().trim().toLongOrNull() ?: return null
            Pair(rx, tx)
        } catch (_: Exception) {
            null
        }
    }

    private fun findTunInterface(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    // Our tunnel always installs 10.10.10.1/24 on the tun interface.
                    if (addr.hostAddress == "10.10.10.1") return ni.name
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun getRecentCountries(context: Context): List<String> {
        val file = java.io.File(context.filesDir, "recent_countries.txt")
        if (!file.exists()) return emptyList()
        return try {
            file.readText().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @JvmStatic
    fun addRecentCountry(context: Context, code: String) {
        val normalized = code.trim().uppercase()
        if (normalized.isEmpty()) return
        val existing = getRecentCountries(context)
        val updated = (listOf(normalized) + existing.filter { it != normalized }).take(10)
        try {
            java.io.File(context.filesDir, "recent_countries.txt").writeText(updated.joinToString(","))
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
            bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    // ---- VPN Accelerator (experimental, behind PREF_VPN_ACCELERATOR) ----
    // File-backed caches (never in-memory): the :vpn process may die between
    // connects, and the UI process must be able to warm the DNS entry.
    private const val ACCEL_DNS_TTL_MS = 10 * 60 * 1000L
    private const val ACCEL_IP_TTL_MS = 24 * 60 * 60 * 1000L

    /** Cache key for one proxy identity. Same shape as usageSuffix(). */
    @JvmStatic
    fun accelKey(server: String?, port: Int, username: String?): String {
        val raw = "${server ?: ""}:$port:${username ?: ""}"
        return try { java.net.URLEncoder.encode(raw, "UTF-8") } catch (_: Exception) { raw.hashCode().toString() }
    }

    /**
     * Resolve the SOCKS hostname, using the file DNS cache when accelerated.
     * Flag OFF (or cache miss/expiry) resolves exactly like before.
     */
    @JvmStatic
    fun resolveServerHost(context: Context, server: String?, accelerated: Boolean): String? {
        if (server.isNullOrEmpty()) return server
        if (accelerated) {
            readAccelDns(context, server)?.let { return it }
        }
        val ip = resolveHost(server)
        if (accelerated && ip != null) writeAccelDns(context, server, ip)
        return ip
    }

    /** Best-effort app-start warm-up; caller must run off the main thread. */
    @JvmStatic
    fun warmAccelDns(context: Context, server: String?) {
        if (server.isNullOrEmpty()) return
        try {
            if (readAccelDns(context, server) == null) {
                resolveHost(server)?.let { writeAccelDns(context, server, it) }
            }
        } catch (_: Exception) {
        }
    }

    private fun resolveHost(server: String): String? {
        return try {
            java.net.InetAddress.getByName(server).hostAddress
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve SOCKS server '$server', using as-is", e)
            server
        }
    }

    private fun accelDnsFile(context: Context) = File(context.filesDir, "accel_dns.json")

    private fun readAccelDns(context: Context, host: String): String? {
        return try {
            val f = accelDnsFile(context)
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            if (o.optString("host") != host) return null
            val age = System.currentTimeMillis() - o.optLong("time", 0L)
            if (age < 0 || age > ACCEL_DNS_TTL_MS) return null
            o.optString("ip").ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAccelDns(context: Context, host: String, ip: String) {
        try {
            accelDnsFile(context).writeText(
                JSONObject()
                    .put("host", host)
                    .put("ip", ip)
                    .put("time", System.currentTimeMillis())
                    .toString()
            )
        } catch (_: Exception) {
        }
    }

    /** Persist the last verified exit IP per proxy for optimistic reconnect. */
    @JvmStatic
    fun saveAccelIp(context: Context, key: String, info: IpInfo) {
        try {
            File(context.filesDir, "accel_ip.json").writeText(
                JSONObject()
                    .put("key", key)
                    .put("time", System.currentTimeMillis())
                    .put("ip", info.ip)
                    .put("countryCode", info.countryCode)
                    .put("country", info.country)
                    .put("regionName", info.regionName)
                    .put("city", info.city)
                    .put("isp", info.isp)
                    .put("org", info.org)
                    .put("asName", info.asName)
                    .put("timezone", info.timezone)
                    .toString()
            )
        } catch (_: Exception) {
        }
    }

    /** Cached exit IP for this proxy, or null (miss / other proxy / expired). */
    @JvmStatic
    fun loadAccelIp(context: Context, key: String): IpInfo? {
        return try {
            val f = File(context.filesDir, "accel_ip.json")
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            if (o.optString("key") != key) return null
            val age = System.currentTimeMillis() - o.optLong("time", 0L)
            if (age < 0 || age > ACCEL_IP_TTL_MS) return null
            val ip = o.optString("ip")
            if (ip.isEmpty()) return null
            IpInfo(
                ip = ip,
                countryCode = o.optString("countryCode"),
                country = o.optString("country"),
                regionName = o.optString("regionName"),
                city = o.optString("city"),
                isp = o.optString("isp"),
                org = o.optString("org"),
                asName = o.optString("asName"),
                timezone = o.optString("timezone")
            )
        } catch (_: Exception) {
            null
        }
    }
}
