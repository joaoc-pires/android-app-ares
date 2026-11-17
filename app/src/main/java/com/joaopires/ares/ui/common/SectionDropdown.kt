// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.joaopires.ares.R
import com.joaopires.ares.core.data.model.Section

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionDropdown(
    sections: List<Section>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val noSection = stringResource(R.string.add_feed_no_section)
    val selected = sections.firstOrNull { it.id == selectedId }?.name ?: noSection
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && enabled }, modifier = modifier) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.add_feed_section)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(noSection) }, onClick = {
                onSelect(null)
                expanded = false
            })
            sections.forEach { section ->
                DropdownMenuItem(text = { Text(section.name) }, onClick = {
                    onSelect(section.id)
                    expanded = false
                })
            }
        }
    }
}
