package io.github.gwenvador.pushbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.json.JSONObject

private fun listenerEnabled(c: Context): Boolean {
    val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
    val me = ComponentName(c, ForwarderService::class.java)
    return flat.contains(me.flattenToString()) || flat.contains(me.flattenToShortString())
}

@Composable
fun SettingsScreen(vm: AppViewModel, padding: PaddingValues, openApps: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    // Re-read system state when coming back from the system settings.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val access = remember(tick) { listenerEnabled(ctx) }
    val unrestricted = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    var forwarding by remember { mutableStateOf(prefs.forwarding()) }
    val muted = remember(tick) { prefs.blocked().size }
    var confirmSignOut by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Section("Notification forwarding") }
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = if (access) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    ListItem(
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        leadingContent = { Icon(if (access) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null) },
                        headlineContent = { Text(if (access) "Notification access granted" else "Notification access needed") },
                        supportingContent = {
                            Text(
                                if (access) "Your notifications show up on your other devices."
                                else "Allow Pushbox in the list that opens." +
                                    if (Build.VERSION.SDK_INT >= 33) " If the switch is greyed out, open App info › ⋮ › Allow restricted settings first." else "",
                            )
                        },
                    )
                    if (!access) {
                        FilledTonalButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("Grant access") }
                        if (Build.VERSION.SDK_INT >= 33) TextButton(
                            onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open App info") }
                    }
                }
            }
        }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Notifications, null) },
                headlineContent = { Text("Forward notifications") },
                supportingContent = { Text("Show this phone's notifications on your computers") },
                trailingContent = { Switch(forwarding, { forwarding = it; prefs.forwarding(it) }) },
                modifier = Modifier.clickable { forwarding = !forwarding; prefs.forwarding(forwarding) },
            )
        }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Apps, null) },
                headlineContent = { Text("Apps to forward") },
                supportingContent = { Text(if (muted == 0) "All apps" else "$muted app${if (muted > 1) "s" else ""} muted") },
                modifier = Modifier.clickable(onClick = openApps),
            )
        }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Send, null) },
                headlineContent = { Text("Send a test notification") },
                modifier = Modifier.clickable {
                    Api.IO.execute {
                        runCatching {
                            Api.call(prefs.server(), prefs.token(), "POST", "/api/mirror", JSONObject()
                                .put("key", "pushbox-test-${System.currentTimeMillis()}").put("package", ctx.packageName)
                                .put("app", "Pushbox").put("title", "Test notification").put("text", "Forwarding from ${prefs.deviceName()} works."))
                        }.onSuccess { vm.say("Sent. Check your computer.") }.onFailure { vm.say("Failed: ${it.message}") }
                    }
                },
            )
        }

        item { Section("Background") }
        item {
            ListItem(
                leadingContent = { Icon(if (unrestricted) Icons.Rounded.BatteryFull else Icons.Rounded.BatteryAlert, null) },
                headlineContent = { Text(if (unrestricted) "Runs in the background" else "Battery optimisation is on") },
                supportingContent = { Text(if (unrestricted) "Pushes arrive even while the phone sleeps." else "Tap to let pushes arrive while the phone sleeps.") },
                modifier = Modifier.clickable(enabled = !unrestricted) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                },
            )
        }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Notifications, null) },
                headlineContent = { Text("Notification categories") },
                supportingContent = { Text("Hide the \"connected\" notification, change sounds") },
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                },
            )
        }

        item { Section("Account") }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Language, null) },
                headlineContent = { Text("Open the website") },
                supportingContent = { Text("Already signed in, as this phone") },
                modifier = Modifier.clickable { vm.openWebsite(ctx) },
            )
        }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Cloud, null) },
                headlineContent = { Text(vm.deviceName) },
                supportingContent = { Text(vm.server.removePrefix("https://")) },
            )
        }
        item {
            ListItem(
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error) },
                headlineContent = { Text("Sign out", color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { confirmSignOut = true },
            )
        }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Info, null) },
                headlineContent = { Text("Pushbox ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}") },
                supportingContent = { Text("Self-hosted. Direct transfers on your local network and Tailscale.") },
            )
        }
    }

    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out?") },
        text = { Text("This phone stops receiving pushes and forwarding notifications.") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

private class AppEntry(val pkg: String, val label: String)

@Composable
fun AppsScreen(padding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var blocked by remember { mutableStateOf(prefs.blocked()) }
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
    val shown = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }

    LazyColumn(Modifier.fillMaxSize().padding(padding)) {
        item {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        items(shown, key = { it.pkg }) { a ->
            val on = a.pkg !in blocked
            fun toggle() {
                blocked = if (on) blocked + a.pkg else blocked - a.pkg
                prefs.blocked(blocked)
            }
            ListItem(
                leadingContent = { AppIcon(a.pkg) },
                headlineContent = { Text(a.label) },
                trailingContent = { Switch(on, { toggle() }) },
                modifier = Modifier.clickable { toggle() },
            )
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, Modifier.size(40.dp))
}
