package net.typeblog.socks.ui.screens

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.preference.PreferenceManager
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.ProtonDialogRadioRow
import net.typeblog.socks.ui.components.ProtonSwitch
import net.typeblog.socks.ui.components.SettingsItem
import net.typeblog.socks.util.Constants.ACCEL_MODE_BOTH
import net.typeblog.socks.util.Constants.ACCEL_MODE_SINGLE
import net.typeblog.socks.util.Constants.ACCEL_PRIMARY_KILOIP
import net.typeblog.socks.util.Constants.ACCEL_PRIMARY_TRACE
import net.typeblog.socks.util.Constants.PREF_ACCEL_CACHE_IP
import net.typeblog.socks.util.Constants.PREF_ACCEL_DNS_CACHE
import net.typeblog.socks.util.Constants.PREF_ACCEL_INTERVAL_MS
import net.typeblog.socks.util.Constants.PREF_ACCEL_MODE
import net.typeblog.socks.util.Constants.PREF_ACCEL_PRIMARY
import net.typeblog.socks.util.Constants.PREF_ACCEL_PROBE
import net.typeblog.socks.util.Constants.PREF_HEV_TUNNEL
import net.typeblog.socks.util.Constants.PREF_VPN_ACCELERATOR

private val INTERVAL_OPTIONS = listOf(
    30000L to "30 seconds",
    60000L to "1 minute",
    120000L to "2 minutes"
)

