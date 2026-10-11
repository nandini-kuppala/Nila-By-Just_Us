package com.nila.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.ui.components.NilaIcons

/**
 * The Health tab: the family's records and the doctor's visits, one tab with
 * two pages, so the bottom bar keeps five destinations with Little Signs added.
 *
 * The switch is a slim underlined strip rather than pills: the Records page
 * already opens with Baby / Mother pills, and two rows of pills would read as
 * one control.
 */
@Composable
fun HealthHub(
    records: @Composable () -> Unit,
    visits: @Composable () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        val scheme = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HubTab("Records", Icons.Outlined.FolderShared, page == 0) { page = 0 }
            HubTab("Doctor visits", NilaIcons.Stethoscope, page == 1) { page = 1 }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
        Box(Modifier.weight(1f).padding(top = 12.dp)) {
            if (page == 0) records() else visits()
        }
    }
}

@Composable
private fun RowScope.HubTab(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (selected) scheme.primary else scheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.Tab },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = ink,
            )
        }
        Box(
            Modifier
                .fillMaxWidth(0.7f)
                .height(3.dp)
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .background(if (selected) scheme.primary else androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}
