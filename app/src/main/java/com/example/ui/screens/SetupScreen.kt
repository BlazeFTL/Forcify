package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.OperatingMode
import com.example.ui.theme.FrostCyan
import com.example.ui.theme.StateFree
import com.example.ui.viewmodel.PureStopViewModel
import com.example.ui.viewmodel.RootCheckStatus

@Composable
fun SetupScreen(viewModel: PureStopViewModel) {
    val context = LocalContext.current
    val currentMode by viewModel.operatingMode.collectAsState()
    val hasUsageAccess by viewModel.hasUsageAccess.collectAsState()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsState()
    val isIgnoringBattery by viewModel.isIgnoringBattery.collectAsState()
    val rootStatus by viewModel.rootStatus.collectAsState()

    var currentStep by remember { mutableIntStateOf(1) }

    LaunchedEffect(Unit) {
        viewModel.checkPermissions()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "ForCify",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Hibernation & Force Stop Engine",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Step Indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepBadge(stepNumber = 1, title = "Mode", isActive = currentStep == 1, isDone = currentStep > 1)
                Spacer(modifier = Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.outlineVariant))
                StepBadge(stepNumber = 2, title = "Setup", isActive = currentStep == 2, isDone = currentStep > 2)
                Spacer(modifier = Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.outlineVariant))
                StepBadge(stepNumber = 3, title = "Ready", isActive = currentStep == 3, isDone = false)
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (currentStep) {
                1 -> {
                    Step1ModeSelection(
                        selectedMode = currentMode,
                        onSelectMode = { mode ->
                            viewModel.selectOperatingMode(mode)
                            currentStep = 2
                            if (mode == OperatingMode.ROOT) {
                                viewModel.testRootAccess()
                            }
                        }
                    )
                }
                2 -> {
                    Step2Permissions(
                        mode = currentMode,
                        rootStatus = rootStatus,
                        hasUsageAccess = hasUsageAccess,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        isIgnoringBattery = isIgnoringBattery,
                        onTestRoot = { viewModel.testRootAccess() },
                        onGrantUsage = { viewModel.openUsageAccessSettings(context) },
                        onGrantAccessibility = { viewModel.openAccessibilitySettings(context) },
                        onGrantBattery = { viewModel.requestIgnoreBatteryOptimizations(context) },
                        onRefresh = { viewModel.checkPermissions() },
                        onBack = { currentStep = 1 },
                        onProceed = { currentStep = 3 }
                    )
                }
                3 -> {
                    Step3Complete(
                        mode = currentMode,
                        onStart = { viewModel.completeSetup() }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepBadge(stepNumber: Int, title: String, isActive: Boolean, isDone: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isDone -> StateFree
                        isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = "$stepNumber",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Step1ModeSelection(
    selectedMode: OperatingMode,
    onSelectMode: (OperatingMode) -> Unit
) {
    Text(
        text = "Select Operating Mode",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = "Choose how PureStop will execute force stops and manage background processes.",
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
    )

    // Root Mode Card
    ModeCard(
        title = "Root Mode",
        badge = "Instant & Silent",
        badgeColor = MaterialTheme.colorScheme.primary,
        icon = Icons.Default.Bolt,
        description = "Requires Root (Magisk/KernelSU). Kills applications instantly in background without opening Settings, cuts wakeups at the OS appops level, and disables boot receivers.",
        pros = listOf("Zero screen flickering / 100% silent", "Instant batch kill of all selected apps", "Cuts background AppOps and Wake Locks"),
        isSelected = selectedMode == OperatingMode.ROOT,
        onClick = { onSelectMode(OperatingMode.ROOT) }
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Non-Root Mode Card
    ModeCard(
        title = "Non-Root Mode",
        badge = "No Root Required",
        badgeColor = StateFree,
        icon = Icons.Default.Shield,
        description = "Works on any standard Android device. Uses Android Accessibility Service to automate pressing 'Force Stop' and confirming dialogs in App Info settings seamlessly.",
        pros = listOf("Safe and works on standard devices", "Automated batch sequence via Accessibility", "Battery restriction and background kill"),
        isSelected = selectedMode == OperatingMode.NON_ROOT,
        onClick = { onSelectMode(OperatingMode.NON_ROOT) }
    )
}

@Composable
private fun ModeCard(
    title: String,
    badge: String,
    badgeColor: Color,
    icon: ImageVector,
    description: String,
    pros: List<String>,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(badgeColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = icon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = badge, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = badgeColor)
                }
            }

            Text(
                text = description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                lineHeight = 18.sp
            )

            pros.forEach { pro ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 3.dp)
                ) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = StateFree, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = pro, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(text = if (isSelected) "Selected Mode ✓" else "Choose $title")
            }
        }
    }
}

