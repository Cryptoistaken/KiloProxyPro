package net.typeblog.socks.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import net.typeblog.socks.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the public GitHub release feed for a newer build and installs the APK.
 * Both [check] and [downloadAndInstall] are synchronous and MUST be called
 * from a background thread.
 */
object UpdateChecker {

    data class UpdateInfo(
        val versionCode: Int,
        val tag: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val body: String
    )

    private const val UPDATE_URL =
        "https://api.github.com/repos/Cryptoistaken/KiloProxyPro/releases/latest"
    private const val USER_AGENT = "KiloProxy-Pro-Updater"
    private const val TIMEOUT_MILLIS = 8000
    private const val DOWNLOAD_CONNECT_TIMEOUT = 30_000
    // A stalled read blocks cancel/pause until it times out (the flags are
    // polled between reads). 60s keeps slow networks alive — a timeout just
    // retries and resumes via Range — while keeping cancel responsive.
    private const val DOWNLOAD_READ_TIMEOUT = 60_000
    private const val MAX_RETRIES = 2
    private const val BUFFER_SIZE = 8192
    private const val TAG = "KiloProxyUpdate"

    fun check(): UpdateInfo? {
        Log.d(TAG, "check() called, installed versionCode = ${BuildConfig.VERSION_CODE}")
        val info = fetchLatestNotes() ?: run {
            Log.d(TAG, "check() -> fetchLatestNotes() returned null")
            return null
        }
        if (info.versionCode <= BuildConfig.VERSION_CODE) {
            Log.d(TAG, "check() -> latest ${info.tag} (code ${info.versionCode}) <= installed, no update")
            return null
        }
        Log.d(TAG, "check() -> update available: ${info.tag} (code ${info.versionCode}), apk = ${info.apkUrl}")
        return info
    }

