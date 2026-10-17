package io.github.gwenvador.pushbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushesScreen(vm: AppViewModel, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            if (vm.pushes.isEmpty() && vm.inbox.isEmpty() && !vm.refreshing) {
                EmptyFeed()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Direct files waiting for a decision, merged by date with the pushes.
                    val feed = (vm.pushes.map { it.createdAt to (it as Any) } + vm.inbox.map { it.at to (it as Any) }).sortedByDescending { it.first }
                    items(feed, key = { (_, v) -> if (v is Push) v.id else "inbox-" + (v as Inbox.Item).id }) { (_, v) ->
                        if (v is Push) SwipeablePush(vm, v) else InboxCard(vm, v as Inbox.Item)
                    }
                }
            }
        }
        if (vm.sending > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
        Composer(vm)
    }
}

@Composable
private fun EmptyFeed() {
    // Inside a scrollable so pull-to-refresh still works when empty.
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Icon(Icons.Rounded.Inbox, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.size(12.dp))
            Text("No pushes yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Send a note, a link or a file below,\nor share anything to Pushbox.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeablePush(vm: AppViewModel, p: Push) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) vm.delete(p) },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.onErrorContainer) }
        },
    ) { PushCard(vm, p) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PushCard(vm: AppViewModel, p: Push) {
    val ctx = LocalContext.current
    val mine = p.source == vm.myId
    fun copy(s: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pushbox", s))
        vm.say("Copied")
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            // Opaque: the swipe-to-delete layer sits right behind the card.
            containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f).compositeOver(MaterialTheme.colorScheme.surface)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = {
                when (p.type) {
                    "link" -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
                    "file" -> ctx.startActivity(OpenFileActivity.forPush(ctx, p.raw))
                    else -> p.body?.let(::copy)
                }
            },
            onLongClick = { (p.body ?: p.url)?.let(::copy) },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(vm.deviceKind(p.source), 30.dp, highlighted = mine)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(vm.deviceName(p.source), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "to " + (p.target?.let(vm::deviceName) ?: "all devices"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(relativeTime(p.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(10.dp))
            p.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }

            when (p.type) {
                "link" -> Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Link, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(Uri.parse(p.url).host ?: "", style = MaterialTheme.typography.labelLarge)
                            Text(p.url ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                "file" -> {
                    if (p.isImage && p.fileUrl != null) {
                        PushImage(p.id, p.fileUrl, Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(14.dp)))
                        Spacer(Modifier.size(8.dp))
                    }
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(p.fileName ?: "file", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                Text(Files.humanSize(p.fileSize), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            p.body?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = if (p.type == "note") 0.dp else 8.dp).animateContentSize())
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (p.type) {
                    "link" -> {
                        ActionChip("Open", Icons.Rounded.OpenInNew) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url))) }
                        ActionChip("Copy link", Icons.Rounded.ContentCopy) { copy(p.url!!) }
                    }
                    "file" -> if (p.id in vm.savedIds) ActionChip("Open", Icons.Rounded.OpenInNew) { ctx.startActivity(OpenFileActivity.forPush(ctx, p.raw)) }
                        else ActionChip("Download", Icons.Rounded.Download) { ctx.startActivity(OpenFileActivity.forPush(ctx, p.raw)) }
                }
                if (p.body != null) ActionChip("Copy", Icons.Rounded.ContentCopy) { copy(p.body) }
            }
        }
    }
}

@Composable
private fun InboxCard(vm: AppViewModel, it: Inbox.Item) {
    val ctx = LocalContext.current
    val saved = it.savedUri != null
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(it.from, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(if (saved) "sent directly · downloaded" else "sent directly · not downloaded yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Text(relativeTime(it.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.size(10.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(it.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(Files.humanSize(it.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saved) ActionChip("Open", Icons.Rounded.OpenInNew) { ctx.startActivity(OpenFileActivity.forInbox(ctx, it.id)) }
                else ActionChip("Download", Icons.Rounded.Download) { ctx.startActivity(OpenFileActivity.forInbox(ctx, it.id)) }
                ActionChip("Remove", Icons.Rounded.Delete) { vm.removeInbox(it) }
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

@Composable
private fun Composer(vm: AppViewModel) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("all") }
    val files = remember { mutableStateListOf<Uri>() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files.addAll(it) }

    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            TargetPicker(vm, target) { target = it }
            if (files.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    files.forEach { u ->
                        InputChip(
                            selected = false,
                            onClick = { files.remove(u) },
                            label = { Text(Files.meta(ctx, u).name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.width(140.dp)) },
                            leadingIcon = { Icon(Icons.Rounded.InsertDriveFile, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Icons.Rounded.Delete, "Remove", Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                IconButton(onClick = { pick.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.AttachFile, "Attach files") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Note or link") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        vm.send(text, files.toList(), target)
                        text = ""
                        files.clear()
                    },
                    enabled = text.isNotBlank() || files.isNotEmpty(),
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
            }
        }
    }
}

@Composable
private fun TargetPicker(vm: AppViewModel, target: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = vm.targets.firstOrNull { it.id == target }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(current?.let { kindIcon(it.kind) } ?: Icons.Rounded.Devices, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("To: " + (current?.name ?: "All devices"))
            if (current != null && current.id in vm.directIds) Icon(Icons.Rounded.Bolt, "Direct", Modifier.size(16.dp))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("All devices") },
                leadingIcon = { Icon(Icons.Rounded.Devices, null) },
                onClick = { onChange("all"); open = false },
            )
            vm.targets.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.name + if (d.id in vm.online) "" else "  · offline") },
                    leadingIcon = { Icon(kindIcon(d.kind), null) },
                    trailingIcon = { if (d.id in vm.directIds) Icon(Icons.Rounded.Bolt, "Direct transfer", tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onChange(d.id); open = false },
                )
            }
        }
    }
    if (target != "all" && current == null && vm.devices.isNotEmpty()) onChange("all")
}
