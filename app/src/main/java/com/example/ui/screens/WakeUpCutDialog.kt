package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RoomService
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.OperatingMode
import com.example.model.InstalledAppItem
import com.example.model.WakeUpPath
import com.example.model.WakeUpPathType
import com.example.model.WakeUpRiskLevel
import com.example.ui.components.AppIconImage
import com.example.ui.components.AppStatusBadge
import com.example.ui.theme.StateEvading
import com.example.ui.theme.StateFree
import com.example.ui.theme.StateWorking

private enum class WakeUpFilterTab {
    ALL,
    SAFE,
    MODERATE,
    RISKY
}

@Composable
fun WakeUpCutDialog(
    app: InstalledAppItem,
    operatingMode: OperatingMode,
    onDismiss: () -> Unit,
    onTogglePath: (InstalledAppItem, WakeUpPath, Boolean) -> Unit,
    onCutSafePaths: (InstalledAppItem) -> Unit,
    onCutAllPaths: (InstalledAppItem) -> Unit,
    onRestoreAllPaths: (InstalledAppItem) -> Unit,
    onForceStop: (InstalledAppItem) -> Unit,
    onToggleMonitor: (Boolean) -> Unit = {},
    onDismissDetectedEvent: (com.example.model.DetectedWakeUpEvent) -> Unit = {}
) {
    val wakeUpDetails = app.wakeUpDetails
    val paths = wakeUpDetails.paths
    var currentTab by remember { mutableStateOf(WakeUpFilterTab.ALL) }

    val filteredPaths = remember(paths, currentTab) {
        when (currentTab) {
            WakeUpFilterTab.ALL -> paths
            WakeUpFilterTab.SAFE -> paths.filter { it.riskLevel == WakeUpRiskLevel.SAFE }
            WakeUpFilterTab.MODERATE -> paths.filter { it.riskLevel == WakeUpRiskLevel.MODERATE }
            WakeUpFilterTab.RISKY -> paths.filter { it.riskLevel == WakeUpRiskLevel.RISKY }
        }
    }

    // Use full width Dialog with usePlatformDefaultWidth = false to remove cramped narrow layout!
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // Header: App Icon, App Name, Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIconImage(drawable = app.icon, appName = app.appName, size = 46.dp)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = app.appName,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            AppStatusBadge(state = app.state)
                        }
                        Text(
                            text = app.packageName,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Stats Banner: Telemetry, Culprits, and Cut progress
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${wakeUpDetails.wakeupCount24h}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "24h Launches",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(modifier = Modifier.width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${wakeUpDetails.primaryCulpritsCount}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (wakeUpDetails.primaryCulpritsCount > 0) Color(0xFFEA580C) else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Autostart Vectors",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(modifier = Modifier.width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${wakeUpDetails.cutPathsCount} / ${paths.size}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (wakeUpDetails.cutPathsCount > 0) StateFree else StateEvading
                            )
                            Text(
                                text = "Paths Cut",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Monitor Wake-Up Path Option Card
                var isMonitoring by remember(app.packageName, app.isWakeUpMonitoringEnabled) {
                    mutableStateOf(app.isWakeUpMonitoringEnabled)
                }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isMonitoring) Color(0xFFFFFBEB) else Color(0xFFF8FAFC)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isMonitoring) Color(0xFFFDE68A) else Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = if (isMonitoring) Color(0xFFD97706) else Color(0xFF64748B),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Monitor Wake-Up Path",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isMonitoring) Color(0xFF92400E) else Color(0xFF334155)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isMonitoring) "Actively detecting background autostarts & network triggers" else "Tick on to monitor and record background wake-ups",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                        Switch(
                            checked = isMonitoring,
                            onCheckedChange = {
                                isMonitoring = it
                                onToggleMonitor(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFD97706),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFFE2E8F0)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Detected Background Wake-Up Card(s) (e.g. TeraBox, SyncService caught starting in background)
                val detectedWakeUps = remember(app.packageName) {
                    com.example.detector.BackgroundWakeUpDetector.detectedEvents.value
                        .filter { it.packageName == app.packageName && !it.isCut }
                }

                if (detectedWakeUps.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        detectedWakeUps.forEach { detected ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFF59E0B)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFF59E0B)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Bolt,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = "DETECTED BACKGROUND WAKE-UP",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF92400E)
                                                )
                                                Text(
                                                    text = "${detected.pathTitle} (${detected.componentName.substringAfterLast('.')})",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF1E293B)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { onDismissDetectedEvent(detected) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Dismiss",
                                                tint = Color(0xFF94A3B8),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${detected.triggerContext} • ${detected.formattedTime}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            val matchingPath = paths.find {
                                                it.componentName.contains(detected.componentName) ||
                                                detected.componentName.contains(it.componentName) ||
                                                it.componentName.endsWith(".${detected.componentName.substringAfterLast('.')}")
                                            } ?: WakeUpPath(
                                                id = "${app.packageName}:detected:${detected.componentName}",
                                                packageName = app.packageName,
                                                type = detected.pathType,
                                                title = detected.pathTitle,
                                                componentName = detected.componentName,
                                                reason = detected.triggerContext,
                                                isCut = false
                                            )
                                            onTogglePath(app, matchingPath, true)
                                        },
                                        modifier = Modifier.fillMaxWidth().height(36.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                                    ) {
                                        Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Cut This Path Only (${detected.componentName.substringAfterLast('.')})",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                } else {
                    // Informative status when no wake-up has occurred yet
                    if (isMonitoring) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBBF7D0)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFDCFCE7)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Wake-Up Monitoring Active",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF166534)
                                    )
                                    Text(
                                        text = "Monitoring background autostarts & network triggers. Once a wake-up occurs, its exact path will appear here.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF15803D),
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFF1F5F9)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Wake-Up Monitoring Disabled",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF334155)
                                    )
                                    Text(
                                        text = "Tick on 'Monitor Wake-Up Path' above to capture silent background autostarts.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B),
                                        lineHeight = 15.sp
                                    )
                                }
                                Button(
                                    onClick = {
                                        isMonitoring = true
                                        onToggleMonitor(true)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Enable", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Quick Action Bar: Cut Safe Only, Cut All, Restore All
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (wakeUpDetails.safePathsCount > 0 && paths.any { it.riskLevel == WakeUpRiskLevel.SAFE && !it.isCut }) {
                        Button(
                            onClick = { onCutSafePaths(app) },
                            colors = ButtonDefaults.buttonColors(containerColor = StateFree),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Cut Safe (${wakeUpDetails.safePathsCount})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (paths.any { !it.isCut }) {
                        OutlinedButton(
                            onClick = { onCutAllPaths(app) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Cut All (${paths.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (paths.any { it.isCut }) {
                        OutlinedButton(
                            onClick = { onRestoreAllPaths(app) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Restore All", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Risk Filter Tabs (Safe to Cut vs Caution vs Risky)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = currentTab == WakeUpFilterTab.ALL,
                        onClick = { currentTab = WakeUpFilterTab.ALL },
                        label = { Text("All (${paths.size})", fontSize = 11.sp) },
                        shape = RoundedCornerShape(8.dp)
                    )
                    FilterChip(
                        selected = currentTab == WakeUpFilterTab.SAFE,
                        onClick = { currentTab = WakeUpFilterTab.SAFE },
                        label = {
                            Text(
                                text = "🛡️ Safe (${wakeUpDetails.safePathsCount})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StateFree.copy(alpha = 0.15f),
                            selectedLabelColor = StateFree
                        )
                    )
                    FilterChip(
                        selected = currentTab == WakeUpFilterTab.MODERATE,
                        onClick = { currentTab = WakeUpFilterTab.MODERATE },
                        label = {
                            Text(
                                text = "⚠️ Sync/Providers (${wakeUpDetails.moderatePathsCount})",
                                fontSize = 11.sp
                            )
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                    FilterChip(
                        selected = currentTab == WakeUpFilterTab.RISKY,
                        onClick = { currentTab = WakeUpFilterTab.RISKY },
                        label = {
                            Text(
                                text = "🚨 Push (${wakeUpDetails.riskyPathsCount})",
                                fontSize = 11.sp
                            )
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Paths List
                if (filteredPaths.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No wake-up vectors found under this filter category.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredPaths, key = { it.id }) { path ->
                            DetailedWakeUpPathCard(
                                path = path,
                                onToggle = { isChecked ->
                                    onTogglePath(app, path, isChecked)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Action Footer: Force Stop & Done
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (operatingMode == OperatingMode.ROOT) "⚡ Root: Direct 'pm disable' enforcement" else "🛡️ Non-Root: App background restrict mode",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onForceStop(app)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Stop", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(text = "Done", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailedWakeUpPathCard(
    path: WakeUpPath,
    onToggle: (Boolean) -> Unit
) {
    val icon = when (path.type) {
        WakeUpPathType.PROVIDER_DOCUMENTS -> Icons.Default.FolderShared
        WakeUpPathType.PROVIDER_CONTENT -> Icons.Default.FolderShared
        WakeUpPathType.SERVICE_SYNC_ADAPTER -> Icons.Default.CloudSync
        WakeUpPathType.RECEIVER_BOOT -> Icons.Default.Bolt
        WakeUpPathType.RECEIVER_CONNECTIVITY -> Icons.Default.Wifi
        WakeUpPathType.RECEIVER_POWER -> Icons.Default.BatteryAlert
        WakeUpPathType.OP_SCHEDULED_ALARM -> Icons.Default.Alarm
        WakeUpPathType.RECEIVER_TRACKER -> Icons.Default.Tune
        WakeUpPathType.RECEIVER_PUSH,
        WakeUpPathType.SERVICE_FOREGROUND -> Icons.Default.Notifications
        WakeUpPathType.SERVICE_BACKGROUND,
        WakeUpPathType.SERVICE_JOB -> Icons.Default.RoomService
        WakeUpPathType.OP_WAKE_LOCK -> Icons.Default.Lock
        WakeUpPathType.BATTERY_OPTIMIZATION -> Icons.Default.BatteryAlert
        else -> Icons.Default.Tune
    }

    val riskColor = when (path.riskLevel) {
        WakeUpRiskLevel.SAFE -> StateFree
        WakeUpRiskLevel.MODERATE -> Color(0xFFD97706)
        WakeUpRiskLevel.RISKY -> StateEvading
    }

    val riskBg = when (path.riskLevel) {
        WakeUpRiskLevel.SAFE -> StateFree.copy(alpha = 0.12f)
        WakeUpRiskLevel.MODERATE -> Color(0xFFFEF3C7)
        WakeUpRiskLevel.RISKY -> StateEvading.copy(alpha = 0.12f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = Color(0xFFE2E8F0),
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (path.isCut) StateFree.copy(alpha = 0.15f) else riskBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (path.isCut) Icons.Default.ContentCut else icon,
                    contentDescription = null,
                    tint = if (path.isCut) StateFree else riskColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Body
            Column(modifier = Modifier.weight(1f)) {
                // Top Tag Row: Category, Active Cause Flame, Risk Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (path.reason.contains("CAUGHT WAKING APP")) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFFEF3C7))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "CAUGHT IN BACKGROUND ⚡",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD97706)
                            )
                        }
                    } else if (path.isActiveVector || path.isPrimaryCulprit) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFEA580C).copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "ACTIVE VECTOR 🔥",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFEA580C)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(riskBg)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = path.riskLevel.label.uppercase(),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = riskColor
                        )
                    }

                    Text(
                        text = path.type.category,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                // Title
                Text(
                    text = path.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Reason
                Text(
                    text = path.reason,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )

                // Risk & Breakage Explanation
                if (path.riskExplanation.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Impact: ${path.riskExplanation}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = riskColor,
                        lineHeight = 14.sp
                    )
                }

                // Component Name
                if (path.componentName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = path.componentName,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Switch Cut Toggle
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(
                    checked = path.isCut,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Text(
                    text = if (path.isCut) "Cut" else "Active",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (path.isCut) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