    /**
     * Fetches the latest release without the version gate — used to display
     * the release notes ("What's new") regardless of the installed version.
     * Synchronous, MUST be called from a background thread.
     */
    fun fetchLatestNotes(): UpdateInfo? {
        var connection: HttpURLConnection? = null
        return try {
            Log.d(TAG, "fetchLatestNotes() -> GET $UPDATE_URL")
            connection = URL(UPDATE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val status = connection.responseCode
            Log.d(TAG, "fetchLatestNotes() -> HTTP $status")
            if (status != HttpURLConnection.HTTP_OK) {
                val err = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                Log.e(TAG, "fetchLatestNotes() -> non-200 response, body: ${err?.take(500)}")
                return null
            }

            val body = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            Log.d(TAG, "fetchLatestNotes() -> body ${body.length} chars, parsing...")
            parseRelease(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "fetchLatestNotes() -> exception: ${e::class.simpleName}: ${e.message}", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): UpdateInfo? {
        // Tags are pushed as v<versionCode> — anything else is not for us.
        val tag = json.optString("tag_name")
        if (!tag.matches(Regex("""^v(\d+)$"""))) {
            Log.w(TAG, "parseRelease() -> tag '$tag' does not match v<versionCode>")
            return null
        }

        val versionCode = tag.drop(1).toIntOrNull() ?: return null

        val assets = json.optJSONArray("assets") ?: run {
            Log.w(TAG, "parseRelease() -> no assets array in release $tag")
            return null
        }
        if (assets.length() == 0) return null

        // Prefer the arm64 build (device ABI), fall back to the first asset.
        val arm64Asset = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").contains("arm64") }
        val asset = arm64Asset ?: assets.getJSONObject(0)
        val apkUrl = asset.optString("browser_download_url")
        if (apkUrl.isEmpty()) return null
        if (!apkUrl.startsWith("https://")) {
            Log.w(TAG, "parseRelease() -> non-https apkUrl rejected: $apkUrl")
            return null
        }

        Log.d(TAG, "parseRelease() -> picked asset '${asset.optString("name")}' (${asset.optLong("size")} bytes) from release $tag")
        return UpdateInfo(
            versionCode = versionCode,
            tag = tag,
            apkUrl = apkUrl,
            sizeBytes = asset.optLong("size"),
            body = sanitizeNotes(json.optString("body"))
        )
    }

    // Release notes follow the app's ASCII-only user-text rule: strip emojis
    // and decorative unicode (bullets, arrows, etc.) so "What's new" renders
    // as plain text with no emoji glyphs.
    fun sanitizeNotes(raw: String): String {
        return raw.lines()
            .map { line -> line.replace(Regex("[^\\x20-\\x7E\\t]"), "").trimEnd() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Cache file for a release tag. Single place for the tag sanitizing so
     * download, install and discard can never disagree on the file name.
     */
    fun cacheFile(context: Context, tag: String): File {
        val safeTag = tag.filter { it.isLetterOrDigit() || it == '.' || it == '-' }
        return if (safeTag.isNotEmpty()) File(context.cacheDir, "update-$safeTag.apk")
        else File(context.cacheDir, "update.apk")
    }

    /** Deletes a cached (possibly partial) download for a release tag. */
    fun discardCached(context: Context, tag: String) {
        try { cacheFile(context, tag).delete() } catch (_: Exception) { }
    }

    /**
     * Downloads the APK to cache and launches the package installer.
     *
     * [totalBytes] is the expected download size (used to compute progress). When
     * provided (> 0), [onProgress] is invoked on the calling thread with a
     * 0f..1f fraction at regular intervals while bytes stream in.
     * Synchronous, MUST be called from a background thread. Returns an error
     * message on failure, or null once the installer has been launched.
     */
    fun downloadAndInstall(
        context: Context,
        url: String,
        totalBytes: Long = 0L,
        onProgress: ((Float) -> Unit)? = null,
        tag: String = ""
    ): String? {
        downloadToCache(context, url, totalBytes, onProgress, isCancelled = { false }, tag = tag)?.let { return it }
        return installCached(context, tag)
    }

    /**
     * Downloads the APK to cache without launching the installer, so the UI
     * can show a separate done screen with Install/Cancel. [isCancelled] and
     * [isPaused] are polled while bytes stream in: cancel aborts and deletes
     * the partial file ("Cancelled"), pause aborts but keeps it ("Paused") so
     * the next call resumes with an HTTP Range request. Both results should
     * be swallowed by the caller (no toast).
     *
     * [tag] keys the cache file (update-&lt;tag&gt;.apk): resuming a partial
     * from another version would stitch two different APKs together - the
     * size check passes but the package installer fails with a parse error.
     * Stale files from other versions are wiped first so a bad file can
     * never trap the flow in a permanent parse-error loop.
     * Synchronous, MUST be called from a background thread.
     */
    fun downloadToCache(
        context: Context,
        url: String,
        totalBytes: Long = 0L,
        onProgress: ((Float) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
        isPaused: (() -> Boolean)? = null,
        tag: String = ""
    ): String? {
        if (!url.startsWith("https://")) return "Update URL must use HTTPS"
        val file = cacheFile(context, tag)
        // Never resume foreign bytes: drop the legacy name and any other
        // version's file before touching this download.
        try {
            context.cacheDir.listFiles { f ->
                f.isFile && f.name.startsWith("update-") && f.name.endsWith(".apk") && f != file
            }?.forEach { try { it.delete() } catch (_: Exception) { } }
            if (file.name != "update.apk") {
                try { File(context.cacheDir, "update.apk").delete() } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                // Never trust a stale file: it may be another version's APK or a
                // foreign partial, which installs as a corrupt package (parse
                // error). Only resume a partial smaller than THIS download, and
                // always verify the final size before reporting success.
                var offset = 0L
                if (totalBytes > 0 && file.exists()) {
                    val len = file.length()
                    when {
                        len == totalBytes -> {
                            onProgress?.invoke(1f)
                            return null
                        }
                        len > 0 && len < totalBytes -> offset = len
                        else -> file.delete()
                    }
                }

                Log.d(TAG, "downloadToCache() -> attempt ${attempt + 1}/$MAX_RETRIES, GET $url (offset=$offset)")
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT
                connection.readTimeout = DOWNLOAD_READ_TIMEOUT
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")

                val status = connection.responseCode
                Log.d(TAG, "downloadToCache() -> HTTP $status")
                val append = offset > 0 && status == HttpURLConnection.HTTP_PARTIAL
                if (offset > 0 && !append) {
                    // Server ignored the Range request: restart from scratch.
                    Log.w(TAG, "downloadToCache() -> Range not honored (HTTP $status), restarting full download")
                    file.delete()
                    offset = 0
                    if (status != HttpURLConnection.HTTP_OK) {
                        val err = "HTTP $status"
                        val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                        Log.e(TAG, "downloadToCache() -> $err, body: ${body?.take(300)}")
                        connection.disconnect()
                        if (attempt < MAX_RETRIES - 1) {
                            Thread.sleep(1000L * (attempt + 1))
                            return@repeat
                        }
                        return "Download failed ($err)"
                    }
                } else if (offset == 0L && status != HttpURLConnection.HTTP_OK) {
                    val err = "HTTP $status"
                    val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                    Log.e(TAG, "downloadToCache() -> $err, body: ${body?.take(300)}")
                    connection.disconnect()
                    if (attempt < MAX_RETRIES - 1) {
                        Thread.sleep(1000L * (attempt + 1))
                        return@repeat
                    }
                    return "Download failed ($err)"
                }

                var downloaded = offset
                if (offset > 0) onProgress?.invoke((offset * 100 / totalBytes.coerceAtLeast(1) / 100f).coerceIn(0f, 1f))
                var lastReportedPct = if (totalBytes > 0) (downloaded * 100 / totalBytes).toInt() else -1
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(file, append).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read = input.read(buffer)
                        while (read != -1) {
                            if (isCancelled?.invoke() == true) {
                                Log.d(TAG, "downloadToCache() -> cancelled by user")
                                try { file.delete() } catch (_: Exception) { }
                                return "Cancelled"
                            }
                            if (isPaused?.invoke() == true) {
                                Log.d(TAG, "downloadToCache() -> paused by user at $downloaded bytes")
                                return "Paused"
                            }
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = (downloaded * 100 / totalBytes).toInt()
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    onProgress?.invoke((pct / 100f).coerceIn(0f, 1f))
                                }
                            }
                            read = input.read(buffer)
                        }
                    }
                }

                Log.d(TAG, "downloadToCache() -> downloaded ${file.length()} bytes to $file")
                if (totalBytes > 0 && file.length() != totalBytes) {
                    // Truncated or overgrown file: never hand it to the
                    // installer (parse error). Discard and retry fresh.
                    Log.w(TAG, "downloadToCache() -> size mismatch (got ${file.length()}, want $totalBytes), discarding")
                    try { file.delete() } catch (_: Exception) { }
                    if (attempt < MAX_RETRIES - 1) {
                        Thread.sleep(1000L * (attempt + 1))
                        return@repeat
                    }
                    return "Download incomplete, please try again"
                }
                return null
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "downloadToCache() -> attempt ${attempt + 1} failed: ${e::class.simpleName}: ${e.message}", e)
                connection?.disconnect()
                // A cancel that landed mid-read or mid-backoff resolves here
                // instead of sleeping through another retry.
                if (isCancelled?.invoke() == true) {
                    try { file.delete() } catch (_: Exception) { }
                    return "Cancelled"
                }
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(1000L * (attempt + 1))
                }
            }
        }
        return lastException?.message ?: "Update failed"
    }

    /** Launches the package installer for the previously downloaded update.apk. */
    fun installCached(context: Context, tag: String = ""): String? {
        val file = cacheFile(context, tag)
        if (!file.exists()) return "Downloaded file is missing"
        // Verify the file is a parseable APK for OUR package before handing
        // it to the installer: a stitched/truncated file would otherwise
        // surface as a system "problem parsing the package" error with no
        // clean retry. Discard it and ask for a fresh download instead.
        val archiveInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }
        } catch (_: Exception) { null }
        if (archiveInfo == null || archiveInfo.packageName != context.packageName) {
            Log.w(TAG, "installCached() -> ${file.name} failed verification, discarding")
            try { file.delete() } catch (_: Exception) { }
            return "Download incomplete, please try again"
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        Log.d(TAG, "installCached() -> launching installer for $file (${file.length()} bytes)")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            Log.e(TAG, "installCached() -> startActivity(installer) threw: ${e::class.simpleName}: ${e.message}", e)
            "Could not open installer: ${e.message}"
        }
    }
}