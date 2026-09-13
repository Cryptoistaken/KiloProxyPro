package net.typeblog.socks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.VpnService
import android.net.VpnService.Builder
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.PowerManager
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import hev.htproxy.TProxyService
import net.typeblog.socks.R
import net.typeblog.socks.util.Constants
import net.typeblog.socks.util.Constants.ACTION_STOP_VPN
import net.typeblog.socks.util.Constants.INTENT_APP_BYPASS
import net.typeblog.socks.util.Constants.INTENT_APP_LIST
import net.typeblog.socks.util.Constants.INTENT_DNS
import net.typeblog.socks.util.Constants.INTENT_DNS_PORT
import net.typeblog.socks.util.Constants.INTENT_IPV6_PROXY
import net.typeblog.socks.util.Constants.INTENT_NAME
import net.typeblog.socks.util.Constants.INTENT_PASSWORD
import net.typeblog.socks.util.Constants.INTENT_PER_APP
import net.typeblog.socks.util.Constants.INTENT_PORT
import net.typeblog.socks.util.Constants.INTENT_ROUTE
import net.typeblog.socks.util.Constants.INTENT_SERVER
import net.typeblog.socks.util.Constants.INTENT_UDP_GW
import net.typeblog.socks.util.Constants.INTENT_USERNAME
import net.typeblog.socks.util.Constants.PREF_AUTO_STOP
import net.typeblog.socks.util.IpInfo
import net.typeblog.socks.util.Routes
import net.typeblog.socks.util.SocksTester
import net.typeblog.socks.util.Utility
import net.typeblog.socks.BuildConfig.DEBUG
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SocksVpnService : VpnService() {
    inner class VpnBinder : IVpnService.Stub() {
        override fun isRunning(): Boolean {
            return mRunning
        }

        override fun stop() {
            Log.d(TAG, "stop() called via AIDL binder")
            stopMe("binder_stop")
        }

        override fun getCurrentIp(): String {
            return mCurrentIp ?: ""
        }

        override fun getCountryCode(): String {
            return mCountryCode ?: ""
        }

        override fun getCountry(): String {
            return mIpInfo?.country ?: ""
        }

        override fun getRegion(): String {
            return mIpInfo?.regionName ?: ""
        }

        override fun getCity(): String {
            return mIpInfo?.city ?: ""
        }

        override fun getIsp(): String {
            return mIpInfo?.isp ?: ""
        }

        override fun getOrg(): String {
            return mIpInfo?.org ?: ""
        }

        override fun getAsName(): String {
            return mIpInfo?.asName ?: ""
        }

        override fun getTimezone(): String {
            return mIpInfo?.timezone ?: ""
        }

        override fun getConnectedSince(): Long {
            return mConnectedSince
        }

        override fun getErrorMessage(): String {
            return mError ?: ""
        }

        override fun getReceivedBytes(): Long {
            return mCumulativeRx + mReceivedBytes
        }

        override fun getSentBytes(): Long {
            return mCumulativeTx + mSentBytes
        }

        override fun getProfileName(): String {
            return mProfileName ?: ""
        }

        override fun isProxyVerified(): Boolean {
            return mProxyVerified
        }

        override fun getState(): Bundle = Bundle().apply {
            putBoolean(Constants.VPN_STATE_RUNNING, mRunning)
            putBoolean(Constants.VPN_STATE_TUNNEL_UP, mTunnelUp)
            putBoolean(Constants.VPN_STATE_VERIFIED, mProxyVerified)
            putBoolean(Constants.VPN_STATE_CONNECTED, mRunning && mTunnelUp && mProxyVerified)
            putLong(Constants.VPN_STATE_CONNECTED_SINCE, mConnectedSince)
            putString(Constants.VPN_STATE_IP, mCurrentIp ?: "")
            putString(Constants.VPN_STATE_COUNTRY_CODE, mCountryCode ?: "")
            putString(Constants.VPN_STATE_COUNTRY, mIpInfo?.country ?: "")
            putString(Constants.VPN_STATE_REGION, mIpInfo?.regionName ?: "")
            putString(Constants.VPN_STATE_CITY, mIpInfo?.city ?: "")
            putString(Constants.VPN_STATE_ISP, mIpInfo?.isp ?: "")
            putString(Constants.VPN_STATE_ORG, mIpInfo?.org ?: "")
            putString(Constants.VPN_STATE_AS_NAME, mIpInfo?.asName ?: "")
            putString(Constants.VPN_STATE_TIMEZONE, mIpInfo?.timezone ?: "")
            putString(Constants.VPN_STATE_ERROR, mError ?: "")
            putLong(Constants.VPN_STATE_RECEIVED, mCumulativeRx + mReceivedBytes)
            putLong(Constants.VPN_STATE_SENT, mCumulativeTx + mSentBytes)
            putString(Constants.VPN_STATE_PROFILE, mProfileName ?: "")
        }
    }

    private var mInterface: ParcelFileDescriptor? = null
    @Volatile
    private var mRunning = false
    @Volatile
    private var mProxyVerified = false
    private val mBinder: IBinder = VpnBinder()
    @Volatile
    private var mProfileName: String? = null
    private var mTun2socksProcess: java.lang.Process? = null
    private var mPdnsdProcess: java.lang.Process? = null
    @Volatile
    private var mDns: String? = null
    @Volatile
    private var mDnsPort: Int = 53
    private var mResolvedServer: String? = null
    private var mServer: String? = null
    private var mPort: Int = 0
    private var mUsername: String? = null
    private var mPassword: String? = null

    @Volatile
    private var mCurrentIp: String? = null
    @Volatile
    private var mCountryCode: String? = null
    @Volatile
    private var mIpInfo: IpInfo? = null
    @Volatile
    private var mConnectedSince: Long = 0L
    @Volatile
    private var mError: String? = null
    private var mIpCheckFailures = 0
    @Volatile
    private var mTunnelUp = false
    private var mPendingIpInfo: IpInfo? = null
    private val mIpCheckHandler = Handler(Looper.getMainLooper())
    private val mMainHandler = Handler(Looper.getMainLooper())
    private val mIpCheckExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ipcheck").apply { isDaemon = true }
    }
    private val mProbeInFlight = AtomicBoolean(false)
    @Volatile
    private var mSendfdCancelled = false
    // Generation counter for connect attempts. Bumped on every start and every
    // stop so a background start thread orphaned by a cancel-while-connecting
    // can detect it is stale and abort instead of resurrecting the tunnel.
    @Volatile
    private var mConnectSeq = 0
    // VPN Accelerator (experimental): read once per connect from prefs.
    // OFF means every path below behaves exactly like before.
    @Volatile
    private var mAccel = false
    private var mAccelKey: String? = null
    private var mAccelPrimary: String = Constants.ACCEL_PRIMARY_TRACE
    private var mAccelBoth = true
    private var mAccelCacheIp = false
    private var mAccelProbe = true
    private var mAccelDns = true
    private var mAccelIntervalMs = 60000L
    private var mHev = false
    private var mHevUdp = true
    @Volatile
    private var mHevActive = false
    private var mNotificationReceiverRegistered = false
    private var mScreenOffRegistered = false
    private var mScreenOnRegistered = false

    // Last notification content actually issued, so the retry loop can skip
    // redundant notify() calls when the visible text didn't change.
    private var mLastNotificationText: String? = null
    private var mLastNotificationActions = -1

    @Volatile
    private var mReceivedBytes = 0L
    @Volatile
    private var mSentBytes = 0L
    @Volatile
    private var mCumulativeRx = 0L
    @Volatile
    private var mCumulativeTx = 0L
    private var mBaseRx = 0L
    private var mBaseTx = 0L

    private fun readUsageBytes(): Pair<Long, Long>? {
        Utility.readTunBytes()?.let { return it }
        val rx = TrafficStats.getUidRxBytes(Process.myUid())
        val tx = TrafficStats.getUidTxBytes(Process.myUid())
        return if (rx >= 0L && tx >= 0L) Pair(rx, tx) else null
    }

    private val mStatsHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var mStatsTick = 0L
    private val mStatsRunnable = object : Runnable {
        override fun run() {
            val usage = readUsageBytes()
            usage?.let { (rx, tx) ->
                mReceivedBytes = (rx - mBaseRx).coerceAtLeast(0L)
                mSentBytes = (tx - mBaseTx).coerceAtLeast(0L)
            }
            mStatsTick++
            // TEMP tunDBG: remove after data-used diagnosis.
            if (mStatsTick % 5L == 0L) {
                Log.d(TAG, "tunDBG tick=$mStatsTick usage=$usage base=($mBaseRx,$mBaseTx) session=($mReceivedBytes,$mSentBytes) total=(${mCumulativeRx + mReceivedBytes},${mCumulativeTx + mSentBytes})")
            }
            if (mRunning) {
                // Persist usage periodically so the profiles page proxy card
                // reflects live data instead of only updating on VPN stop.
                if (mStatsTick % USAGE_PERSIST_TICKS == 0L) {
                    persistProfileBytes()
                }
                mStatsHandler.postDelayed(this, STATS_INTERVAL)
            }
        }
    }
    // TEMP tunDBG helper: remove after data-used diagnosis.
    private fun dumpInterfaces(): String {
        return try {
            val sb = StringBuilder()
            val ifs = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    sb.append(ni.name).append(':').append(addrs.nextElement().hostAddress).append(',')
                }
            }
            val sysfs = java.io.File("/sys/class/net").list()?.joinToString(",") ?: "?"
            "$sb sysfs=[$sysfs]"
        } catch (e: Exception) {
            "dumpFail:${e.message}"
        }
    }
    private val mScreenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "Screen off received, auto-stopping VPN")
                stopMe("screen_off")
            }
        }
    }
    private val mScreenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON && mRunning) {
                Log.d(TAG, "Screen on — re-verifying connectivity")
                mIpCheckFailures = 0
                mIpCheckHandler.removeCallbacks(mIpCheckRunnable)
                mIpCheckHandler.post(mIpCheckRunnable)
            }
        }
    }
    private val mIpCheckRunnable = object : Runnable {
        override fun run() {
            if (!mRunning) return
            // During Doze (or while the screen is off) the network is suspended,
            // so every probe would fail. Skip the check instead of counting these
            // failures toward teardown; the loop re-verifies on wake.
            if (isNetworkCheckBlocked()) {
                mIpCheckHandler.postDelayed(this, DOZE_CHECK_INTERVAL)
                return
            }
            // Use the pre-resolved server IP when available so the ip-api and
            // SOCKS probes avoid a repeated hostname DNS round-trip.
            val server = mResolvedServer ?: mServer
            val port = mPort
            val username = mUsername
            val password = mPassword
            if (!mProbeInFlight.compareAndSet(false, true)) return
            mIpCheckExecutor.execute {
                try {
                    // Master OFF keeps stock selection: kiloip first, trace
                    // fallback. Master ON honors the Advanced Settings page.
                    val info = if (mAccel) {
                        Utility.checkWith(server, port, username, password, mAccelPrimary, mAccelBoth)
                    } else {
                        Utility.checkPublicIp(server, port, username, password)
                    }
                    if (info != null) {
                        runOnMainThread {
                            mProbeInFlight.set(false)
                            if (!mTunnelUp) {
                                mPendingIpInfo = info
                            } else {
                                applyIpInfo(info)
                                mIpCheckHandler.postDelayed(this, healthyInterval())
                            }
                        }
                    } else {
                        // Public-IP lookup failed; this may simply mean the
                        // checker is unreachable. Connected state is already
                        // set at tunnel-up, so this is pure enrichment. Only a real SOCKS handshake failure
                        // counts as a dead proxy and may tear down the VPN.
                        val probe = SocksTester.probeProxy(server, port, username, password)
                        runOnMainThread {
                            mProbeInFlight.set(false)
                            if (!mTunnelUp) {
                                // Tunnel still spawning — quiet fast retry; the
                                // failure counter/probe path is only valid once the
                                // tunnel is actually up.
                                mIpCheckHandler.postDelayed(this, IP_INFO_RETRY)
                                return@runOnMainThread
                            }
                            if (probe == SocksTester.ProxyProbe.OK) {
                                // Proxy itself is healthy — do not count the lookup
                                // failure, do not stop. The checker lookup may
                                // simply be temporarily unreachable, so retry
                                // sooner than the normal cadence so the
                                // country/pill appears quickly.
                                mProxyVerified = true
                                mIpCheckFailures = 0
                                updateNotification()
                                notifyStateChanged()
                                mIpCheckHandler.postDelayed(this, IP_INFO_RETRY)
                            } else if (!mAccel || mAccelProbe) {
                                // SOCKS handshake failed against the address in
                                // use. In accelerated mode that address may come
                                // from the DNS cache; drop it so the next connect
                                // resolves fresh instead of reusing a dead IP.
                                if (mAccel) Utility.clearAccelDns(this)
                                if (mProxyVerified) {
                                    mProxyVerified = false
                                    notifyStateChanged()
                                }
                                mIpCheckFailures++
                                Log.e(TAG, "IP check failed ($mIpCheckFailures/$MAX_IP_CHECK_FAILURES): $probe")
                                if (mIpCheckFailures >= MAX_IP_CHECK_FAILURES) {
                                    mError = when (probe) {
                                        SocksTester.ProxyProbe.AUTH_FAILED ->
                                            "Connection failed: proxy authentication failed. Check your username and password."
                                        SocksTester.ProxyProbe.NOT_SOCKS5 ->
                                            "Connection failed: server is not a SOCKS5 proxy."
                                        SocksTester.ProxyProbe.CONNECT_FAILED ->
                                            "Connection failed: proxy refused the connection."
                                        else ->
                                            "Connection failed: proxy unreachable or not responding."
                                    }
                                    Log.e(TAG, "Connectivity never verified — stopping: $mError")
                                    stopMe("proxy_connect_failed")
                                    return@runOnMainThread
                                }
                                mIpCheckHandler.postDelayed(this, IP_CHECK_RETRY)
                            } else {
                                // Probe off: never tear down, only retry enrichment.
                                if (mAccel) Utility.clearAccelDns(this)
                                mIpCheckHandler.postDelayed(this, IP_CHECK_RETRY)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "IP check failed", e)
                    runOnMainThread {
                        mProbeInFlight.set(false)
                        if (!mTunnelUp) {
                            // Same rule as the failure branch: no failure counting
                            // while the tunnel is still spawning.
                            mIpCheckHandler.postDelayed(this, IP_INFO_RETRY)
                        } else {
                            mIpCheckHandler.postDelayed(this, IP_CHECK_RETRY)
                        }
                    }
                }
            }
        }
    }

    private fun isNetworkCheckBlocked(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return !pm.isInteractive ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm.isDeviceIdleMode)
    }

    private val mNotificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP_VPN -> {
                    Log.d(TAG, "Notification stop action received")
                    stopMe("notification_stop")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Control",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null) {
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DEBUG) {
            Log.d(TAG, "starting")
        }

        if (intent == null) {
            stopSelf()
            return START_STICKY
        }

        // Ordered stop request (sent by stopVpn() right after the binder call).
        // Intents are delivered in order, so a stop queued behind a start still
        // lands: without this, cancelling while connecting is lost because the
        // binder stop() early-returns when onStartCommand has not run yet, and
        // the queued start then brings the tunnel up anyway.
        if (intent.action == ACTION_STOP_VPN) {
            Log.d(TAG, "onStartCommand: ordered stop received")
            stopMe("stop_action")
            return START_NOT_STICKY
        }

        if (mRunning) {
            return START_STICKY
        }

        mProfileName = intent.getStringExtra(INTENT_NAME)
        val server = intent.getStringExtra(INTENT_SERVER)
        val port = intent.getIntExtra(INTENT_PORT, 1080)
        val username = intent.getStringExtra(INTENT_USERNAME)
        val passwd = intent.getStringExtra(INTENT_PASSWORD)
        mServer = server
        mPort = port
        mUsername = username
        mPassword = passwd
        val route = intent.getStringExtra(INTENT_ROUTE)
        val dns = intent.getStringExtra(INTENT_DNS)
        val dnsPort = intent.getIntExtra(INTENT_DNS_PORT, 53)
        mDns = dns
        mDnsPort = dnsPort
        val accelPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        mAccel = accelPrefs.getBoolean(Constants.PREF_VPN_ACCELERATOR, false)
        mAccelKey = if (mAccel) Utility.accelKey(server, port, username) else null
        mAccelPrimary = accelPrefs.getString(Constants.PREF_ACCEL_PRIMARY, Constants.ACCEL_PRIMARY_TRACE)
            ?: Constants.ACCEL_PRIMARY_TRACE
        mAccelBoth = accelPrefs.getString(Constants.PREF_ACCEL_MODE, Constants.ACCEL_MODE_BOTH) != Constants.ACCEL_MODE_SINGLE
        mAccelCacheIp = accelPrefs.getBoolean(Constants.PREF_ACCEL_CACHE_IP, false)
        mAccelProbe = accelPrefs.getBoolean(Constants.PREF_ACCEL_PROBE, true)
        mAccelDns = accelPrefs.getBoolean(Constants.PREF_ACCEL_DNS_CACHE, true)
        mAccelIntervalMs = accelPrefs.getLong(Constants.PREF_ACCEL_INTERVAL_MS, 60000L)
        mHev = accelPrefs.getBoolean(Constants.PREF_HEV_TUNNEL, false)
        mHevUdp = accelPrefs.getBoolean(Constants.PREF_HEV_UDP, true)
        val perApp = intent.getBooleanExtra(INTENT_PER_APP, false)
        val appBypass = intent.getBooleanExtra(INTENT_APP_BYPASS, false)
        val appList = intent.getStringArrayExtra(INTENT_APP_LIST)
        val ipv6 = intent.getBooleanExtra(INTENT_IPV6_PROXY, false)
        val udpgw = intent.getStringExtra(INTENT_UDP_GW)

        Log.d(TAG, "onStartCommand: profile=$mProfileName server=$server:$port user=$username route=$route dns=$dns:$dnsPort perApp=$perApp ipv6=$ipv6 udpgw=$udpgw hev=$mHev hevUdp=$mHevUdp")

        createNotificationChannel()

        showNotification()
        mRunning = true
        // New connect generation: any background start thread from a previous
        // attempt is now stale and must abort at its next checkpoint.
        mConnectSeq++
        val connectSeq = mConnectSeq

            // Register notification action receiver
        registerReceiverCompat(mNotificationActionReceiver, IntentFilter(ACTION_STOP_VPN))
        mNotificationReceiverRegistered = true

        configure(mProfileName, route, perApp, appBypass, appList, ipv6)

        if (DEBUG)
            Log.d(TAG, "fd: ${mInterface?.fd}")

        if (mInterface != null) {
            Log.d(TAG, "mInterface is non-null with fd=${mInterface!!.fd}, calling start()")
            start(mInterface!!.fd, server, port, username, passwd, dns, dnsPort, ipv6, udpgw, connectSeq)
        } else {
            Log.e(TAG, "mInterface is NULL after configure() — VPN establish() returned null!")
            stopMe("interface_null")
        }

        // NOTE (FIX #3): The mRunning-dependent post-start steps (stats runnable,
        // ip-check runnable, screen-off receiver, counter resets) now run inside
        // start()'s background completion, posted back to the main thread by
        // postStartOnMain() after the tunnel is up. Do not re-add a synchronous
        // `if (mRunning)` block here — start() returns before the tunnel is up.

        return START_STICKY
    }

    override fun onRevoke() {
        Log.d(TAG, "onRevoke called - VPN permission revoked")
        super.onRevoke()
        stopMe("vpn_revoked")
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == VpnService.SERVICE_INTERFACE) {
            return super.onBind(intent)
        }
        if (android.os.Binder.getCallingUid() == Process.myUid()) {
            return mBinder
        }
        Log.w(TAG, "Unauthorized bind attempt from UID ${android.os.Binder.getCallingUid()}")
        return null
    }

    /**
     * Registers a [BroadcastReceiver] safely across all API levels.
     * On Android 13+ (API 33) the receiver MUST be flagged
     * RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED or the system throws a
     * SecurityException on non-system broadcasts. On API 21-25 only the
     * older 4-arg overload exists. These are private in-app receivers, so
     * we use RECEIVER_NOT_EXPORTED where required.
     */
    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter, null, null)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        super.onDestroy()
        stopMe("on_destroy")
    }

    private fun stopMe(reason: String = "") {
        var stateChanged = false
        synchronized(this) {
            if (!mRunning && reason != "on_destroy") return
            if (mRunning) {
                mRunning = false
                stateChanged = true
            }
        }
        Log.d(TAG, "stopMe called" + if (reason.isNotEmpty()) " - reason: $reason" else "")
        if (reason.isEmpty()) {
            // Log stack trace when no reason is given to identify caller
            Log.d(TAG, "stopMe stack trace:", Throwable("stopMe caller trace"))
        }
        mSendfdCancelled = true
        // Invalidate any in-flight background start thread so a cancel that
        // lands mid-connect cannot be resurrected by the orphaned thread.
        mConnectSeq++
        mProbeInFlight.set(false)
        if (stateChanged) notifyStateChanged(mError)
        persistProfileBytes()
        mStatsHandler.removeCallbacks(mStatsRunnable)
        mIpCheckHandler.removeCallbacks(mIpCheckRunnable)
        if (Build.VERSION.SDK_INT >= 34) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }

        val dir = filesDir.absolutePath

        // Kill tun2socks: destroy Process handle or fall back to pid file
        mTun2socksProcess?.let { p ->
            try {
                p.destroy()
                Log.d(TAG, "tun2socks process destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying tun2socks process: ${e.message}")
            }
            mTun2socksProcess = null
        }
        Utility.killPidFile("$dir/tun2socks.pid")
        Utility.killPidFile("$dir/pdnsd.pid")

        // Kill pdnsd Process (launched non-blocking) if we hold a reference.
        mPdnsdProcess?.let { p ->
            try {
                p.destroy()
                Log.d(TAG, "pdnsd process destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying pdnsd process: ${e.message}")
            }
            mPdnsdProcess = null
        }

        // Stop the hev native tunnel when this session requested it. Checked
        // against mHev as well as mHevActive: a stop landing between the
        // native start and the active flag would otherwise leak a running
        // tunnel whose next start always returns false. The native stop is
        // safe when idle, so stopping twice is harmless.
        if (mHev || mHevActive) {
            try {
                TProxyService.TProxyStopService()
                Log.d(TAG, "hev tunnel stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping hev tunnel: ${e.message}")
            } catch (_: UnsatisfiedLinkError) {
                Log.e(TAG, "hev native library missing at stop")
            }
            mHevActive = false
        }

        try {
            mInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
        }

        mProfileName = null
        mServer = null
        mResolvedServer = null
        mAccelKey = null
        mPort = 0
        mUsername = null
        mPassword = null
        mDns = null
        mDnsPort = 53
        mHev = false
        mHevUdp = true
        mCurrentIp = null
        mCountryCode = null
        mIpInfo = null
        mConnectedSince = 0L
        mRunning = false
        mProxyVerified = false
        mError = null

        mCumulativeRx = 0L
        mCumulativeTx = 0L
        mReceivedBytes = 0L
        mSentBytes = 0L
        mBaseRx = 0L
        mBaseTx = 0L

        mIpCheckHandler.removeCallbacks(mIpCheckRunnable)
        mTunnelUp = false
        mPendingIpInfo = null
        mStatsHandler.removeCallbacks(mStatsRunnable)

        try {
            if (mNotificationReceiverRegistered) {
                unregisterReceiver(mNotificationActionReceiver)
                mNotificationReceiverRegistered = false
            }
        } catch (_: Exception) { }

        try {
            if (mScreenOffRegistered) {
                unregisterReceiver(mScreenOffReceiver)
                mScreenOffRegistered = false
            }
        } catch (_: Exception) { }

        try {
            if (mScreenOnRegistered) {
                unregisterReceiver(mScreenOnReceiver)
                mScreenOnRegistered = false
            }
        } catch (_: Exception) { }

        stopSelf()
    }

    private fun usageKeySuffix(): String = Utility.usageSuffix(mProfileName ?: "")

    private fun loadProfileBytes(profileName: String?) {
        val name = profileName ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val suffix = usageKeySuffix()
        mCumulativeRx = prefs.getLong("usage_rx_${name}_$suffix", 0L)
        mCumulativeTx = prefs.getLong("usage_tx_${name}_$suffix", 0L)
    }

    private fun persistProfileBytes() {
        val name = mProfileName ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val suffix = usageKeySuffix()
        val totalRx = mCumulativeRx + mReceivedBytes
        val totalTx = mCumulativeTx + mSentBytes
        prefs.edit()
            .putLong("usage_rx_${name}_$suffix", totalRx)
            .putLong("usage_tx_${name}_$suffix", totalTx)
            .commit()
        Log.d(TAG, "Persisted usage for ${name}_$suffix: rx=$totalRx tx=$totalTx")
    }

    private fun showNotification() {
        // Posted at service start when the tunnel is not up yet: claim
        // Connecting, not Connected. updateNotification() flips the text
        // once the IP is known.
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notify_title))
            .setContentText("Connecting")
            .setSmallIcon(R.drawable.ic_notification_transparent)
            // Plain-drawable launcher copy: R.mipmap.ic_launcher resolves to the
            // adaptive-icon XML on API 26+, which BitmapFactory cannot decode
            // (returns null), leaving a stale or missing large icon.
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.app_icon))
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!mRunning) return

        val notificationText = if (!mCurrentIp.isNullOrEmpty()) {
            getString(R.string.notify_msg, mProfileName ?: "")
        } else {
            "Connecting"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_notification_transparent)
            // Plain-drawable launcher copy: R.mipmap.ic_launcher resolves to the
            // adaptive-icon XML on API 26+, which BitmapFactory cannot decode
            // (returns null), leaving a stale or missing large icon.
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.app_icon))
            .setOngoing(true)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // Skip redundant re-issues when the visible content+actions haven't changed
        // (the healthy-proxy / ip-api-fail retry path can otherwise fire this ~2x/sec).
        val textNow = notificationText
        if (textNow == mLastNotificationText && ((notification.actions?.size ?: 0) == mLastNotificationActions)) {
            return
        }
        mLastNotificationText = textNow
        mLastNotificationActions = notification.actions?.size ?: 0
        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * DNS server used by the hev path's route carve-out. The stock engine
     * forwards DNS to the profile's configured server through pdnsd, so the
     * hev path must use the same address for parity: a network that blocks
     * 8.8.8.8 but allows the provider DNS keeps resolving. Falls back to
     * 8.8.8.8 when the profile value is not a numeric address (addDnsServer
     * requires one) or uses a non-standard port.
     */
    private fun hevDnsServer(): String {
        val configured = mDns?.trim().orEmpty()
        if (configured.isNotEmpty() && mDnsPort == 53 && isNumericAddress(configured)) return configured
        return HEV_DNS_SERVER
    }

    private fun isNumericAddress(value: String): Boolean {
        if (value.isEmpty()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.net.InetAddresses.isNumericAddress(value)
            } else {
                value.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) ||
                    (value.contains(':') && value.all { it.isDigit() || it in "abcdefABCDEF:." })
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun configure(name: String?, route: String?, perApp: Boolean, bypass: Boolean, apps: Array<String>?, ipv6: Boolean) {
        val b = Builder()
        // hev carve-out needs the profile DNS (stock pdnsd forwards there);
        // the stock engine keeps its fixed 8.8.8.8 interception.
        val dnsServer = if (mHev) hevDnsServer() else HEV_DNS_SERVER
        b.setMtu(1500)
            .setSession(name ?: "KiloProxy Pro")
            .addAddress("10.10.10.1", 24)
            .addDnsServer(dnsServer)

        if (ipv6) {
            b.addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)
        }

        if (mHev) {
            // hev path: the Builder DNS server stays OUTSIDE the tunnel so
            // plain DNS goes direct to the real resolver. hev would have to
            // relay it over SOCKS UDP, which TCP-only proxies refuse — with
            // the carve-out DNS works everywhere and TCP still rides hev.
            Routes.addRoutes(this, b, route ?: "all", dnsServer)
        } else {
            Routes.addRoutes(this, b, route ?: "all")

            b.addRoute("8.8.8.8", 32)
        }

        if (!perApp) {
            // Exclude the app's own UID from the tunnel. tun2socks and pdnsd run
            // under this UID and must reach the real network to resolve the SOCKS
            // server hostname and connect to the proxy; routing them into the
            // un-served tun would deadlock startup (getaddrinfo black-hole).
            // The proxy IP is still reported to the user because the ip-api.com
            // check is explicitly tunneled through the SOCKS proxy (see checkPublicIp).
            try {
                b.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
            }
        } else {
            if (bypass) {
                // In bypass mode, selected apps bypass the tunnel; the app's own
                // UID must also bypass so tun2socks/pdnsd can reach the proxy.
                try {
                    b.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}", e)
                }
                for (p in apps.orEmpty()) {
                    if (TextUtils.isEmpty(p)) continue
                    try {
                        b.addDisallowedApplication(p.trim { it <= ' ' })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error: ${e.message}", e)
                    }
                }
            } else {
                for (p in apps.orEmpty()) {
                    if (TextUtils.isEmpty(p) || p.trim { it <= ' ' } == packageName) continue
                    try {
                        b.addAllowedApplication(p.trim { it <= ' ' })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error: ${e.message}", e)
                    }
                }
            }
        }

        mInterface = b.establish()
        if (mInterface == null) {
            Log.e(TAG, "VpnService.Builder.establish() returned null")
        } else {
            Log.d(TAG, "VpnService established with fd=${mInterface!!.fd}")
        }
    }

    private fun start(fd: Int, server: String?, port: Int, user: String?, passwd: String?, dns: String?, dnsPort: Int, ipv6: Boolean, udpgw: String?, connectSeq: Int) {
        // configure()/establish() run on the main thread (VpnService.Builder API).
        // Everything blocking below — config write, pdnsd spawn, hostname
        // resolution, tun2socks spawn, sendfd poll — runs on a background thread
        // so startup does not stall the main thread.
        // Clear any stale failure text from a previous attempt before connecting.
        mError = null
        mSendfdCancelled = false
        // Fire the IP check in parallel with the tunnel spawn below, so the exit
        // IP/country is already on its way while pdnsd/tun2socks boot. Results
        // landing before the tunnel is up are buffered (mPendingIpInfo) and shown
        // the moment the tunnel is ready — never before.
        mIpCheckHandler.post(mIpCheckRunnable)
        val libDir = applicationInfo.nativeLibraryDir
        val dir = filesDir.absolutePath
        Thread {
            try {
                // Accelerator + DNS cache option: resolve the SOCKS hostname
                // in parallel with pdnsd bring-up, so the wait is
                // max(conf+pdnsd, dns).
                var accelDnsThread: Thread? = null
                if (mAccel && mAccelDns) {
                    accelDnsThread = Thread {
                        mResolvedServer = Utility.resolveServerHost(this, server, true)
                    }.apply { isDaemon = true; start() }
                }

                // hev path needs no pdnsd: the Builder DNS server is carved
                // out of the tunnel routes (see configure), so plain DNS
                // goes direct to the real resolver even when the proxy has
                // no UDP support. UDP ASSOCIATE is only used for non-DNS
                // UDP when the proxy allows it.
                if (!mHev) {
                    Utility.makePdnsdConf(this, dns ?: "8.8.8.8", dnsPort)

                    // Launch pdnsd non-blocking: no waitFor() (pdnsd.conf sets
                    // daemon=on so it forks into the background). It only needs to be
                    // running by the time the tunnel carries the first DNS query. Keep
                    // the Process reference so stopMe() can destroy it.
                    if (!launchPdnsd(dir, libDir)) {
                        runOnMainThread { stopMe("pdnsd_start_failed") }
                        return@Thread
                    }
                }

                // FIX #5: resolve the SOCKS server hostname once on this background
                // thread and pass the resolved IP to tun2socks so the native binary
                // does NOT perform its own getaddrinfo during bring-up.
                // Accelerator: the parallel thread above already resolved (cache
                // or fresh); just join it. Otherwise keep the original call.
                val serverIp = if (mAccel && mAccelDns) {
                    try {
                        accelDnsThread?.join(15000)
                    } catch (_: Exception) {
                    }
                    mResolvedServer ?: server
                } else {
                    val ip = try {
                        java.net.InetAddress.getByName(server).hostAddress
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resolve SOCKS server '$server', using as-is", e)
                        server
                    }
                    mResolvedServer = ip
                    ip
                }

                // Cancel checkpoint: DNS resolution blocks for seconds. If the
                // user stopped while connecting, abort before spawning tun2socks
                // so the orphaned thread cannot resurrect the tunnel.
                if (connectSeq != mConnectSeq || !mRunning || mSendfdCancelled) return@Thread

                // Experimental hev engine: native tunnel takes the TUN fd
                // directly via JNI — no tun2socks process, no sendfd poll.
                if (mHev) {
                    startHevTunnel(dir, fd, serverIp, port, user, passwd, ipv6, mHevUdp, connectSeq)
                    return@Thread
                }

                // NAT64/DNS64 mobile networks resolve IPv4-only proxy hostnames
                // to an IPv6 (64:ff9b::/96) literal. tun2socks's BAddr parser
                // requires IPv6 in brackets ([addr]:port) or it exits immediately;
                // the Java probes below handle raw IPv6 fine, so only the native
                // command line needs the brackets.
                val socksAddr = if (serverIp != null && serverIp.contains(":")) "[$serverIp]:$port" else "$serverIp:$port"
                val command = mutableListOf(
                    "$libDir/libtun2socks.so",
                    "--netif-ipaddr", "10.10.10.2",
                    "--netif-netmask", "255.255.255.0",
                    "--socks-server-addr", socksAddr,
                    "--tunfd", fd.toString(),
                    "--tunmtu", "1500",
                    "--loglevel", "3"
                )

                if (!user.isNullOrEmpty()) {
                    command.add("--username")
                    command.add(user!!)
                    command.add("--password")
                    command.add(passwd ?: "")
                }

                if (ipv6) {
                    command.add("--netif-ip6addr")
                    command.add("fdfe:dcba:9876::2")
                }

                command.add("--dnsgw")
                command.add("10.10.10.1:8091")

                if (udpgw != null && udpgw.isNotEmpty()) {
                    command.add("--udpgw-remote-server-addr")
                    command.add(udpgw)
                }

                val loggable = command.mapIndexed { i, arg ->
                    if (i > 0 && command[i - 1] == "--password") "***" else arg
                }.joinToString(" ")
                Log.d(TAG, "tun2socks full command: $loggable")

                // Start tun2socks non-blocking (no daemonization). Store Process for later cleanup.
                try {
                    val pb = ProcessBuilder(command)
                    pb.redirectErrorStream(true)
                    val process = pb.start()
                    mTun2socksProcess = process
                    Log.d(TAG, "tun2socks process started with PID awareness")

                    // Cancel checkpoint: stop may have landed while exec'ing.
                    // Destroy the just-spawned process instead of leaking it.
                    if (connectSeq != mConnectSeq || !mRunning || mSendfdCancelled) {
                        try { process.destroy() } catch (_: Exception) { }
                        mTun2socksProcess = null
                        return@Thread
                    }

                    // Consume stdout/stderr on a background thread to prevent buffer deadlock
                    Thread {
                        try {
                            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                            var line = reader.readLine()
                            while (line != null) {
                                Log.d(TAG, "tun2socks: $line")
                                line = reader.readLine()
                            }
                            val exitCode = process.waitFor()
                            Log.d(TAG, "tun2socks process exited with: $exitCode")
                            if (exitCode != 0 && mRunning) {
                                Log.e(TAG, "tun2socks exited unexpectedly with code $exitCode")
                                // Only stop if we haven't already initiated shutdown
                                runOnMainThread { stopMe("tun2socks_exited:$exitCode") }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "tun2socks monitor error: ${e.message}")
                        }
                    }.apply { isDaemon = true }.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start tun2socks process", e)
                    runOnMainThread { stopMe("tun2socks_start_failed:${e.message}") }
                    return@Thread
                }

                // FIX #1: short fixed poll for sendfd instead of a 1s..5s sleep ramp
                // (up to 15s on the main thread). Poll every 50ms up to 100 attempts
                // (~5s cap).
                var attempts = 0
                while (attempts < 100 && !mSendfdCancelled && mRunning && connectSeq == mConnectSeq) {
                    val sendResult = System.sendfd(fd)
                    if (sendResult != -1) {
                        Log.d(TAG, "sendfd succeeded on attempt ${attempts + 1}/100")
                        // FIX #2: connected is now immediate on tunnel-up; the IP check
                        // is posted below as async enrichment.
                        runOnMainThread { if (!mSendfdCancelled && mRunning && connectSeq == mConnectSeq) postStartOnMain() }
                        return@Thread
                    }
                    attempts++
                    Log.d(TAG, "sendfd attempt $attempts/100 returned: $sendResult")
                    try {
                        Thread.sleep(50)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error: ${e.message}", e)
                    }
                }

                if (mSendfdCancelled || !mRunning || connectSeq != mConnectSeq) return@Thread
                Log.e(TAG, "sendfd failed after 100 attempts, stopping VPN")
                runOnMainThread { stopMe("sendfd_failed_100_attempts") }
                return@Thread
            } catch (e: Exception) {
                Log.e(TAG, "Vpn startup failed", e)
                runOnMainThread { stopMe("start_failed:${e.message}") }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Experimental hev-socks5-tunnel bring-up. Writes hev.yml, hands the
     * live TUN fd to the native tunnel via JNI, and marks connected at
     * tunnel-up. Must run on a background thread (JNI start is quick, but
     * resolve already happened above). Honors the connect generation and
     * stop checkpoints like the stock path.
     */
    private fun startHevTunnel(dir: String, fd: Int, serverIp: String?, port: Int, user: String?, passwd: String?, ipv6: Boolean, udpAssociate: Boolean, connectSeq: Int) {
        if (serverIp.isNullOrEmpty()) {
            Log.e(TAG, "hev: no resolved server IP, stopping VPN")
            mError = "Connection failed: could not resolve the proxy server."
            runOnMainThread { stopMe("hev_no_server_ip") }
            return
        }
        val confPath = Utility.makeHevConf(dir, serverIp, port, user, passwd, ipv6, udpAssociate)
        if (connectSeq != mConnectSeq || !mRunning || mSendfdCancelled) return
        val started = try {
            TProxyService.TProxyStartService(confPath, fd)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "hev: native library missing: ${e.message}", e)
            mError = "Connection failed: fast tunnel engine is missing from this build."
            runOnMainThread { stopMe("hev_lib_missing") }
            return
        } catch (e: Exception) {
            Log.e(TAG, "hev: TProxyStartService threw: ${e.message}", e)
            mError = "Connection failed: fast tunnel could not start."
            runOnMainThread { stopMe("hev_start_failed:${e.message}") }
            return
        }
        // Claim active immediately: a stop landing here must still stop the
        // native tunnel (stopMe stops when mHev or mHevActive is set).
        mHevActive = true
        if (!started) {
            Log.e(TAG, "hev: TProxyStartService returned false")
            mError = "Connection failed: fast tunnel is already running or would not start. Restart the app and try again."
            runOnMainThread { stopMe("hev_start_false") }
            return
        }

        // TProxyStartService only spawns the native worker and returns
        // immediately; a config/tunnel init failure surfaces asynchronously
        // by flipping TProxyIsRunning() back to false. Give the worker a
        // moment so a dead tunnel is reported instead of a fake Connected
        // state with no traffic.
        try {
            Thread.sleep(HEV_START_VERIFY_MS)
        } catch (_: InterruptedException) {
        }
        val alive = try {
            TProxyService.TProxyIsRunning()
        } catch (_: Exception) {
            false
        }
        if (connectSeq != mConnectSeq || !mRunning || mSendfdCancelled) {
            try { TProxyService.TProxyStopService() } catch (_: Exception) { }
            mHevActive = false
            return
        }
        if (!alive) {
            Log.e(TAG, "hev: native tunnel exited right after start (see hev.log)")
            mError = "Connection failed: fast tunnel could not start. Check the proxy server and try again."
            try { TProxyService.TProxyStopService() } catch (_: Exception) { }
            mHevActive = false
            runOnMainThread { stopMe("hev_start_died") }
            return
        }
        Log.d(TAG, "hev: tunnel running, marking connected at tunnel-up")
        runOnMainThread { if (!mSendfdCancelled && mRunning && connectSeq == mConnectSeq) postStartOnMain() }
    }

    private fun consumeProcessOutput(process: java.lang.Process?) {
        process ?: return
        Thread {
            try {
                java.io.BufferedReader(java.io.InputStreamReader(process.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotEmpty()) Log.d(TAG, "pdnsd: $line")
                        line = reader.readLine()
                    }
                }
                try { process.waitFor() } catch (_: Exception) {}
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    private fun launchPdnsd(dir: String, libDir: String): Boolean {
        return try {
            val pdnsdPb = ProcessBuilder(
                "$libDir/libpdnsd.so",
                "-c",
                "$dir/pdnsd.conf"
            )
            pdnsdPb.redirectErrorStream(true)
            val pdnsd = pdnsdPb.start()
            mPdnsdProcess = pdnsd
            consumeProcessOutput(pdnsd)
            Log.d(TAG, "pdnsd started non-blocking")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pdnsd process", e)
            false
        }
    }

    private fun applyIpInfo(info: IpInfo) {
        applyIpInfo(info, fromCache = false)
    }

    private fun applyIpInfo(info: IpInfo, fromCache: Boolean) {
        mCurrentIp = info.ip
        mCountryCode = info.countryCode
        mIpInfo = info
        mProxyVerified = true
        mIpCheckFailures = 0
        // Cache-last-IP option: persist network-verified results only; the
        // optimistic pass below must not refresh a stale entry timestamp.
        if (mAccel && mAccelCacheIp && !fromCache) {
            mAccelKey?.let { Utility.saveAccelIp(this, it, info) }
        }
        updateNotification()
        notifyStateChanged()
    }

    private fun notifyStateChanged(error: String? = null) {
        sendBroadcast(Intent(Constants.ACTION_VPN_STATE_CHANGED).apply {
            setPackage(packageName)
            error?.let { putExtra(Constants.VPN_STATE_ERROR, it) }
        })
    }

    /** Healthy re-verify cadence: stock 60s, or the Advanced Settings pick. */
    private fun healthyInterval(): Long = if (mAccel) mAccelIntervalMs else IP_CHECK_INTERVAL

    private fun postStartOnMain() {
        if (!mRunning) return
        // FIX #2: connected is marked at tunnel-up, not after the HTTP IP check.
        mConnectedSince = java.lang.System.currentTimeMillis()
        mError = null
        mProxyVerified = false
        mIpCheckFailures = 0
        loadProfileBytes(mProfileName)
        mReceivedBytes = 0L
        mSentBytes = 0L
        val initialUsageBytes = readUsageBytes()
        mBaseRx = initialUsageBytes?.first ?: 0L
        mBaseTx = initialUsageBytes?.second ?: 0L
        // TEMP tunDBG: remove after data-used diagnosis.
        Log.d(TAG, "tunDBG start initial=$initialUsageBytes base=($mBaseRx,$mBaseTx) cumulative=($mCumulativeRx,$mCumulativeTx) profile=$mProfileName")
        Log.d(TAG, "tunDBG ifaces=" + dumpInterfaces())
        mStatsHandler.post(mStatsRunnable)
        mTunnelUp = true
        if (mAccel && mAccelCacheIp && !mProxyVerified) {
            val key = mAccelKey
            val cached = if (key != null) Utility.loadAccelIp(this, key) else null
            if (cached != null) {
                // Optimistic CONNECTED at tunnel-up from the last verified
                // exit IP. The live check below still runs and overwrites
                // with a fresh result, so stale geo self-corrects.
                Log.d(TAG, "Accelerator: showing cached exit IP ${cached.ip}")
                applyIpInfo(cached, fromCache = true)
            }
        }
        val buffered = mPendingIpInfo
        mPendingIpInfo = null
        if (buffered != null) {
            // The parallel IP check already answered while the tunnel was
            // spawning — surface it now, exactly as if it had just been
            // received on a normally-connected tunnel.
            applyIpInfo(buffered)
            mIpCheckHandler.postDelayed(mIpCheckRunnable, healthyInterval())
        } else {
            mIpCheckHandler.post(mIpCheckRunnable)
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(PREF_AUTO_STOP, false) && !mScreenOffRegistered) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            registerReceiverCompat(mScreenOffReceiver, filter)
            mScreenOffRegistered = true
        }
        if (!mScreenOnRegistered) {
            registerReceiverCompat(mScreenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            mScreenOnRegistered = true
        }
        notifyStateChanged()
    }

    private fun runOnMainThread(action: () -> Unit) {
        mMainHandler.post(action)
    }

    companion object {
        private const val TAG = "SocksVpnService"
        private const val CHANNEL_ID = "floating_control"
        private const val NOTIFICATION_ID = 2
        private const val IP_CHECK_INTERVAL = 60000L
        private const val IP_INFO_RETRY = 500L
        private const val IP_CHECK_RETRY = 5000L
        private const val MAX_IP_CHECK_FAILURES = 3
        private const val DOZE_CHECK_INTERVAL = 60000L
        private const val STATS_INTERVAL = 1000L
        private const val USAGE_PERSIST_TICKS = 5L
        // Builder DNS for both engines. On the hev path this same address
        // is carved out of the tunnel routes (see configure) so DNS goes
        // direct instead of dying on SOCKS UDP at TCP-only proxies.
        private const val HEV_DNS_SERVER = "8.8.8.8"
        // Time allowed for the async hev worker to prove it is alive.
        private const val HEV_START_VERIFY_MS = 400L
    }
}
