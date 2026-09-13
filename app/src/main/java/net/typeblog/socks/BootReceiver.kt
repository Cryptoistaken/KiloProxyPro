package net.typeblog.socks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.preference.PreferenceManager
import net.typeblog.socks.util.Constants.PREF_FLOATING_CONTROL
import net.typeblog.socks.util.Profile
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.Utility
import net.typeblog.socks.BuildConfig.DEBUG

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            if (um != null && !um.isUserUnlocked) return
        }
        val action = intent.action ?: return
        val p: Profile = try {
            ProfileManager.getInstance(context.applicationContext).getDefault()
        } catch (_: Exception) {
            return
        }

        // BOOT_COMPLETED: device reboot. MY_PACKAGE_REPLACED: in-app update
        // finished and the system killed the VPN/bubble services — the FGS
        // docs list both as allowed foreground-service start triggers, so
        // restore the same state the app would have had before the update.
        if (p.autoConnect() && VpnService.prepare(context) == null) {
            if (DEBUG) {
                Log.d(TAG, "starting VPN service after $action")
            }

            Utility.startVpn(context, p)
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_FLOATING_CONTROL, false) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context))
        ) {
            if (DEBUG) {
                Log.d(TAG, "starting floating control service after $action")
            }
            FloatingControlService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
