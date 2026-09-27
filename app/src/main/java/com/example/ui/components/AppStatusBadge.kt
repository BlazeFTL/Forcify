package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppState
import com.example.ui.theme.StateEvading
import com.example.ui.theme.StateEvadingBg
import com.example.ui.theme.StateForeground
import com.example.ui.theme.StateForegroundBg
import com.example.ui.theme.StateFree
import com.example.ui.theme.StateFreeBg
import com.example.ui.theme.StateWorking
import com.example.ui.theme.StateWorkingBg

@Composable
fun AppStatusBadge(
    state: AppState,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, borderColor) = when (state) {
        AppState.FOREGROUND -> Triple(StateForegroundBg, StateForeground, StateForeground.copy(alpha = 0.3f))
        AppState.WORKING_STATE -> Triple(StateWorkingBg, StateWorking, StateWorking.copy(alpha = 0.3f))
        AppState.BACKGROUND_RUNNING -> Triple(Color(0xFFEFF6FF), Color(0xFF2563EB), Color(0xFFBFDBFE))
        AppState.EVADING_RESTRICTIONS -> Triple(StateEvadingBg, StateEvading, StateEvading.copy(alpha = 0.4f))
        AppState.BACKGROUND_FREE -> Triple(StateFreeBg, StateFree, StateFree.copy(alpha = 0.3f))
        AppState.CACHED -> Triple(Color(0xFFF1F5F9), Color(0xFF475569), Color(0xFFCBD5E1))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                AppState.FOREGROUND -> {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(StateForeground)
                    )
                }
                AppState.WORKING_STATE -> {
                    Icon(
                        imageVector = Icons.Default.HourglassTop,
                        contentDescription = null,
                        tint = StateWorking,
                        modifier = Modifier.size(12.dp)
                    )
                }
                AppState.BACKGROUND_RUNNING -> {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2563EB))
                    )
                }
                AppState.EVADING_RESTRICTIONS -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = StateEvading,
                        modifier = Modifier.size(12.dp)
                    )
                }
                AppState.BACKGROUND_FREE -> {
                    Icon(
                        imageVector = Icons.Default.AcUnit,
                        contentDescription = null,
                        tint = StateFree,
                        modifier = Modifier.size(12.dp)
                    )
                }
                AppState.CACHED -> {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF94A3B8))
                    )
                }
            }
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = state.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}
