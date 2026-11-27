package com.svenuks.imsforpixel.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.svenuks.imsforpixel.MainActivity
import com.svenuks.imsforpixel.R
import com.svenuks.imsforpixel.SimConfigStore
import com.svenuks.imsforpixel.SimLiveStatus
import com.svenuks.imsforpixel.ui.theme.StatusAmber
import com.svenuks.imsforpixel.ui.theme.StatusBlue
import com.svenuks.imsforpixel.ui.theme.StatusGreen
import com.svenuks.imsforpixel.ui.theme.StatusRed

@Composable
fun DashboardScreen(
    status0: SimLiveStatus,
    status1: SimLiveStatus,
    isWifiConnected: Boolean,
    isAuthorized: Boolean,
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AdbConnectionBanner(
                isWifiConnected = isWifiConnected,
                isAuthorized = isAuthorized,
                portInput = portInput,
                onPortInputChange = onPortInputChange
            )
        }

        item {
            SimHeroCard(
                slotIndex = 0,
                status = status0,
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
                isApplying = isApplying,
                onConfigure = { onConfigureSlot(1) },
                onApply = { onApplySlot(1) },
                onRestore = { onRestoreSlot(1) },
                onFixApn = { onFixApnSlot(1) }
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dashboard_global_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = stringResource(R.string.dashboard_global_desc),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onRefreshStatus, enabled = !isApplying) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.btn_refresh_status))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onApplyAllEnabled,
                            enabled = !isApplying,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isApplying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.btn_activate), fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = onRestoreAll,
                            enabled = !isApplying,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                            border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_restore), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.dashboard_shortcuts_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShortcutTile(
                        title = stringResource(R.string.shortcut_sim_settings),
                        icon = Icons.Default.Settings,
                        modifier = Modifier.weight(1f)
                    ) {
                        openIntentSafely(context, Intent("android.settings.WIRELESS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
                    }
                    ShortcutTile(
                        title = stringResource(R.string.shortcut_phone_info),
                        icon = Icons.Default.Phone,
                        modifier = Modifier.weight(1f)
                    ) {
                        openRadioInfo(context)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShortcutTile(
                        title = stringResource(R.string.shortcut_dev_options),
                        icon = Icons.Default.Build,
                        modifier = Modifier.weight(1f)
                    ) {
                        openIntentSafely(
                            context,
                            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        )
                    }
                    ShortcutTile(
                        title = stringResource(R.string.shortcut_wifi_settings),
                        icon = Icons.Default.ArrowForward,
                        modifier = Modifier.weight(1f)
                    ) {
                        openIntentSafely(context, Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                    }
                }
            }
        }
    }
}

@Composable
private fun AdbConnectionBanner(
    isWifiConnected: Boolean,
    isAuthorized: Boolean,
    portInput: String,
    onPortInputChange: (String) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember(isAuthorized) { mutableStateOf(!isAuthorized) }

    val statusColor = when {
        isAuthorized -> StatusGreen
        portInput.isNotEmpty() -> StatusAmber
        else -> StatusRed
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = when {
                                isAuthorized -> stringResource(R.string.adb_banner_ready, portInput)
                                portInput.isNotEmpty() -> stringResource(R.string.adb_banner_unpaired, portInput)
                                else -> stringResource(R.string.adb_banner_disconnected)
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isWifiConnected) stringResource(R.string.adb_wifi_on) else stringResource(R.string.adb_wifi_off),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.adb_setup_hint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            (context as? MainActivity)?.requestNotificationPermissionAndShow()
                            openIntentSafely(
                                context,
                                Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_open_wireless_debugging), fontSize = 12.sp)
                    }

                    OutlinedTextField(
                        value = portInput,
                        onValueChange = onPortInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.label_adb_port), fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SimHeroCard(
    slotIndex: Int,
    status: SimLiveStatus,
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
        "IWLAN" -> "VoWiFi (IWLAN)"
        "NR" -> "5G VoNR"
        "LTE" -> "VoLTE"
        "NONE" -> "Not Registered"
        else -> status.imsTransport
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (status.configApplied) StatusGreen.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
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
                    text = if (status.imsRegistered) "IMS: $transportLabel" else "IMS: Not Registered",
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
                                Text(
                                    text = stringResource(R.string.apn_conflict_title),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onConfigure,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.btn_configure), fontSize = 11.sp, maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onApply,
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.btn_apply_short), fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onRestore,
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                    border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.btn_restore_short), fontSize = 11.sp, maxLines = 1)
                }
            }
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

@Composable
private fun ShortcutTile(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
