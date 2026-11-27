package com.svenuks.imsforpixel.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.svenuks.imsforpixel.CaptivePortalState
import com.svenuks.imsforpixel.R
import com.svenuks.imsforpixel.SimConfigStore
import com.svenuks.imsforpixel.SimLiveStatus
import com.svenuks.imsforpixel.ui.theme.StatusAmber
import com.svenuks.imsforpixel.ui.theme.StatusBlue
import com.svenuks.imsforpixel.ui.theme.StatusGreen
import com.svenuks.imsforpixel.ui.theme.StatusRed

@Composable
fun DiagnosticsScreen(
    status0: SimLiveStatus,
    status1: SimLiveStatus,
    captivePortal: CaptivePortalState,
    portInput: String,
    onPortInputChange: (String) -> Unit,
    isApplying: Boolean,
    recheckSignal: Long,
    onRefreshStatus: () -> Unit,
    onFixApnSlot: (Int) -> Unit,
    onRestartImsSlot: (Int) -> Unit,
    onPersistentVolteEnable: (Int) -> Unit,
    onPersistentVolteRestore: (Int) -> Unit,
    onCaptivePortalWrite: (String, String) -> Unit,
    onCaptivePortalReset: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var bootReminder by remember { mutableStateOf(SimConfigStore.isBootReminderEnabled(prefs)) }
    var autoApplyOnLaunch by remember { mutableStateOf(SimConfigStore.isAutoApplyOnLaunch(prefs)) }
    var autoDisableWifiDebug by remember { mutableStateOf(SimConfigStore.isAutoDisableWifiDebug(prefs)) }
    var lastLog by remember { mutableStateOf(SimConfigStore.getLastExecutionLog(prefs)) }
    var showLog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(recheckSignal) {
        lastLog = SimConfigStore.getLastExecutionLog(prefs)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Backup Calling 4-Point Readiness Card
        item {
            BackupCallingReadinessCard(status0 = status0, status1 = status1)
        }

        // Actions: Refresh + Copy Report + Export/Import JSON
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onRefreshStatus,
                        enabled = !isApplying,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.diag_btn_refresh), fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            val report = SimConfigStore.buildDiagnosticReport(context, status0, status1)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("ModernIMS Diagnostic Report", report))
                            Toast.makeText(context, context.getString(R.string.toast_report_copied), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.diag_btn_copy_report), fontSize = 12.sp)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val json = SimConfigStore.exportConfigJson(prefs)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("ModernIMS Profile JSON", json))
                            Toast.makeText(context, context.getString(R.string.toast_json_exported), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.diag_btn_export_json), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.diag_btn_import_json), fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            InspectorCard(
                status = status0,
                isApplying = isApplying,
                onFixApn = { onFixApnSlot(0) },
                onRestartIms = { onRestartImsSlot(0) },
                onPersistentEnable = { onPersistentVolteEnable(0) },
                onPersistentRestore = { onPersistentVolteRestore(0) }
            )
        }

        item {
            InspectorCard(
                status = status1,
                isApplying = isApplying,
                onFixApn = { onFixApnSlot(1) },
                onRestartIms = { onRestartImsSlot(1) },
                onPersistentEnable = { onPersistentVolteEnable(1) },
                onPersistentRestore = { onPersistentVolteRestore(1) }
            )
        }

        item {
            CaptivePortalCard(
                state = captivePortal,
                isApplying = isApplying,
                onWrite = onCaptivePortalWrite,
                onReset = onCaptivePortalReset
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.diag_preferences_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    ToggleRow(
                        title = stringResource(R.string.toggle_boot_reminder_title),
                        description = stringResource(R.string.toggle_boot_reminder_desc),
                        checked = bootReminder
                    ) { enabled ->
                        bootReminder = enabled
                        SimConfigStore.setBootReminderEnabled(prefs, enabled)
                    }
                    ToggleRow(
                        title = stringResource(R.string.toggle_auto_apply_launch_title),
                        description = stringResource(R.string.toggle_auto_apply_launch_desc),
                        checked = autoApplyOnLaunch
                    ) { enabled ->
                        autoApplyOnLaunch = enabled
                        SimConfigStore.setAutoApplyOnLaunch(prefs, enabled)
                    }
                    ToggleRow(
                        title = stringResource(R.string.toggle_auto_disable_debug_title),
                        description = stringResource(R.string.toggle_auto_disable_debug_desc),
                        checked = autoDisableWifiDebug
                    ) { enabled ->
                        autoDisableWifiDebug = enabled
                        SimConfigStore.setAutoDisableWifiDebug(prefs, enabled)
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = stringResource(R.string.diag_qs_tile_tip),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
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

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLog = !showLog },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.diag_last_log_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Icon(
                            imageVector = if (showLog) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }

                    AnimatedVisibility(visible = showLog) {
                        Text(
                            text = lastLog.ifBlank { stringResource(R.string.diag_no_log) },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        ImportProfileDialog(
            onDismiss = { showImportDialog = false },
            onImport = { jsonText ->
                val res = SimConfigStore.importConfigJson(prefs, jsonText)
                res.fold(
                    onSuccess = { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        bootReminder = SimConfigStore.isBootReminderEnabled(prefs)
                        autoApplyOnLaunch = SimConfigStore.isAutoApplyOnLaunch(prefs)
                        autoDisableWifiDebug = SimConfigStore.isAutoDisableWifiDebug(prefs)
                        showImportDialog = false
                    },
                    onFailure = { err ->
                        Toast.makeText(context, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        )
    }
}

@Composable
private fun BackupCallingReadinessCard(status0: SimLiveStatus, status1: SimLiveStatus) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE) }

    val dualSimActive = status0.subId > 0 && status1.subId > 0
    val anyCrossConfig = (SimConfigStore.loadSimConfig(prefs, 0).let { it.overrideEnabled && it.crossSim }) ||
        (SimConfigStore.loadSimConfig(prefs, 1).let { it.overrideEnabled && it.crossSim })
    val anySystemCrossEnabled = status0.crossSimEnabled || status1.crossSimEnabled
    val apnHealthy = status0.apnConflictDetail.isBlank() && status1.apnConflictDetail.isBlank()

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.readiness_title),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
            ReadinessRow(stringResource(R.string.readiness_dual_sim), dualSimActive)
            ReadinessRow(stringResource(R.string.readiness_carrier_config), anyCrossConfig)
            ReadinessRow(stringResource(R.string.readiness_system_cross_sim), anySystemCrossEnabled)
            ReadinessRow(stringResource(R.string.readiness_apn_health), apnHealthy)
        }
    }
}

