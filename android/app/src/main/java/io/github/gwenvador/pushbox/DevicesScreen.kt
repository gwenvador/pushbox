package io.github.gwenvador.pushbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DevicesScreen(vm: AppViewModel, padding: PaddingValues) {
    var renaming by remember { mutableStateOf<Device?>(null) }
    var removing by remember { mutableStateOf<Device?>(null) }
    val sorted = vm.devices.sortedWith(compareByDescending<Device> { it.id == vm.myId }.thenByDescending { it.id in vm.online }.thenByDescending { it.lastSeen })

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(sorted, key = { it.id }) { d ->
            val me = d.id == vm.myId
            val on = d.id in vm.online
            var menu by remember { mutableStateOf(false) }
            ListItem(
                leadingContent = {
                    Box {
                        DeviceAvatar(d.kind, 44.dp, highlighted = me)
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(12.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp)
                                .background(if (on) androidx.compose.ui.graphics.Color(0xFF22C55E) else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                    }
                },
                headlineContent = { Text(d.name + if (me) "  (this phone)" else "") },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            when (d.kind) { "phone" -> "Phone"; "desktop" -> "Computer"; "api" -> "API token"; else -> "Browser" },
                            if (on) "online" else "seen ${relativeTime(d.lastSeen)}",
                        ).joinToString(" · "),
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (d.id in vm.directIds && !me) SuggestionChip(onClick = {}, label = { Text("Direct") }, icon = { Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp)) })
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; renaming = d })
                                if (!me) DropdownMenuItem(
                                    text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; removing = d },
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    renaming?.let { d ->
        var name by remember(d.id) { mutableStateOf(d.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameDevice(d, name.trim()); renaming = null }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    removing?.let { d ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${d.name}?") },
            text = { Text("It will be signed out and stop receiving pushes.") },
            confirmButton = { TextButton(onClick = { vm.removeDevice(d); removing = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}
