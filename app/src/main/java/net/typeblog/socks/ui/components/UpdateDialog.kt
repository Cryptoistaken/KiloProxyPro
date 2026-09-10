package net.typeblog.socks.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.R
import net.typeblog.socks.util.UpdateChecker

/**
 * Update-available dialog shared between the in-app "check updates" flow and the
 * proactive launch-time prompt. One card per state: ask (app icon + capped
 * scrolling release notes + Later/Update), downloading (icon + progress +
 * cancel), done (Install/Cancel). Hands off to the package installer only when
 * the user taps Install. [onDismiss] is called when the user picks
 * "Later"/"Cancel" or after the install hand-off completes.
 */
@Composable
fun UpdateDialog(
    info: UpdateChecker.UpdateInfo,
    onDismiss: () -> Unit,
    dismissLabel: String = "Later"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var pausedUi by remember { mutableStateOf(false) }
    var downloadDone by remember { mutableStateOf(false) }
    // Atomic flags: polled on the IO thread, flipped from button onClicks.
    val cancelFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val pauseFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var permissionPending by remember { mutableStateOf(false) }

    val mbTotal = info.sizeBytes / 1048576.0
    val mbDone = downloadProgress * mbTotal
    // Smooth bar motion like the demo: the bar eases toward progress while
    // the % text stays live.
    val animatedProgress by animateFloatAsState(
        targetValue = downloadProgress,
        label = "downloadBar"
    )

    fun startDownload() {
        val resume = pausedUi
        downloading = true
        pausedUi = false
        downloadDone = false
        if (!resume) downloadProgress = 0f
        cancelFlag.set(false)
        pauseFlag.set(false)
        // onProgress fires per chunk on the IO thread; hop to Main only when
        // the shown integer percent actually moves (one coroutine per point,
        // not per chunk).
        var lastUiPct = -1
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                UpdateChecker.downloadToCache(
                    context, info.apkUrl, info.sizeBytes,
                    onProgress = { progress ->
                        val pct = (progress * 100).toInt()
                        if (pct != lastUiPct) {
                            lastUiPct = pct
                            scope.launch { downloadProgress = progress }
                        }
                    },
                    isCancelled = { cancelFlag.get() },
                    isPaused = { pauseFlag.get() },
                    tag = info.tag
                )
            }
            downloading = false
            when (err) {
                null -> downloadDone = true
                "Paused" -> pausedUi = true
                "Cancelled" -> { }
                else -> {
                    onDismiss()
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun discardPartial() {
        scope.launch(Dispatchers.IO) {
            UpdateChecker.discardCached(context, info.tag)
        }
    }

    fun installNow() {
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                UpdateChecker.installCached(context, info.tag)
            }
            onDismiss()
            if (err != null) {
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    val unknownSourceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (context.packageManager.canRequestPackageInstalls()) {
            installNow()
        } else {
            Toast.makeText(
                context,
                "Please allow 'Install unknown apps' for KiloProxy Pro, then try again",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun launchInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            permissionPending = true
        } else {
            installNow()
        }
    }

    if (permissionPending) {
        AlertDialog(
            onDismissRequest = { permissionPending = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text(text = "Allow installing updates?") },
            text = {
                Text(
                    text = "KiloProxy Pro needs to install the update. " +
                        "You'll be taken to Settings to allow \"Install unknown apps\" for KiloProxy Pro " +
                        "- this is required only once."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    permissionPending = false
                    unknownSourceLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }) {
                    Text(text = "Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { permissionPending = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    // Plain-drawable copy of the launcher PNG: Compose
                    // painterResource cannot render the adaptive-icon XML
                    // that R.mipmap.ic_launcher resolves to on API 26+,
                    // which crashed the dialog as soon as it appeared.
                    painter = painterResource(R.drawable.app_icon),
                    contentDescription = "KiloProxy Pro",
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            downloadDone -> "Download done"
                            downloading -> "Downloading update"
                            pausedUi -> "Download paused"
                            else -> "Update available"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "${info.tag} - ${"%.1f MB".format(mbTotal)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (downloading) {
                    IconButton(onClick = { pauseFlag.set(true) }) {
                        Icon(
                            imageVector = Icons.Filled.Pause,
                            contentDescription = "Pause download"
                        )
                    }
                    IconButton(onClick = { cancelFlag.set(true) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel download"
                        )
                    }
                }
                if (pausedUi) {
                    IconButton(onClick = { startDownload() }) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Resume download"
                        )
                    }
                    IconButton(onClick = { pausedUi = false; discardPartial() }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel download"
                        )
                    }
                }
            }
        },
        text = {
            Column {
                when {
                    downloadDone -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Completed - ${"%.1f MB".format(mbTotal)} ready to install.",
                                maxLines = 2
                            )
                        }
                    }
                    downloading -> {
                        Text(
                            text = "Downloading ${(downloadProgress * 100).toInt()}% - " +
                                "%.1f / %.1f MB".format(mbDone, mbTotal),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Plain fill line (not M3 LinearProgressIndicator: it
                        // always draws a stop-indicator dot at the track end,
                        // which floated alone once the track went transparent).
                        DownloadBar(
                            progress = animatedProgress,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Do not close the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    pausedUi -> {
                        Text(
                            text = "Paused ${(downloadProgress * 100).toInt()}% - " +
                                "%.1f / %.1f MB".format(mbDone, mbTotal),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DownloadBar(
                            progress = animatedProgress,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        Text(
                            text = "Install over the current version. " +
                                "Profiles and app data are preserved."
                        )
                        if (info.body.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = context.getString(R.string.whats_new_dialog_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = info.body,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                downloadDone -> {
                    Button(onClick = { launchInstall() }) {
                        Text(text = "Install", maxLines = 1)
                    }
                }
                !downloading -> {
                    Button(onClick = { startDownload() }) {
                        Text(text = "Update", maxLines = 1)
                    }
                }
            }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = if (downloadDone) "Cancel" else dismissLabel,
                        maxLines = 1
                    )
                }
            }
        }
    )
}

/**
 * Fill-only download bar: a plain fractional line with no track and no end
 * dot, matching the transparent-track look without the M3 stop indicator.
 */
@Composable
private fun DownloadBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
    }
}
