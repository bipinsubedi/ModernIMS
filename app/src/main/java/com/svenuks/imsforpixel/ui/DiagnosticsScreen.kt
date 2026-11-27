package com.svenuks.imsforpixel.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    portInput: String,
    onPortInputChange: (String) -> Unit,
    isApplying: Boolean,
    recheckSignal: Long,
    onRefreshStatus: () -> Unit,
    onFixApnSlot: (Int) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var bootReminder by remember { mutableStateOf(SimConfigStore.isBootReminderEnabled(prefs)) }
    var lastLog by remember { mutableStateOf(SimConfigStore.getLastExecutionLog(prefs)) }
    var showLog by remember { mutableStateOf(false) }

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
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                        clipboard.setPrimaryClip(ClipData.newPlainText("IMS Diagnostic Report", report))
                        Toast.makeText(context, context.getString(R.string.toast_report_copied), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.diag_btn_copy_report), fontSize = 12.sp)
                }
            }
        }

        item {
            InspectorCard(status = status0, isApplying = isApplying, onFixApn = { onFixApnSlot(0) })
        }

        item {
            InspectorCard(status = status1, isApplying = isApplying, onFixApn = { onFixApnSlot(1) })
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
}

@Composable
private fun InspectorCard(
    status: SimLiveStatus,
    isApplying: Boolean,
    onFixApn: () -> Unit
) {
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
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
private fun DiagRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
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
