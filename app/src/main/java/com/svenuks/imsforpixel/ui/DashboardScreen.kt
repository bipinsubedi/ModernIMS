package com.svenuks.imsforpixel.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.svenuks.imsforpixel.AutoApplyService
import com.svenuks.imsforpixel.MainActivity
import com.svenuks.imsforpixel.R
import com.svenuks.imsforpixel.SimConfigStore
import com.svenuks.imsforpixel.SimLiveStatus
import com.svenuks.imsforpixel.ui.theme.StatusAmber
import com.svenuks.imsforpixel.ui.theme.StatusBlue
import com.svenuks.imsforpixel.ui.theme.StatusGreen
import com.svenuks.imsforpixel.ui.theme.StatusRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    status0: SimLiveStatus,
    status1: SimLiveStatus,
    isWifiConnected: Boolean,
    isAuthorized: Boolean,
    needsReapplyAfterBoot: Boolean,
    portInput: String,
    onPortInputChange: (String) -> Unit,
    isApplying: Boolean,
    onConfigureSlot: (Int) -> Unit,
    onApplySlot: (Int) -> Unit,
    onRestoreSlot: (Int) -> Unit,
    onFixApnSlot: (Int) -> Unit,
    onApplyAllEnabled: () -> Unit,
    onRestoreAll: () -> Unit,
    onRefreshStatus: () -> Unit
) {
    val context = LocalContext.current
    var showAdbSheet by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1-line compact Wireless ADB pill (tap opens clean modal sheet)
        item {
            CompactAdbPill(
                isWifiConnected = isWifiConnected,
                isAuthorized = isAuthorized,
                portInput = portInput,
                onClick = { showAdbSheet = true }
            )
        }

        // Reboot Quick Re-Apply banner (only shown after reboot when overrides were active)
        if (needsReapplyAfterBoot) {
            item {
                RebootReapplyBanner(
                    isAuthorized = isAuthorized,
                    isApplying = isApplying,
                    portInput = portInput,
                    onQuickApply = onApplyAllEnabled,
                    onStartWatcherAndOpenDebug = {
                        val port = portInput.toIntOrNull() ?: -1
                        AutoApplyService.startWatcher(context, port)
                        Toast.makeText(context, context.getString(R.string.toast_watcher_started), Toast.LENGTH_SHORT).show()
                        openIntentSafely(
                            context,
                            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        )
                    }
                )
            }
        }

        // Dual-SIM Hero Cards
        item {
            SimHeroCard(
                slotIndex = 0,
                status = status0,
                otherCarrierName = status1.carrierName,
                isApplying = isApplying,
                onConfigure = { onConfigureSlot(0) },
                onApply = { onApplySlot(0) },
                onRestore = { onRestoreSlot(0) },
                onFixApn = { onFixApnSlot(0) }
            )
        }

        item {
            SimHeroCard(
                slotIndex = 1,
                status = status1,
                otherCarrierName = status0.carrierName,
                isApplying = isApplying,
                onConfigure = { onConfigureSlot(1) },
                onApply = { onApplySlot(1) },
                onRestore = { onRestoreSlot(1) },
                onFixApn = { onFixApnSlot(1) }
            )
        }

        // Compact Batch & System Tools Strip
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.dashboard_tools_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionChipTile(
                        label = stringResource(R.string.btn_activate),
                        icon = Icons.Default.PlayArrow,
                        tint = MaterialTheme.colorScheme.primary,
                        enabled = !isApplying,
                        onClick = onApplyAllEnabled
                    )
                    ActionChipTile(
                        label = stringResource(R.string.btn_restore),
                        icon = Icons.Default.Delete,
                        tint = StatusRed,
                        enabled = !isApplying,
                        onClick = onRestoreAll
                    )
                    ActionChipTile(
                        label = stringResource(R.string.btn_refresh_status),
                        icon = Icons.Default.Refresh,
                        tint = MaterialTheme.colorScheme.onSurface,
                        enabled = !isApplying,
                        onClick = onRefreshStatus
                    )
                    ActionChipTile(
                        label = stringResource(R.string.shortcut_sim_settings),
                        icon = Icons.Default.Settings,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = { openIntentSafely(context, Intent("android.settings.WIRELESS_SETTINGS"), Intent(Settings.ACTION_SETTINGS)) }
                    )
                    ActionChipTile(
                        label = stringResource(R.string.shortcut_phone_info),
                        icon = Icons.Default.Phone,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = { openRadioInfo(context) }
                    )
                    ActionChipTile(
                        label = stringResource(R.string.shortcut_dev_options),
                        icon = Icons.Default.Build,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            openIntentSafely(
                                context,
                                Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            )
                        }
                    )
                    ActionChipTile(
                        label = stringResource(R.string.shortcut_wifi_settings),
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = { openIntentSafely(context, Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS)) }
                    )
                }
            }
        }
    }

    if (showAdbSheet) {
        ModalBottomSheet(onDismissRequest = { showAdbSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.adb_sheet_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = if (isWifiConnected) stringResource(R.string.adb_sheet_wifi_ok) else stringResource(R.string.adb_wifi_off),
                    fontSize = 12.sp,
                    color = if (isWifiConnected) StatusGreen else StatusRed
                )
                Text(
                    text = stringResource(R.string.adb_setup_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )

                Button(
                    onClick = {
                        (context as? MainActivity)?.requestNotificationPermissionAndShow()
                        val port = portInput.toIntOrNull() ?: -1
                        AutoApplyService.startWatcher(context, port)
                        openIntentSafely(
                            context,
                            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_open_wireless_debugging))
                }

                OutlinedTextField(
                    value = portInput,
                    onValueChange = onPortInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_adb_port), fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }
}

@Composable
private fun CompactAdbPill(
    isWifiConnected: Boolean,
    isAuthorized: Boolean,
    portInput: String,
    onClick: () -> Unit
) {
    val statusColor = when {
        isAuthorized -> StatusGreen
        portInput.isNotEmpty() -> StatusAmber
        else -> StatusRed
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = statusColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = when {
                        isAuthorized -> stringResource(R.string.adb_banner_ready, portInput)
                        portInput.isNotEmpty() -> stringResource(R.string.adb_banner_unpaired, portInput)
                        !isWifiConnected -> stringResource(R.string.adb_wifi_off)
                        else -> stringResource(R.string.adb_banner_disconnected)
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isAuthorized) stringResource(R.string.adb_pill_manage) else stringResource(R.string.adb_pill_connect),
                color = statusColor,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun RebootReapplyBanner(
    isAuthorized: Boolean,
    isApplying: Boolean,
    portInput: String,
    onQuickApply: () -> Unit,
    onStartWatcherAndOpenDebug: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = StatusAmber.copy(alpha = 0.14f)),
        border = BorderStroke(1.dp, StatusAmber.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.reboot_banner_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Text(
                text = stringResource(R.string.reboot_banner_desc),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isAuthorized && portInput.isNotEmpty()) {
                    Button(
                        onClick = onQuickApply,
                        enabled = !isApplying,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.btn_quick_reapply), fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onStartWatcherAndOpenDebug,
                        enabled = !isApplying,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.btn_auto_apply_watcher), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SimHeroCard(
    slotIndex: Int,
    status: SimLiveStatus,
    otherCarrierName: String,
    isApplying: Boolean,
    onConfigure: () -> Unit,
    onApply: () -> Unit,
    onRestore: () -> Unit,
    onFixApn: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(slotIndex, status.lastUpdatedEpochMs) {
        context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val overrideEnabled = SimConfigStore.loadSimConfig(prefs, slotIndex).overrideEnabled
    val simType = if (status.isEmbedded) "eSIM" else "pSIM"
    val title = if (status.carrierName.isNotBlank()) {
        "${status.carrierName} ($simType · SIM ${slotIndex + 1})"
    } else {
        stringResource(R.string.config_panel_title, slotIndex + 1)
    }

    val transportLabel = when (status.imsTransport) {
        "CROSS_SIM" -> "Backup Calling"
        "IWLAN" -> "VoWiFi"
        "NR" -> "5G VoNR"
        "LTE" -> "VoLTE"
        "NONE" -> "Not Registered"
        else -> status.imsTransport
    }

    val otherLabel = otherCarrierName.ifBlank { "SIM ${if (slotIndex == 0) 2 else 1}" }
    val humanSummary = when {
        status.imsRegistered && status.imsTransport == "CROSS_SIM" ->
            stringResource(R.string.summary_backup_active, otherLabel)
        status.imsRegistered && status.imsTransport == "IWLAN" && status.crossSimEnabled ->
            stringResource(R.string.summary_vowifi_and_backup_ready, otherLabel)
        status.imsRegistered && status.imsTransport == "IWLAN" ->
            stringResource(R.string.summary_vowifi_active)
        !status.configApplied && !overrideEnabled ->
            stringResource(R.string.summary_carrier_native)
        status.imsRegistered ->
            stringResource(R.string.summary_ims_cellular_active, transportLabel)
        else ->
            stringResource(R.string.summary_ims_not_registered)
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (status.configApplied) StatusGreen.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (status.mccMnc.isNotBlank() || status.subId > 0) {
                        Text(
                            text = "MCC/MNC ${status.mccMnc.ifBlank { "-" }} · SubID ${status.subId}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                PillTag(
                    text = if (status.configApplied) stringResource(R.string.badge_config_applied)
                    else if (overrideEnabled) stringResource(R.string.badge_pending_apply)
                    else stringResource(R.string.badge_config_default),
                    color = if (status.configApplied) StatusGreen else if (overrideEnabled) StatusAmber else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PillTag(
                    text = if (status.imsRegistered) "IMS: $transportLabel" else "IMS: Off",
                    color = if (status.imsRegistered) {
                        if (status.imsTransport == "CROSS_SIM") StatusBlue else StatusGreen
                    } else StatusRed
                )
                PillTag(
                    text = if (status.crossSimEnabled) "Backup: ON" else "Backup: OFF",
                    color = if (status.crossSimEnabled) StatusBlue else MaterialTheme.colorScheme.onSurfaceVariant
                )
                PillTag(
                    text = if (status.wfcEnabled) "VoWiFi: ON" else "VoWiFi: OFF",
                    color = if (status.wfcEnabled) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (status.imsRegistered) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (status.imsRegistered) StatusGreen else StatusAmber,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = humanSummary,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (status.apnConflictDetail.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StatusAmber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, StatusAmber.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(stringResource(R.string.apn_conflict_title), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(
                                    text = stringResource(R.string.apn_conflict_desc, status.apnConflictDetail),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(onClick = onFixApn, enabled = !isApplying) {
                            Text(stringResource(R.string.btn_fix_apn), fontSize = 11.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onConfigure,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.btn_configure), fontSize = 12.sp, maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onApply,
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.btn_apply_short), fontSize = 12.sp, maxLines = 1)
                }
                OutlinedIconButton(
                    onClick = onRestore,
                    enabled = !isApplying,
                    border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.45f)),
                    colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = StatusRed)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.btn_restore_short), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionChipTile(
    label: String,
    icon: ImageVector,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.55f else 0.25f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = tint, maxLines = 1)
        }
    }
}

@Composable
fun PillTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun openIntentSafely(context: Context, primary: Intent, fallback: Intent) {
    try {
        context.startActivity(primary)
    } catch (_: Exception) {
        try {
            context.startActivity(fallback)
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.toast_developer_options_not_found), Toast.LENGTH_SHORT).show()
        }
    }
}

private fun openRadioInfo(context: Context) {
    val attempts = listOf(
        Intent().setClassName("com.android.phone", "com.android.phone.settings.RadioInfo"),
        Intent().setClassName("com.android.settings", "com.android.settings.RadioInfo"),
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode("*#*#4636#*#*")))
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
    Toast.makeText(context, context.getString(R.string.toast_shortcut_unavailable), Toast.LENGTH_SHORT).show()
}
