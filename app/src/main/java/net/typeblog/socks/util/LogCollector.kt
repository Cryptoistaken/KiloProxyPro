package net.typeblog.socks.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogCollector {

    // Hard cap so a huge log buffer can never OOM the app or
    // produce an unshareable file. Keeps the newest tail.
    private const val MAX_LOG_CHARS = 200_000
    private const val CACHE_FILE = "debug_logs_cache.txt"
    private const val HEV_LOG_FILE = "hev.log"
    private const val HEV_LOG_TAIL_CHARS = 60_000

    fun collectLogs(context: Context): String {
        val header = buildString {
            appendLine("=== KiloProxy Pro Debug Logs ===")
            appendLine("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Package: ${context.packageName}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("============================")
            appendLine()
        }

        // The VPN engine (SocksVpnService) runs in the ":vpn" process, so
        // capture every process of this package — not just the UI process.
        val pids = appProcessPids(context)
        val output = buildString {
            for ((i, pid) in pids.withIndex()) {
                if (i > 0) appendLine("--- process $pid ---")
                append(runLogcat(pid))
            }
        }

        // The hev (Fast tunnel) engine logs natively to filesDir/hev.log, not
        // logcat: without this tail, native start/connect failures would be
        // invisible in the shared debug logs.
        val nativeLog = hevLogTail(context)
        val combined = header + output + nativeLog
        val result = combined.takeLast(MAX_LOG_CHARS)
        val cache = File(context.filesDir, CACHE_FILE)
        if (hasRealLogs(output + nativeLog)) {
            try {
                cache.writeText(result)
            } catch (_: Exception) {
            }
            return result
        }
        try {
            if (cache.exists()) {
                val cached = cache.readText()
                if (cached.isNotBlank()) return cached
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun hasRealLogs(output: String): Boolean =
        output.lineSequence().any {
            val t = it.trim()
            t.isNotEmpty() && !t.startsWith("(") && !t.startsWith("--- process")
        }

    /** Tail of the hev native engine log written to filesDir/hev.log. */
    private fun hevLogTail(context: Context): String {
        return try {
            val f = File(context.filesDir, HEV_LOG_FILE)
            if (!f.exists() || f.length() == 0L) return ""
            val tail = f.readText().takeLast(HEV_LOG_TAIL_CHARS)
            "\n--- fast tunnel (hev) native log (tail) ---\n$tail\n"
        } catch (e: Exception) {
            "\n--- fast tunnel (hev) native log unavailable: ${e.message} ---\n"
        }
    }

    private fun appProcessPids(context: Context): List<Int> {
        val mine = android.os.Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val siblings = am.runningAppProcesses
            ?.filter { it.pkgList.any { p -> p == context.packageName } }
            ?.map { it.pid }
            ?.filter { it != mine }
            ?: emptyList()
        return listOf(mine) + siblings
    }

    private fun runLogcat(pid: Int): String {
        return try {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "time", "-t", "2000", "--pid", pid.toString()
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (output.isBlank()) "(logcat exit $exit produced no output for pid $pid)\n"
            else if (exit != 0) "$output\n(logcat exit $exit for pid $pid)\n"
            else output
        } catch (e: Exception) {
            "(logcat failed for pid $pid: ${e.message})\n"
        }
    }

    fun shareLogs(context: Context, logs: String) {
        val file = File(context.cacheDir, "kiloproxy_logs_${System.currentTimeMillis()}.txt")
        file.writeText(logs)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "KiloProxy Pro Debug Logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share logs"))
    }
}
