package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.OperatingMode
import com.example.model.AppState
import com.example.model.BatchFreezeProgress
import com.example.model.InstalledAppItem
import com.example.ui.components.AppIconImage
import com.example.ui.theme.StateEvading
import com.example.ui.theme.StateForeground
import com.example.ui.theme.StateFree
import com.example.ui.theme.StateFreeBg
import com.example.ui.theme.StateWorking
import com.example.ui.viewmodel.PureStopViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: PureStopViewModel) {
    val operatingMode by viewModel.operatingMode.collectAsState()
    val allManagedApps by viewModel.allManagedApps.collectAsState()
    val pendingApps by viewModel.pendingApps.collectAsState()
    val notHibernatingApps by viewModel.notHibernatingApps.collectAsState()
    val willHibernateSoonApps by viewModel.willHibernateSoonApps.collectAsState()
    val hibernatedApps by viewModel.hibernatedApps.collectAsState()
    val allInstalledApps by viewModel.allInstalledApps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingAddApps by viewModel.isLoadingAddApps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val showAddAppsSheet by viewModel.showAddAppsSheet.collectAsState()
    val showCutBootDialog by viewModel.showCutBootDialog.collectAsState()
    val isInitialScanCompleted by viewModel.isInitialScanCompleted.collectAsState()
    val hideSystemAppsInAddList by viewModel.hideSystemAppsInAddList.collectAsState()
    val addAppSortOption by viewModel.addAppSortOption.collectAsState()
    val selectedAppForWakeup by viewModel.selectedAppForWakeup.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val showWakeUpManagerDialog by viewModel.showWakeUpManagerDialog.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var isHibernatedSectionExpanded by remember { mutableStateOf(false) }

    // Multi-select for batch stopping requested by user
    var selectedPackagesForBatchStop by remember { mutableStateOf(setOf<String>()) }
    val isMultiSelectMode = selectedPackagesForBatchStop.isNotEmpty()

    BackHandler(enabled = isMultiSelectMode) {
        selectedPackagesForBatchStop = emptySet()
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    // Filtered lists for each category
    val filteredNotHibernating = remember(notHibernatingApps, searchQuery) {
        if (searchQuery.isBlank()) notHibernatingApps else {
            notHibernatingApps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val filteredWillHibernateSoon = remember(willHibernateSoonApps, searchQuery) {
        if (searchQuery.isBlank()) willHibernateSoonApps else {
            willHibernateSoonApps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val filteredHibernated = remember(hibernatedApps, searchQuery) {
        if (searchQuery.isBlank()) hibernatedApps else {
            hibernatedApps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val stoppableCount = remember(pendingApps) {
        pendingApps.count {
            it.state != AppState.WORKING_STATE || it.ignoreWorkingState
        }
    }

    val protectedWorkingCount = remember(pendingApps) {
        pendingApps.count {
            it.state == AppState.WORKING_STATE && !it.ignoreWorkingState
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isMultiSelectMode) {
                // Contextual Selection TopBar
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selectedPackagesForBatchStop = emptySet() }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                    },
                    title = {
                        Text(
                            text = "${selectedPackagesForBatchStop.size} Selected",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    actions = {
                        val selectable = if (searchQuery.isBlank()) pendingApps else (filteredNotHibernating + filteredWillHibernateSoon)
                        val allSelected = selectable.isNotEmpty() &&
                            selectable.all { selectedPackagesForBatchStop.contains(it.packageName) }
                        TextButton(
                            onClick = {
                                selectedPackagesForBatchStop = if (allSelected) {
                                    emptySet()
                                } else {
                                    selectable.map { it.packageName }.toSet()
                                }
                            }
                        ) {
                            Text(
                                text = if (allSelected) "Deselect All" else "Select All",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "ForCify",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Mode Indicator Pill (Root / Non-Root)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (operatingMode == OperatingMode.ROOT)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else
                                            StateFree.copy(alpha = 0.15f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (operatingMode == OperatingMode.ROOT)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                        else
                                            StateFree.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable { showModeDialog = true }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (operatingMode == OperatingMode.ROOT) Icons.Default.Bolt else Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = if (operatingMode == OperatingMode.ROOT) MaterialTheme.colorScheme.primary else StateFree,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (operatingMode == OperatingMode.ROOT) "Root" else "Non-Root",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (operatingMode == OperatingMode.ROOT) MaterialTheme.colorScheme.primary else StateFree
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Search toggle button
                        IconButton(
                            onClick = {
                                isSearchExpanded = !isSearchExpanded
                                if (!isSearchExpanded) viewModel.setSearchQuery("")
                            }
                        ) {
                            Icon(imageVector = Icons.Default.Search, contentDescription = "Search Apps")
                        }

                        // THE "+" BUTTON ON TOP REQUESTED BY USER!
                        IconButton(
                            onClick = { viewModel.openAddApps() },
                            modifier = Modifier
                                .testTag("add_apps_top_button")
                                .padding(end = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add Apps to Freeze List",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Three-dot menu: Styled to match app's clean card design
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Options")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier
                                    .widthIn(min = 220.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                                shape = RoundedCornerShape(16.dp),
                                containerColor = Color.White,
                                tonalElevation = 6.dp,
                                shadowElevation = 10.dp
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Refresh Status", fontWeight = FontWeight.Medium) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.refreshApps()
                                    },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cut Wakeups / Paths", fontWeight = FontWeight.Medium) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.openWakeUpManager()
                                    },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cut Boot Receivers", fontWeight = FontWeight.Medium) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.openCutBootDialog()
                                    },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.PowerOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                DropdownMenuItem(
                                    text = { Text("Switch Operating Mode", fontWeight = FontWeight.Medium) },
                                    onClick = {
                                        showMenu = false
                                        showModeDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Re-run Setup Wizard", fontWeight = FontWeight.Medium) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.resetSetup()
                                    },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            if (isMultiSelectMode) {
                val selectedApps = allManagedApps.filter { selectedPackagesForBatchStop.contains(it.packageName) }
                val singleSelected = selectedApps.firstOrNull()
                var showSelectionOverflow by remember { mutableStateOf(false) }

                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (selectedApps.size == 1) singleSelected?.appName ?: "" else "${selectedApps.size} Selected",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )

                        // Force Stop / Hibernate Button
                        IconButton(
                            onClick = {
                                viewModel.forceStopSelected(selectedApps)
                                selectedPackagesForBatchStop = emptySet()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Force Stop",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Selection Options Overflow Menu
                        Box {
                            IconButton(onClick = { showSelectionOverflow = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = Color.White
                                )
                            }

                            DropdownMenu(
                                expanded = showSelectionOverflow,
                                onDismissRequest = { showSelectionOverflow = false },
                                modifier = Modifier
                                    .widthIn(min = 230.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                                shape = RoundedCornerShape(16.dp),
                                containerColor = Color.White,
                                tonalElevation = 6.dp,
                                shadowElevation = 10.dp
                            ) {
                                if (selectedApps.size == 1 && singleSelected != null) {
                                    DropdownMenuItem(
                                        text = { Text("Run") },
                                        onClick = {
                                            showSelectionOverflow = false
                                            viewModel.launchApp(singleSelected.packageName)
                                        },
                                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                                    )
                                }

                                DropdownMenuItem(
                                    text = { Text("Degreenify selected app") },
                                    onClick = {
                                        showSelectionOverflow = false
                                        selectedApps.forEach { viewModel.removeAppFromFreezeList(it.packageName) }
                                        selectedPackagesForBatchStop = emptySet()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                                )

                                if (selectedApps.size == 1 && singleSelected != null) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Restrict running as foreground")
                                                Checkbox(
                                                    checked = singleSelected.isRestrictedForeground,
                                                    onCheckedChange = null,
                                                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.toggleRestrictRunningAsForeground(singleSelected)
                                        }
                                    )

                                    HorizontalDivider()

                                    Text(
                                        text = "HIBERNATION SETTINGS",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Ignore working state")
                                                Checkbox(
                                                    checked = singleSelected.ignoreWorkingState,
                                                    onCheckedChange = null,
                                                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.toggleIgnoreWorkingState(singleSelected)
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("Inspect Wake-Ups") },
                                        onClick = {
                                            showSelectionOverflow = false
                                            viewModel.selectAppForWakeup(singleSelected)
                                        },
                                        leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isMultiSelectMode) {
                val hasStoppable = stoppableCount > 0
                ExtendedFloatingActionButton(
                    onClick = { viewModel.forceStopAllRunning() },
                    containerColor = if (hasStoppable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (hasStoppable) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .testTag("force_stop_fab_button")
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp, end = 8.dp),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Force Stop Button",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    text = {
                        val fabLabel = when {
                            stoppableCount > 0 -> "Force Stop ($stoppableCount)"
                            protectedWorkingCount > 0 -> "Working Protected ($protectedWorkingCount)"
                            else -> "All Hibernated ✓"
                        }
                        Text(
                            text = fabLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Optional Search Bar with "X" clear button
            AnimatedVisibility(visible = isSearchExpanded && !isMultiSelectMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = { Text("Filter running apps...") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = if (searchQuery.isNotBlank()) {
                        {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    } else null,
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            // Main Content: Strictly running apps only, no stopped/hibernated lists cluttering the page!
            if (isLoading && allManagedApps.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (allManagedApps.isEmpty()) {
                if (viewModel.preferences.savedManagedPackages.isNotEmpty() || !isInitialScanCompleted) {
                    // Restoring cached items on startup, do NOT flash "No Apps Added Yet"
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                    }
                } else {
                    // Truly empty state when no apps are added yet
                    EmptyStateView(
                        isSearchActive = searchQuery.isNotBlank(),
                        onAddApps = { viewModel.openAddApps() }
                    )
                }
            } else if (filteredNotHibernating.isEmpty() && filteredWillHibernateSoon.isEmpty()) {
                if (allManagedApps.isEmpty()) {
                    if (viewModel.preferences.savedManagedPackages.isEmpty() && isInitialScanCompleted) {
                        EmptyStateView(
                            isSearchActive = searchQuery.isNotBlank(),
                            onAddApps = { viewModel.openAddApps() }
                        )
                    }
                } else if (!isInitialScanCompleted) {
                    // Smooth initial scan in progress, do not flash empty state
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                    }
                } else if (searchQuery.isNotBlank() && filteredHibernated.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No apps match '$searchQuery'",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    // All managed apps are in hibernated state!
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AllHibernatedCleanCard(
                            managedCount = allManagedApps.size,
                            onAddMore = { viewModel.openAddApps() }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. NOT HIBERNATING AUTOMATICALLY SECTION
                    if (filteredNotHibernating.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isMultiSelectMode) "LONG PRESS OR TAP APPS TO SELECT" else "NOT HIBERNATING AUTOMATICALLY (${filteredNotHibernating.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!isMultiSelectMode && protectedWorkingCount > 0) {
                                    Text(
                                        text = "$protectedWorkingCount Working Protected",
                                        fontSize = 11.sp,
                                        color = StateWorking,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Text(
                                        text = "${filteredNotHibernating.size} Running",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        items(filteredNotHibernating, key = { it.packageName }) { app ->
                            val isSelected = selectedPackagesForBatchStop.contains(app.packageName)
                            GreenifyStyleAppCard(
                                app = app,
                                isSelectionMode = isMultiSelectMode,
                                isSelected = isSelected,
                                onToggleSelect = {
                                    selectedPackagesForBatchStop = if (isSelected) {
                                        selectedPackagesForBatchStop - app.packageName
                                    } else {
                                        selectedPackagesForBatchStop + app.packageName
                                    }
                                },
                                onLongClick = {
                                    selectedPackagesForBatchStop = selectedPackagesForBatchStop + app.packageName
                                },
                                onForceStop = { viewModel.forceStopSingle(app) },
                                onRun = { viewModel.launchApp(app.packageName) },
                                onToggleRestrictForeground = { viewModel.toggleRestrictRunningAsForeground(app) },
                                onOpenWakeup = { viewModel.selectAppForWakeup(app) },
                                onRemove = { viewModel.removeAppFromFreezeList(app.packageName) },
                                onToggleIgnoreWorking = { viewModel.toggleIgnoreWorkingState(app) }
                            )
                        }
                    }

                    // 2. WILL HIBERNATE SOON AFTER SCREEN GOES OFF SECTION
                    if (filteredWillHibernateSoon.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "WILL HIBERNATE SOON AFTER SCREEN GOES OFF (${filteredWillHibernateSoon.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${filteredWillHibernateSoon.size} Pending",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        items(filteredWillHibernateSoon, key = { it.packageName }) { app ->
                            val isSelected = selectedPackagesForBatchStop.contains(app.packageName)
                            GreenifyStyleAppCard(
                                app = app,
                                isSelectionMode = isMultiSelectMode,
                                isSelected = isSelected,
                                onToggleSelect = {
                                    selectedPackagesForBatchStop = if (isSelected) {
                                        selectedPackagesForBatchStop - app.packageName
                                    } else {
                                        selectedPackagesForBatchStop + app.packageName
                                    }
                                },
                                onLongClick = {
                                    selectedPackagesForBatchStop = selectedPackagesForBatchStop + app.packageName
                                },
                                onForceStop = { viewModel.forceStopSingle(app) },
                                onRun = { viewModel.launchApp(app.packageName) },
                                onToggleRestrictForeground = { viewModel.toggleRestrictRunningAsForeground(app) },
                                onOpenWakeup = { viewModel.selectAppForWakeup(app) },
                                onRemove = { viewModel.removeAppFromFreezeList(app.packageName) },
                                onToggleIgnoreWorking = { viewModel.toggleIgnoreWorkingState(app) }
                            )
                        }
                    }

                    // 3. HIBERNATED SECTION (Collapsible)
                    if (filteredHibernated.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { isHibernatedSectionExpanded = !isHibernatedSectionExpanded }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isHibernatedSectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "HIBERNATED (${filteredHibernated.size})",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = if (isHibernatedSectionExpanded) "Tap to collapse" else "Tap to expand",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (isHibernatedSectionExpanded) {
                            items(filteredHibernated, key = { it.packageName }) { app ->
                                val isSelected = selectedPackagesForBatchStop.contains(app.packageName)
                                GreenifyStyleAppCard(
                                    app = app,
                                    isSelectionMode = isMultiSelectMode,
                                    isSelected = isSelected,
                                    onToggleSelect = {
                                        selectedPackagesForBatchStop = if (isSelected) {
                                            selectedPackagesForBatchStop - app.packageName
                                        } else {
                                            selectedPackagesForBatchStop + app.packageName
                                        }
                                    },
                                    onLongClick = {
                                        selectedPackagesForBatchStop = selectedPackagesForBatchStop + app.packageName
                                    },
                                    onForceStop = { viewModel.forceStopSingle(app) },
                                    onRun = { viewModel.launchApp(app.packageName) },
                                    onToggleRestrictForeground = { viewModel.toggleRestrictRunningAsForeground(app) },
                                    onOpenWakeup = { viewModel.selectAppForWakeup(app) },
                                    onRemove = { viewModel.removeAppFromFreezeList(app.packageName) },
                                    onToggleIgnoreWorking = { viewModel.toggleIgnoreWorkingState(app) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Add Apps Sheet with Sort options and Hide System Apps inside Three-Dot
    if (showAddAppsSheet) {
        AddAppsDialog(
            allApps = allInstalledApps,
            managedPackageNames = allManagedApps.map { it.packageName }.toSet(),
            hideSystemApps = hideSystemAppsInAddList,
            onToggleHideSystemApps = { viewModel.setHideSystemAppsInAddList(it) },
            sortOption = addAppSortOption,
            onSelectSortOption = { viewModel.setAddAppSortOption(it) },
            isLoading = isLoadingAddApps,
            onDismiss = { viewModel.closeAddApps() },
            onConfirmAdd = { selected ->
                viewModel.addAppsToFreezeList(selected)
            }
        )
    }

    // Cut Boot Receivers Dialog
    if (showCutBootDialog) {
        CutBootReceiversDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.closeCutBootDialog() }
        )
    }

    // Wake-Up Paths Manager Dialog (App list with selection, path choices, and re-attach)
    if (showWakeUpManagerDialog) {
        WakeUpPathsManagerDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.closeWakeUpManager() }
        )
    }

    // Wake-Up Inspection & Granular Cut Dialog
    selectedAppForWakeup?.let { app ->
        WakeUpCutDialog(
            app = app,
            operatingMode = operatingMode,
            onDismiss = { viewModel.selectAppForWakeup(null) },
            onTogglePath = { item, path, cut ->
                viewModel.toggleSpecificWakeUpPath(item, path, cut)
            },
            onCutSafePaths = { item ->
                viewModel.cutSafeWakeUpPaths(item)
            },
            onCutAllPaths = { item ->
                viewModel.cutAllWakeUpPaths(item)
            },
            onRestoreAllPaths = { item ->
                viewModel.restoreAllWakeUpPaths(item)
            },
            onForceStop = { item ->
                viewModel.forceStopSingle(item)
            }
        )
    }

    // Batch Force Stop Progress Dialog
    if (batchProgress.isRunning) {
        BatchProgressDialog(
            progress = batchProgress,
            onDismiss = { viewModel.engine.dismissBatchProgress() }
        )
    }

    // Switch Mode Dialog
    if (showModeDialog) {
        SwitchModeDialog(
            currentMode = operatingMode,
            onSelectMode = { newMode ->
                viewModel.selectOperatingMode(newMode)
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false }
        )
    }
}

/**
 * Greenify-style app card:
 * - App name and status
 * - Working Mode protection badge & "Working Mode Ignored" indicator
 * - Stop action on right
 * - Long press to multi-select apps for batch force stop
 * - 3-Dot menu with Ignore Working Mode toggle
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GreenifyStyleAppCard(
    app: InstalledAppItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onLongClick: () -> Unit,
    onForceStop: () -> Unit,
    onRun: () -> Unit,
    onToggleRestrictForeground: () -> Unit,
    onOpenWakeup: () -> Unit,
    onRemove: () -> Unit,
    onToggleIgnoreWorking: () -> Unit
) {
    var showItemMenu by remember { mutableStateOf(false) }

    val isWillHibernateSoon = app.state != AppState.FOREGROUND &&
        app.state != AppState.EVADING_RESTRICTIONS &&
        app.state != AppState.WORKING_STATE

    val stateText = when {
        app.state == AppState.EVADING_RESTRICTIONS -> "Running as foreground (evading restrictions)"
        app.state == AppState.FOREGROUND -> "Foreground"
        app.state == AppState.WORKING_STATE -> "Active Task (Downloading / Media)"
        isWillHibernateSoon -> ""
        app.stateDetail.isNotBlank() && app.stateDetail != "Hibernated" && app.stateDetail != "Pending Hibernation" -> app.stateDetail
        else -> ""
    }

    val stateColor = when (app.state) {
        AppState.EVADING_RESTRICTIONS -> StateEvading
        AppState.FOREGROUND -> StateForeground
        AppState.WORKING_STATE -> StateWorking
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelect()
                    } else {
                        onOpenWakeup()
                    }
                },
                onLongClick = onLongClick
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
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.padding(end = 4.dp)
                )
            }

            AppIconImage(drawable = app.icon, appName = app.appName, size = 44.dp)
            Spacer(modifier = Modifier.width(12.dp))

            // Greenify Style Title & Subtitle
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.appName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                if (stateText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stateText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = stateColor,
                        maxLines = 1
                    )
                }

                if (app.secondaryDetail.isNotBlank()) {
                    for (line in app.secondaryDetail.lines()) {
                        val trimmed = line.trim()
                        if (trimmed.isNotBlank() && trimmed != "Will hibernate after screen off" && trimmed != "Pending Hibernation") {
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = trimmed,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Stop Button on Right (single stop)
            if (!isSelectionMode) {
                Button(
                    onClick = onForceStop,
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("force_stop_${app.packageName}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.StopCircle,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Stop",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box {
                    IconButton(onClick = { showItemMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showItemMenu,
                        onDismissRequest = { showItemMenu = false },
                        modifier = Modifier
                            .widthIn(min = 250.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        containerColor = Color.White,
                        tonalElevation = 6.dp,
                        shadowElevation = 12.dp
                    ) {
                        // 1. Launch App
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Launch Application", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("Open app in foreground", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                showItemMenu = false
                                onRun()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )

                        // 2. Inspect Wake-Ups & Paths
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Inspect Wake-Ups", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("${app.wakeUpDetails.paths.size} wake-up path(s) detected", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                showItemMenu = false
                                onOpenWakeup()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ContentCut,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))

                        // Header: HIBERNATION RULES
                        Text(
                            text = "HIBERNATION RULES",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                        )

                        // 3. Ignore Working State (toggles whether app is frozen even during active playback/fg service)
                        DropdownMenuItem(
                            text = {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Ignore Working State", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text("Hibernate even if active / audio playing", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            trailingIcon = {
                                Checkbox(
                                    checked = app.ignoreWorkingState,
                                    onCheckedChange = { onToggleIgnoreWorking() },
                                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                )
                            },
                            onClick = {
                                onToggleIgnoreWorking()
                            }
                        )

                        // 4. Restrict Running as Foreground
                        DropdownMenuItem(
                            text = {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Block Foreground Service", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text("Prevent sticky background notifications", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            trailingIcon = {
                                Checkbox(
                                    checked = app.isRestrictedForeground,
                                    onCheckedChange = { onToggleRestrictForeground() },
                                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                )
                            },
                            onClick = {
                                onToggleRestrictForeground()
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))

                        // 5. Remove from ForCify
                        DropdownMenuItem(
                            text = {
                                Text("Remove from ForCify", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFFDC2626))
                            },
                            onClick = {
                                showItemMenu = false
                                onRemove()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = Color(0xFFDC2626),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AllHibernatedCleanCard(
    managedCount: Int,
    onAddMore: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, StateFree.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(StateFreeBg),
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
                text = "All Apps Stopped & Hibernated",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "$managedCount apps in your freeze list are currently background-free. Zero RAM or battery wasted.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAddMore,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Add More Apps", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyStateView(
    isSearchActive: Boolean,
    onAddApps: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(70.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = if (isSearchActive) "No Matching Apps" else "No Apps Added Yet",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (isSearchActive)
                "Try searching with another name or clear the search query."
            else
                "Add apps to your freeze list to detect running services and force stop them on demand.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onAddApps,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add Apps to Freeze List", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BatchProgressDialog(
    progress: BatchFreezeProgress,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Force Stopping Apps...",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Stopping: ${progress.currentAppName}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = progress.summary,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (progress.totalCount > 0) {
                    val frac = progress.completedCount.toFloat() / progress.totalCount.toFloat()
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}

@Composable
private fun SwitchModeDialog(
    currentMode: OperatingMode,
    onSelectMode: (OperatingMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text("Switch Operating Mode", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeSelectionItem(
                    title = "Root Mode (SuperSU / Magisk / KernelSU)",
                    description = "Instant background termination via 'am force-stop' and kernel appops component cutoff. Requires root access.",
                    isSelected = currentMode == OperatingMode.ROOT,
                    onClick = { onSelectMode(OperatingMode.ROOT) }
                )
                ModeSelectionItem(
                    title = "Non-Root Mode (Accessibility Automation)",
                    description = "Safe automated force stop using Android Accessibility Service. Works on standard unrooted devices.",
                    isSelected = currentMode == OperatingMode.NON_ROOT,
                    onClick = { onSelectMode(OperatingMode.NON_ROOT) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ModeSelectionItem(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
    }
}