@Composable
private fun Step2Permissions(
    mode: OperatingMode,
    rootStatus: RootCheckStatus,
    hasUsageAccess: Boolean,
    isAccessibilityEnabled: Boolean,
    isIgnoringBattery: Boolean,
    onTestRoot: () -> Unit,
    onGrantUsage: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onGrantBattery: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onProceed: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Required Permissions",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Configuring for ${if (mode == OperatingMode.ROOT) "Root Mode" else "Non-Root Mode"}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        OutlinedButton(
            onClick = onRefresh,
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Check", fontSize = 12.sp)
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (mode == OperatingMode.ROOT) {
        // Root access item
        PermissionCard(
            title = "Superuser (Root) Access",
            description = "Allows executing 'am force-stop' and altering AppOps without user interaction.",
            icon = Icons.Default.Bolt,
            isGranted = rootStatus == RootCheckStatus.GRANTED,
            statusText = when (rootStatus) {
                RootCheckStatus.IDLE -> "Untested"
                RootCheckStatus.CHECKING -> "Checking su..."
                RootCheckStatus.GRANTED -> "Root Access Granted"
                RootCheckStatus.DENIED -> "Root Not Found or Denied"
            },
            actionLabel = "Test Superuser",
            onAction = onTestRoot,
            isLoading = rootStatus == RootCheckStatus.CHECKING
        )
        Spacer(modifier = Modifier.height(12.dp))
    } else {
        // Accessibility Service for Non-Root
        PermissionCard(
            title = "Accessibility Service",
            description = "Enables PureStop to automatically click 'Force Stop' and 'OK' in App Info settings.",
            icon = Icons.Default.AccessibilityNew,
            isGranted = isAccessibilityEnabled,
            statusText = if (isAccessibilityEnabled) "Service Enabled" else "Service Disabled",
            actionLabel = if (isAccessibilityEnabled) "Configured" else "Enable Service",
            onAction = onGrantAccessibility
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Usage Access for both
    PermissionCard(
        title = "Usage Access",
        description = "Detects whether apps are in Foreground, Working State, or Evading Restrictions.",
        icon = Icons.Default.DataUsage,
        isGranted = hasUsageAccess,
        statusText = if (hasUsageAccess) "Permission Granted" else "Permission Required",
        actionLabel = if (hasUsageAccess) "Granted" else "Grant Access",
        onAction = onGrantUsage
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Battery Optimization ignore
    PermissionCard(
        title = "Ignore Battery Optimizations",
        description = "Ensures Android does not kill PureStop itself while automating app freezes.",
        icon = Icons.Default.BatteryAlert,
        isGranted = isIgnoringBattery,
        statusText = if (isIgnoringBattery) "Optimizations Ignored" else "Recommended",
        actionLabel = if (isIgnoringBattery) "Configured" else "Allow",
        onAction = onGrantBattery
    )

    Spacer(modifier = Modifier.height(24.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Back")
        }

        Button(
            onClick = onProceed,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Continue to Verification →")
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    statusText: String,
    actionLabel: String,
    onAction: () -> Unit,
    isLoading: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (isGranted) StateFree.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isGranted) StateFree.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else icon,
                    contentDescription = null,
                    tint = if (isGranted) StateFree else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isGranted) StateFree else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Button(
                    onClick = onAction,
                    enabled = !isGranted,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isGranted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                        contentColor = if (isGranted) StateFree else Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = actionLabel, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Step3Complete(
    mode: OperatingMode,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(StateFree.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = StateFree,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Setup Completed!",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "PureStop is now configured in ${if (mode == OperatingMode.ROOT) "Root Mode ⚡" else "Non-Root Mode 🛡️"}. You can now add applications to your hibernation list and force-close them on demand.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Quick Instructions:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "1. Tap the '+' button at the top of the screen to choose apps to manage.\n2. Tap the bottom-right Force Stop button at any time to hibernate all running managed apps.\n3. Tap any app card to inspect wake-up triggers and cut background activity.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(imageVector = Icons.Default.Bolt, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Enter PureStop Dashboard", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