@Composable
private fun ReadinessRow(label: String, passed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (passed) StatusGreen else StatusAmber,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (passed) "Ready" else "Pending",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (passed) StatusGreen else StatusAmber
            )
        }
    }
}

@Composable
private fun InspectorCard(
    status: SimLiveStatus,
    isApplying: Boolean,
    onFixApn: () -> Unit,
    onRestartIms: () -> Unit,
    onPersistentEnable: () -> Unit,
    onPersistentRestore: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(status.slotIndex) { context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE) }
    var showOverrides by remember(status.slotIndex) { mutableStateOf(false) }

    val cfg = SimConfigStore.loadSimConfig(prefs, status.slotIndex)
    val overridesList = remember(status.lastUpdatedEpochMs, cfg) {
        SimConfigStore.describeActiveOverrides(cfg)
    }

    val simType = if (status.isEmbedded) "eSIM" else "pSIM"
    val title = if (status.carrierName.isNotBlank()) {
        "SIM ${status.slotIndex + 1} · ${status.carrierName} ($simType)"
    } else {
        "SIM ${status.slotIndex + 1}"
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                TextButton(
                    onClick = onRestartIms,
                    enabled = !isApplying && status.subId > 0,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.diag_btn_restart_sim_ims), fontSize = 11.sp)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            DiagRow("SubID / MCC-MNC", "${status.subId} / ${status.mccMnc.ifBlank { "-" }}")
            DiagRow("CarrierConfig Bundle", status.carrierConfigVersion.ifBlank { "-" })
            DiagRow(
                "IMS Registration",
                if (status.imsRegistered) "Registered (${status.imsTransport})" else "Not Registered",
                valueColor = if (status.imsRegistered) StatusGreen else StatusRed
            )
            DiagRow(
                "Backup Calling (Cross-SIM)",
                if (status.crossSimEnabled) "Enabled" else "Disabled",
                valueColor = if (status.crossSimEnabled) StatusBlue else MaterialTheme.colorScheme.onSurfaceVariant
            )
            DiagRow(
                "VoWiFi Setting",
                if (status.wfcEnabled) "Enabled" else "Disabled",
                valueColor = if (status.wfcEnabled) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
            DiagRow(
                "Active Overrides",
                if (status.configApplied) "${status.overrideCount} keys applied" else "None (Carrier Default)",
                valueColor = if (status.configApplied) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
            DiagRow(
                "APN Health",
                when {
                    status.apnConflictDetail.isNotBlank() -> "Conflict: ${status.apnConflictDetail}"
                    !status.apnImsPresent -> "No dedicated IMS APN found"
                    else -> "Healthy"
                },
                valueColor = if (status.apnConflictDetail.isNotBlank() || !status.apnImsPresent) StatusAmber else StatusGreen
            )

            // Live IMS & 5G Capability Matrix (*#*#4636#*#*)
            Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = stringResource(R.string.diag_live_caps_title),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CapabilityPill("VoLTE", status.capVolte)
                    CapabilityPill("VoWiFi", status.capVowifi)
                    CapabilityPill("VoNR", status.capVonr)
                    CapabilityPill("VT", status.capVt)
                    CapabilityPill("5G NSA", status.capNrNsa)
                    CapabilityPill("5G SA", status.capNrSa)
                }
            }

            // Experimental Persistent VoLTE (VoIMS Opt-In)
            if (status.subId > 0) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.diag_persistent_volte_label),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            val pStatusText = when {
                                !status.persistentVolteSupported -> "Unsupported on current firmware"
                                status.persistentVolteOptIn -> "Active (VoIMS Opt-In ON)"
                                status.persistentVolteCanRestore -> "Modified · Backup saved"
                                else -> "Carrier Default"
                            }
                            Text(
                                text = pStatusText,
                                fontSize = 10.sp,
                                color = if (status.persistentVolteOptIn) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (status.persistentVolteSupported || status.persistentVolteCanRestore) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!status.persistentVolteOptIn && status.persistentVolteSupported) {
                                    FilledTonalButton(
                                        onClick = onPersistentEnable,
                                        enabled = !isApplying,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(stringResource(R.string.diag_persistent_enable), fontSize = 11.sp)
                                    }
                                }
                                if (status.persistentVolteCanRestore) {
                                    OutlinedButton(
                                        onClick = onPersistentRestore,
                                        enabled = !isApplying,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(stringResource(R.string.diag_persistent_restore), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (overridesList.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showOverrides = !showOverrides }
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showOverrides) stringResource(R.string.diag_hide_keys) else stringResource(R.string.diag_show_keys, overridesList.size),
                        color = StatusBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = if (showOverrides) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = StatusBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
                AnimatedVisibility(visible = showOverrides) {
                    Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for ((k, v) in overridesList) {
                            Text(
                                text = "$k = $v",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (status.apnConflictDetail.isNotBlank()) {
                FilledTonalButton(
                    onClick = onFixApn,
                    enabled = !isApplying,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Text(stringResource(R.string.btn_fix_apn), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CapabilityPill(label: String, active: Boolean) {
    val color = if (active) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = if (active) 0.14f else 0.08f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = if (active) "$label ✓" else label,
            color = color,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun CaptivePortalCard(
    state: CaptivePortalState,
    isApplying: Boolean,
    onWrite: (String, String) -> Unit,
    onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var httpInput by remember(state.httpUrl, state.lastUpdatedEpochMs) {
        mutableStateOf(state.httpUrl)
    }
    var httpsInput by remember(state.httpsUrl, state.lastUpdatedEpochMs) {
        mutableStateOf(state.httpsUrl)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.captive_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (state.httpUrl.isBlank() && state.httpsUrl.isBlank()) {
                            "Active: System Default (Google)"
                        } else {
                            "Custom: ${state.httpsUrl.ifBlank { state.httpUrl }}"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.captive_desc),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val presets = listOf(
                            Triple("Cloudflare", "http://cp.cloudflare.com/generate_204", "https://cp.cloudflare.com/generate_204"),
                            Triple("Xiaomi CN", "http://connect.rom.miui.com/generate_204", "https://connect.rom.miui.com/generate_204"),
                            Triple("Huawei CN", "http://connectivitycheck.platform.hicloud.com/generate_204", "https://connectivitycheck.platform.hicloud.com/generate_204")
                        )
                        for ((name, h, hs) in presets) {
                            FilterChip(
                                selected = httpInput == h && httpsInput == hs,
                                onClick = {
                                    httpInput = h
                                    httpsInput = hs
                                },
                                label = { Text(name, fontSize = 11.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = httpInput,
                        onValueChange = { httpInput = it },
                        label = { Text("HTTP Probe URL", fontSize = 11.sp) },
                        placeholder = { Text("http://cp.cloudflare.com/generate_204", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = httpsInput,
                        onValueChange = { httpsInput = it },
                        label = { Text("HTTPS Probe URL", fontSize = 11.sp) },
                        placeholder = { Text("https://cp.cloudflare.com/generate_204", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onWrite(httpInput.trim(), httpsInput.trim()) },
                            enabled = !isApplying && httpInput.isNotBlank() && httpsInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.captive_apply), fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                httpInput = ""
                                httpsInput = ""
                                onReset()
                            },
                            enabled = !isApplying,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.captive_reset), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportProfileDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var jsonText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diag_btn_import_json), fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            OutlinedTextField(
                value = jsonText,
                onValueChange = { jsonText = it },
                label = { Text("Paste ModernIMS JSON", fontSize = 11.sp) },
                minLines = 5,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onImport(jsonText) }) {
                Text(stringResource(R.string.diag_btn_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
private fun DiagRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(8.dp))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}
