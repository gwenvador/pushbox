package io.github.gwenvador.pushbox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.viewModels

/** "Share → Pushbox": a bottom sheet to pick the device. Stays open while files transfer (URI grants end with it). */
class ShareActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val files = mutableListOf<Uri>()
    private var text: String? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs(this).token() == null) {
            Toast.makeText(this, "Open Pushbox and sign in first", Toast.LENGTH_LONG).show()
            finish(); return
        }
        PushService.start(this)
        read(intent)
        val summary = (files.map { "📎 " + Files.meta(this, it).name } + listOfNotNull(text)).joinToString("\n")

        setContent {
            PushboxTheme {
                val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                var sending by androidx.compose.runtime.remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { vm.refresh() }
                // Only one other device: there is no choice to make, send straight to it.
                var auto by androidx.compose.runtime.remember { mutableStateOf(false) }
                LaunchedEffect(vm.devices, vm.refreshing) {
                    val only = vm.targets.singleOrNull()
                    if (!auto && !vm.refreshing && only != null && (files.isNotEmpty() || text != null)) {
                        auto = true
                        sending = true
                        send(only.id)
                    }
                }
                ModalBottomSheet(onDismissRequest = { if (!sending) finish() }, sheetState = sheet) {
                    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                        Text("Send to", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
                        Text(
                            summary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
                        )
                        if (sending) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
                        } else {
                            Target("All devices", null, Icons.Rounded.Devices, false) { sending = true; send("all") }
                            vm.targets.forEach { d ->
                                Target(
                                    d.name, if (d.id in vm.online) "Online" else "Offline · gets it later",
                                    kindIcon(d.kind), d.id in vm.directIds && files.isNotEmpty(),
                                ) { sending = true; send(d.id) }
                            }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Target(name: String, sub: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, direct: Boolean, onClick: () -> Unit) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(icon, null) },
            headlineContent = { Text(name) },
            supportingContent = sub?.let { { Text(if (direct) "$it · direct transfer" else it) } },
            trailingContent = { if (direct) Icon(Icons.Rounded.Bolt, "Direct", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
        )
    }

    private fun read(i: Intent) {
        @Suppress("DEPRECATION")
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(files::add)
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(files::addAll)
        }
        var subject = i.getStringExtra(Intent.EXTRA_SUBJECT)
        val body = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        // Many apps send "Title" + "Title https://…": keep the longer one.
        if (subject != null && body != null && body.contains(subject)) subject = null
        text = listOfNotNull(subject, body).joinToString("\n").ifBlank { null }
    }

    private fun send(target: String) {
        var left = files.size + (if (text != null) 1 else 0)
        if (left == 0) { finish(); return }
        val results = mutableListOf<String>()
        val done = Sender.Callback { ok, how ->
            results += if (ok) how else "failed: $how"
            if (--left == 0) {
                val msg = when {
                    results.any { it.startsWith("failed") } -> results.joinToString(", ")
                    results.contains("sent directly") -> "Sent directly ⚡"
                    else -> "Sent"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        files.forEach { Sender.file(this, it, target, done) }
        text?.let { Sender.text(this, it, target, done) }
    }
}