/**
 * Advanced Settings: experimental connect-time options for our SOCKS5
 * client. Same name idea as Proton on purpose, different meaning: no
 * server fleet here, so these only speed up repeat connects, never
 * throughput. The engine honors them only while Accelerator is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var acceleratorEnabled by remember {
        mutableStateOf(prefs.getBoolean(PREF_VPN_ACCELERATOR, false))
    }
    var primary by remember {
        mutableStateOf(prefs.getString(PREF_ACCEL_PRIMARY, ACCEL_PRIMARY_TRACE) ?: ACCEL_PRIMARY_TRACE)
    }
    var mode by remember {
        mutableStateOf(prefs.getString(PREF_ACCEL_MODE, ACCEL_MODE_BOTH) ?: ACCEL_MODE_BOTH)
    }
    var cacheIp by remember {
        mutableStateOf(prefs.getBoolean(PREF_ACCEL_CACHE_IP, false))
    }
    var probe by remember {
        mutableStateOf(prefs.getBoolean(PREF_ACCEL_PROBE, true))
    }
    var intervalMs by remember {
        mutableStateOf(prefs.getLong(PREF_ACCEL_INTERVAL_MS, 60000L))
    }
    var dnsCache by remember {
        mutableStateOf(prefs.getBoolean(PREF_ACCEL_DNS_CACHE, true))
    }
    var hevTunnel by remember {
        mutableStateOf(prefs.getBoolean(PREF_HEV_TUNNEL, false))
    }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PREF_VPN_ACCELERATOR -> acceleratorEnabled = prefs.getBoolean(PREF_VPN_ACCELERATOR, false)
                PREF_ACCEL_PRIMARY -> primary = prefs.getString(PREF_ACCEL_PRIMARY, ACCEL_PRIMARY_TRACE) ?: ACCEL_PRIMARY_TRACE
                PREF_ACCEL_MODE -> mode = prefs.getString(PREF_ACCEL_MODE, ACCEL_MODE_BOTH) ?: ACCEL_MODE_BOTH
                PREF_ACCEL_CACHE_IP -> cacheIp = prefs.getBoolean(PREF_ACCEL_CACHE_IP, false)
                PREF_ACCEL_PROBE -> probe = prefs.getBoolean(PREF_ACCEL_PROBE, true)
                PREF_ACCEL_INTERVAL_MS -> intervalMs = prefs.getLong(PREF_ACCEL_INTERVAL_MS, 60000L)
                PREF_ACCEL_DNS_CACHE -> dnsCache = prefs.getBoolean(PREF_ACCEL_DNS_CACHE, true)
                PREF_HEV_TUNNEL -> hevTunnel = prefs.getBoolean(PREF_HEV_TUNNEL, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var showPrimaryDialog by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }

    if (showPrimaryDialog) {
        PrimaryDialog(
            primary = primary,
            onSelect = {
                primary = it
                prefs.edit().putString(PREF_ACCEL_PRIMARY, it).apply()
                showPrimaryDialog = false
            },
            onDismiss = { showPrimaryDialog = false }
        )
    }
    if (showModeDialog) {
        ModeChoiceDialog(
            mode = mode,
            onSelect = {
                mode = it
                prefs.edit().putString(PREF_ACCEL_MODE, it).apply()
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false }
        )
    }
    if (showIntervalDialog) {
        IntervalDialog(
            intervalMs = intervalMs,
            onSelect = {
                intervalMs = it
                prefs.edit().putLong(PREF_ACCEL_INTERVAL_MS, it).apply()
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false }
        )
    }

    val primaryLabel = if (primary == ACCEL_PRIMARY_KILOIP) "Kilo IP" else "Trace"
    val modeLabel = if (mode == ACCEL_MODE_SINGLE) "Primary only" else "Both"
    val intervalLabel = INTERVAL_OPTIONS.firstOrNull { it.first == intervalMs }?.second ?: "1 minute"

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Advanced Settings") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.lucide_arrow_left),
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Feature header
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_sliders),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Advanced Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = "Experimental options for faster repeat connects and tunnel engine. Connect options apply when Accelerator is on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Master toggle card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                val onToggle: (Boolean) -> Unit = { newValue ->
                    acceleratorEnabled = newValue
                    prefs.edit().putBoolean(PREF_VPN_ACCELERATOR, newValue).apply()
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(!acceleratorEnabled) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Accelerator",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    ProtonSwitch(
                        checked = acceleratorEnabled,
                        onCheckedChange = onToggle
                    )
                }
            }

            SectionTitle(text = "Accelerator")
            SettingsItem(
                icon = painterResource(R.drawable.lucide_server),
                label = "Primary checker",
                description = primaryLabel,
                showChevron = false,
                onClick = { showPrimaryDialog = true }
            )
            SettingsItem(
                icon = painterResource(R.drawable.lucide_arrows_right_left),
                label = "Checker mode",
                description = modeLabel,
                showChevron = false,
                onClick = { showModeDialog = true }
            )
            SwitchRow(
                iconRes = R.drawable.lucide_eye,
                label = "Cache last IP",
                checked = cacheIp,
                onCheckedChange = {
                    cacheIp = it
                    prefs.edit().putBoolean(PREF_ACCEL_CACHE_IP, it).apply()
                }
            )
            SwitchRow(
                iconRes = R.drawable.lucide_send,
                label = "Proxy health probe",
                checked = probe,
                onCheckedChange = {
                    probe = it
                    prefs.edit().putBoolean(PREF_ACCEL_PROBE, it).apply()
                }
            )

            SectionTitle(text = "Other")
            SettingsItem(
                icon = painterResource(R.drawable.lucide_rotate_cw),
                label = "Recheck interval",
                description = intervalLabel,
                showChevron = false,
                onClick = { showIntervalDialog = true }
            )
            SwitchRow(
                iconRes = R.drawable.ic_proton_filter,
                label = "Cache proxy DNS",
                checked = dnsCache,
                onCheckedChange = {
                    dnsCache = it
                    prefs.edit().putBoolean(PREF_ACCEL_DNS_CACHE, it).apply()
                }
            )

            SectionTitle(text = "Engine")
            SwitchRow(
                iconRes = R.drawable.lucide_settings,
                label = "Fast tunnel (hev)",
                checked = hevTunnel,
                onCheckedChange = {
                    hevTunnel = it
                    prefs.edit().putBoolean(PREF_HEV_TUNNEL, it).apply()
                }
            )
            Text(
                text = "Fast tunnel uses the hev engine. DNS is resolved directly, so it works even when the proxy has no UDP support. UDP traffic rides the proxy TCP connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SwitchRow(
    iconRes: Int,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsItem(
        icon = painterResource(iconRes),
        label = label,
        description = if (checked) "On" else "Off",
        trailing = {
            ProtonSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        onClick = { onCheckedChange(!checked) }
    )
}

@Composable
private fun PrimaryDialog(
    primary: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Primary checker",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                ProtonDialogRadioRow(
                    title = "Trace",
                    description = "Fast IP and country lookup for a quick connect display.",
                    selected = primary == ACCEL_PRIMARY_TRACE,
                    onClick = { onSelect(ACCEL_PRIMARY_TRACE) }
                )
                DialogHairline()
                ProtonDialogRadioRow(
                    title = "Kilo IP",
                    description = "Full location and network details for the status display.",
                    selected = primary == ACCEL_PRIMARY_KILOIP,
                    onClick = { onSelect(ACCEL_PRIMARY_KILOIP) }
                )
            }
        }
    }
}

@Composable
private fun ModeChoiceDialog(
    mode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Checker mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                ProtonDialogRadioRow(
                    title = "Both",
                    description = "Primary checker first, then the other for details or fallback.",
                    selected = mode == ACCEL_MODE_BOTH,
                    onClick = { onSelect(ACCEL_MODE_BOTH) }
                )
                DialogHairline()
                ProtonDialogRadioRow(
                    title = "Primary only",
                    description = "Only the primary checker runs.",
                    selected = mode == ACCEL_MODE_SINGLE,
                    onClick = { onSelect(ACCEL_MODE_SINGLE) }
                )
            }
        }
    }
}

@Composable
private fun IntervalDialog(
    intervalMs: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Recheck interval",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                INTERVAL_OPTIONS.forEachIndexed { index, (value, label) ->
                    if (index > 0) DialogHairline()
                    ProtonDialogRadioRow(
                        title = label,
                        description = "Refresh connection info every ${label.lowercase()}.",
                        selected = intervalMs == value,
                        onClick = { onSelect(value) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogHairline() {
    Surface(
        modifier = Modifier.fillMaxWidth().height(1.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    ) {}
}
