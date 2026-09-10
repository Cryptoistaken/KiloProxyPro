package net.typeblog.socks.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.ConnectionCard
import net.typeblog.socks.ui.components.ConnectionStatusCard
import net.typeblog.socks.ui.components.DataUsageCard
import net.typeblog.socks.ui.viewmodel.VpnViewModel
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.ProxyProviders
import net.typeblog.socks.util.Utility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    modifier: Modifier = Modifier,
    viewModel: VpnViewModel,
    onPickProfileClick: () -> Unit = {},
    onOpenSplitAppsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val isRunning by viewModel.isRunning.collectAsState()
    val currentIp by viewModel.currentIp.collectAsState()
    val countryCode by viewModel.countryCode.collectAsState()
    val country by viewModel.country.collectAsState()
    val city by viewModel.city.collectAsState()
    val region by viewModel.region.collectAsState()
    val isp by viewModel.isp.collectAsState()
    val org by viewModel.org.collectAsState()
    val asName by viewModel.asName.collectAsState()
    val timezone by viewModel.timezone.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileName by viewModel.activeProfileName.collectAsState()
    val connectedSince by viewModel.connectedSince.collectAsState()
    val receivedBytes by viewModel.receivedBytes.collectAsState()
    val sentBytes by viewModel.sentBytes.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isActuallyConnected by viewModel.isConnected.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()

    var selectedProfile by rememberSaveable { mutableStateOf<String?>(null) }

    // Profile picked on the Profiles tab: apply as selection, then consume.
    val pickedProfile by viewModel.pickedProfile.collectAsState()
    LaunchedEffect(pickedProfile) {
        if (pickedProfile != null) {
            selectedProfile = pickedProfile
            viewModel.pickProfile(null)
        }
    }

    var connectStartMs by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(isConnecting) {
        if (isConnecting) {
            if (connectStartMs == 0L) connectStartMs = System.currentTimeMillis()
        } else {
            connectStartMs = 0L
        }
    }
    LaunchedEffect(connectStartMs) {
        if (connectStartMs != 0L) {
            val elapsed = System.currentTimeMillis() - connectStartMs
            val remaining = 20000L - elapsed
            if (remaining > 0) delay(remaining)
            if (connectStartMs != 0L) viewModel.onConnectTimeout()
        }
    }

    LaunchedEffect(errorMessage) {
        val message = errorMessage
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            // A failure ends any in-flight connect request so the button is
            // never left stuck on a disabled "Connecting…" state.
            viewModel.cancelConnect()
        }
    }

    LaunchedEffect(profiles) {
        if (profiles.isNotEmpty() && (selectedProfile == null || selectedProfile !in profiles)) {
            val defaultName = try {
                ProfileManager.getInstance(context).getDefault().getName()
            } catch (_: Exception) {
                null
            }
            selectedProfile = if (defaultName != null && defaultName in profiles) {
                defaultName
            } else {
                profiles.firstOrNull()
            }
        }
    }

    LaunchedEffect(isRunning, activeProfileName) {
        if (isRunning && activeProfileName != null) {
            selectedProfile = activeProfileName
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onVpnPermissionResult(context)
        } else {
            viewModel.cancelConnect()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    // Country code for the card's target profile, using the same derivation as
    // FloatingControlService.onBubbleCountrySelected (username/type/parseCountry).
    val cardCountryCode = remember(selectedProfile, activeProfileName, profiles) {
        val target = selectedProfile ?: activeProfileName ?: profiles.firstOrNull()
        if (target == null) {
            null
        } else {
            try {
                val pm = ProfileManager.getInstance(context)
                val p = pm.getProfile(target) ?: return@remember null
                val username = p.getUsername()
                val type = ProxyProviders.detectType(p.getServer(), username)
                val code = ProxyProviders.parseCountry(username, type)
                if (code.isNullOrBlank()) null else code
            } catch (_: Exception) {
                null
            }
        }
    }

    val serverName = remember(selectedProfile, activeProfileName, profiles) {
        val target = selectedProfile ?: activeProfileName ?: profiles.firstOrNull()
        if (target == null) "" else try {
            val pm = ProfileManager.getInstance(context)
            val p = pm.getProfile(target)
            if (p != null) "${p.getServer()}:${p.getPort()}" else ""
        } catch (_: Exception) {
            ""
        }
    }

    // Show persisted totals when disconnected so the usage card is never blank.
    val displayProfileName = selectedProfile ?: activeProfileName ?: profiles.firstOrNull()
    val persistedUsage by produceState(
        initialValue = Triple(false, receivedBytes, sentBytes),
        displayProfileName, receivedBytes, sentBytes, isActuallyConnected
    ) {
        if (isActuallyConnected) {
            value = Triple(false, receivedBytes, sentBytes)
        } else {
            var rx = receivedBytes
            var tx = sentBytes
            if (rx <= 0L && tx <= 0L) {
                val loaded = withContext(Dispatchers.IO) {
                    try {
                        val pm = ProfileManager.getInstance(context)
                        val p = displayProfileName?.let { pm.getProfile(it) }
                        if (p != null) {
                            val suffix = displayProfileName?.let { Utility.usageSuffix(it) } ?: ""
                            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                            Pair(
                                prefs.getLong("usage_rx_${displayProfileName}_$suffix", 0L),
                                prefs.getLong("usage_tx_${displayProfileName}_$suffix", 0L)
                            )
                        } else Pair(0L, 0L)
                    } catch (_: Exception) { Pair(0L, 0L) }
                }
                rx = loaded.first
                tx = loaded.second
            }
            value = Triple(true, rx, tx)
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
        val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val logoSrc = if (isDarkTheme) R.drawable.logo_dark else R.drawable.logo_light

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = logoSrc),
                contentDescription = "KiloProxy Pro",
                modifier = Modifier.height(28.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "KiloProxy Pro",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No proxy set up yet",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Add a proxy on the Profiles tab to get started",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Tapping opens the Profiles tab for selection; the picked
            // profile comes back through viewModel.pickedProfile.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Profile, ${selectedProfile ?: "none selected"}"
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !isRunning,
                        role = Role.Button,
                        onClickLabel = "Select profile",
                        onClick = onPickProfileClick
                    )
            ) {
                OutlinedTextField(
                    value = selectedProfile ?: "",
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("Profile") },
                    shape = RoundedCornerShape(10.dp),
                    // Disabled fields are greyed out by default; the tap
                    // target is the parent Box, so keep full-contrast colors.
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            ConnectionCard(
                isConnected = isActuallyConnected,
                isConnecting = isConnecting,
                serverName = serverName,
                connectedSince = connectedSince,
                onStartClick = {
                    // Include mode with zero apps can never connect: refuse
                    // and point at the apps list instead of failing later.
                    if (viewModel.isSplitIncludeEmpty(context)) {
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Select at least one app to connect",
                                actionLabel = "Select apps"
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                onOpenSplitAppsClick()
                            }
                        }
                        return@ConnectionCard
                    }
                    val targetProfile = selectedProfile ?: activeProfileName ?: profiles.firstOrNull()
                    if (targetProfile != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        viewModel.clearError()
                        val intent = viewModel.prepareAndStartVpn(context, targetProfile)
                        if (intent != null) {
                            vpnPermissionLauncher.launch(intent)
                        }
                    }
                },
                onStopClick = {
                    viewModel.stopVpn(context)
                },
                modifier = Modifier.fillMaxWidth(),
                countryCode = cardCountryCode
            )

            errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            DataUsageCard(
                receivedBytes = persistedUsage.second,
                sentBytes = persistedUsage.third,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            ConnectionStatusCard(
                isConnected = isActuallyConnected,
                ip = currentIp,
                countryCode = countryCode,
                country = country,
                city = city,
                region = region,
                isp = isp,
                org = org,
                asName = asName,
                timezone = timezone,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}
