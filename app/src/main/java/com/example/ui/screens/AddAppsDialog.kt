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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppSortOption
import com.example.model.AppState
import com.example.model.InstalledAppItem
import com.example.ui.components.AppIconImage
import com.example.ui.components.AppStatusBadge

private enum class AddAppFilter {
    ALL,
    RUNNING,
    EVADING
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAppsDialog(
    allApps: List<InstalledAppItem>,
    managedPackageNames: Set<String>,
    hideSystemApps: Boolean,
    onToggleHideSystemApps: (Boolean) -> Unit,
    sortOption: AppSortOption,
    onSelectSortOption: (AppSortOption) -> Unit,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirmAdd: (List<InstalledAppItem>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var currentFilter by remember { mutableStateOf(AddAppFilter.ALL) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    val selectedPackages = remember { mutableStateOf(mutableSetOf<String>()) }

    // Filter candidate apps (exclude already managed apps and apply system filter & sorting)
    val candidateApps = remember(allApps, managedPackageNames, searchQuery, currentFilter, hideSystemApps, sortOption) {
        val filtered = allApps.filter { !managedPackageNames.contains(it.packageName) }.filter { item ->
            // System app filter
            if (hideSystemApps && item.isSystemApp) return@filter false

            val matchesQuery = searchQuery.isBlank() ||
                item.appName.contains(searchQuery, ignoreCase = true) ||
                item.packageName.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (currentFilter) {
                AddAppFilter.ALL -> true
                AddAppFilter.RUNNING -> item.state == AppState.FOREGROUND ||
                    item.state == AppState.WORKING_STATE ||
                    item.state == AppState.EVADING_RESTRICTIONS
                AddAppFilter.EVADING -> item.state == AppState.EVADING_RESTRICTIONS
            }

            matchesQuery && matchesFilter
        }

        when (sortOption) {
            AppSortOption.NAME_ASC -> filtered.sortedBy { it.appName.lowercase() }
            AppSortOption.NAME_DESC -> filtered.sortedByDescending { it.appName.lowercase() }
            AppSortOption.INSTALL_TIME_DESC -> filtered.sortedByDescending { it.firstInstallTime }
            AppSortOption.INSTALL_TIME_ASC -> filtered.sortedBy { it.firstInstallTime }
            AppSortOption.SIZE_DESC -> filtered.sortedByDescending { it.appSize }
            AppSortOption.SIZE_ASC -> filtered.sortedBy { it.appSize }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // Header with title and Three-Dot Menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = "Add Apps to Freeze List",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${candidateApps.size} apps available • ${sortOption.label}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }

                    // Three-dot options menu positioned to the right side of X
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options and Sorting",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                            Text(
                                text = "SORT APPLICATIONS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            AppSortOption.values().forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            fontWeight = if (sortOption == option) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sortOption == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        showOptionsMenu = false
                                        onSelectSortOption(option)
                                    },
                                    leadingIcon = {
                                        if (sortOption == option) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.size(24.dp))
                                        }
                                    }
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            Text(
                                text = "FILTER",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (hideSystemApps) "Hide System Apps ✓" else "Hide System Apps",
                                        fontWeight = if (hideSystemApps) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    showOptionsMenu = false
                                    onToggleHideSystemApps(!hideSystemApps)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (hideSystemApps) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = if (hideSystemApps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Search Bar with "X" clear button requested by user!
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                placeholder = { Text("Search installed applications...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = if (searchQuery.isNotBlank()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search query",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            // Filter Chips & Quick Select Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = currentFilter == AddAppFilter.ALL,
                    onClick = { currentFilter = AddAppFilter.ALL },
                    label = { Text("All", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = currentFilter == AddAppFilter.RUNNING,
                    onClick = { currentFilter = AddAppFilter.RUNNING },
                    label = { Text("Running Now", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = currentFilter == AddAppFilter.EVADING,
                    onClick = { currentFilter = AddAppFilter.EVADING },
                    label = { Text("Evading", fontSize = 12.sp) }
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (selectedPackages.value.size == candidateApps.size && candidateApps.isNotEmpty()) "Deselect" else "Select All",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        if (selectedPackages.value.size == candidateApps.size) {
                            selectedPackages.value = mutableSetOf()
                        } else {
                            selectedPackages.value = candidateApps.map { it.packageName }.toMutableSet()
                        }
                    }
                )
            }

            // List of candidate apps
            val (unsafeCandidateApps, safeCandidateApps) = remember(candidateApps) {
                candidateApps.partition { it.isUnsafeToForceStop }
            }

            if (isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (candidateApps.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No apps match '$searchQuery'" else "All eligible apps are already added!",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Safe candidate apps
                    if (safeCandidateApps.isNotEmpty() && unsafeCandidateApps.isNotEmpty()) {
                        item {
                            Text(
                                text = "APPS TO FREEZE (${safeCandidateApps.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp)
                            )
                        }
                    }

                    items(safeCandidateApps, key = { it.packageName }) { app ->
                        val isSelected = selectedPackages.value.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    val current = selectedPackages.value.toMutableSet()
                                    if (isSelected) current.remove(app.packageName) else current.add(app.packageName)
                                    selectedPackages.value = current
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIconImage(drawable = app.icon, appName = app.appName, size = 42.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = app.appName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (app.isSystemApp) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(text = "System", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                Text(
                                    text = app.packageName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AppStatusBadge(state = app.state)
                                    if (app.wakeUpDetails.wakeupCount24h > 0) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${app.wakeUpDetails.wakeupCount24h} launches",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    val current = selectedPackages.value.toMutableSet()
                                    if (checked) current.add(app.packageName) else current.remove(app.packageName)
                                    selectedPackages.value = current
                                },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }

                    // NOT SAFE TO FORCE STOP SECTION AT BOTTOM REQUESTED BY USER (SS 6 - 8)
                    if (unsafeCandidateApps.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFFFFBEB)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFDE68A))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFD97706),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "NOT SAFE TO FORCE STOP (${unsafeCandidateApps.size})",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF92400E)
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "May cause notifications not appearing, typing/keyboard failures, or media/volume issues. You can still select and add them if desired.",
                                            fontSize = 11.sp,
                                            color = Color(0xFF78350F),
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        items(unsafeCandidateApps, key = { it.packageName }) { app ->
                            val isSelected = selectedPackages.value.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0xFFF59E0B) else Color(0xFFFDE68A),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        val current = selectedPackages.value.toMutableSet()
                                        if (isSelected) current.remove(app.packageName) else current.add(app.packageName)
                                        selectedPackages.value = current
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIconImage(drawable = app.icon, appName = app.appName, size = 42.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = app.appName,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFFFEF3C7))
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text(text = "Caution", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309))
                                        }
                                    }
                                    Text(
                                        text = app.packageName,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "⚠️ ${app.unsafeReason}",
                                        fontSize = 10.sp,
                                        color = Color(0xFFB45309),
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        lineHeight = 13.sp
                                    )
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        val current = selectedPackages.value.toMutableSet()
                                        if (checked) current.add(app.packageName) else current.remove(app.packageName)
                                        selectedPackages.value = current
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFFD97706),
                                        checkmarkColor = Color.White
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Add Action Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedPackages.value.size} apps selected",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Button(
                        onClick = {
                            val selectedApps = allApps.filter { selectedPackages.value.contains(it.packageName) }
                            onConfirmAdd(selectedApps)
                        },
                        enabled = selectedPackages.value.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "Add to Freeze List",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
