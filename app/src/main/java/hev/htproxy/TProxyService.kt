package hev.htproxy

/**
 * JNI shim for the vendored hev-socks5-tunnel shared library
 * (app/src/main/jni/hev, MIT, 2.17.1).
 *
 * The native side registers these methods via RegisterNatives in JNI_OnLoad,
 * so the package/class/method names must match hev-jni.c defaults exactly
 * (PKGNAME=hev/htproxy, CLSNAME=TProxyService). ProGuard keeps this class
 * (see proguard-rules.pro). Called by SocksVpnService when the Fast tunnel
 * (PREF_HEV_TUNNEL) toggle is on.
 */
object TProxyService {
    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
