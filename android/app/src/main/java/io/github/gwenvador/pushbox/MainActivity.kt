package io.github.gwenvador.pushbox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PushService.channels(this)
        handleAuth(intent)
        setContent { PushboxTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuth(intent)
    }

    override fun onResume() {
        super.onResume()
        if (vm.signedIn) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            PushService.start(this)
        }
    }

    /** Back from Pocket ID: pushbox://auth?token=… (intent link) or #token=… */
    private fun handleAuth(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "pushbox") return
        setIntent(Intent(this, MainActivity::class.java))
        // Must be hierarchical (scheme://host?query) for getQueryParameter to work.
        val frag = Uri.parse("pushbox://auth?" + (data.encodedFragment ?: ""))
        val token = data.getQueryParameter("token") ?: frag.getQueryParameter("token")
        val error = data.getQueryParameter("login_error") ?: frag.getQueryParameter("login_error")
        when {
            error != null -> vm.say(error)
            token != null -> vm.completeSignIn(token)
        }
    }
}

private enum class Tab(val label: String) { Pushes("Pushes"), Devices("Devices"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    if (!vm.signedIn) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad -> SignIn(vm, Modifier.padding(pad)) }
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Pushes) }
    var apps by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { if (apps) IconButton(onClick = { apps = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    if (apps) Text("Apps to forward")
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (tab == Tab.Pushes) "Pushbox" else tab.label, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.size(10.dp))
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (vm.connected) Color(0xFF22C55E) else MaterialTheme.colorScheme.outlineVariant))
                    }
                },
            )
        },
        bottomBar = {
            if (!apps) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(when (t) { Tab.Pushes -> Icons.Rounded.Inbox; Tab.Devices -> Icons.Rounded.Devices; Tab.Settings -> Icons.Rounded.Settings }, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        when {
            apps -> AppsScreen(pad) { apps = false }
            tab == Tab.Pushes -> PushesScreen(vm, pad)
            tab == Tab.Devices -> DevicesScreen(vm, pad)
            else -> SettingsScreen(vm, pad) { apps = true }
        }
    }
}

@Composable
private fun SignIn(vm: AppViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    var server by rememberSaveable { mutableStateOf(vm.server) }
    var advanced by rememberSaveable { mutableStateOf(vm.server.isBlank()) }
    val logo = remember { ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)!!.toBitmap(256, 256).asImageBitmap() }

    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(logo, null, Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)))
        Spacer(Modifier.height(20.dp))
        Text("Pushbox", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Send notes, links and files between your devices, and see your phone's notifications on your computer.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                val s = server.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
                vm.setServer(s)
                val name = Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL
                val u = Uri.parse("$s/auth/login").buildUpon()
                    .appendQueryParameter("name", name)
                    .appendQueryParameter("kind", "phone")
                    .appendQueryParameter("return_to", "pushbox://auth")
                    .build()
                ctx.startActivity(Intent(Intent.ACTION_VIEW, u))
            },
            enabled = server.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Sign in with Pocket ID", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(8.dp))
        Text(
            "After signing in, tap \"Open Pushbox\" if the browser asks.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (advanced) {
            OutlinedTextField(server, { server = it }, label = { Text("Server") }, placeholder = { Text("push.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } else {
            TextButton(onClick = { advanced = true }) { Text("Server: ${server.removePrefix("https://")}") }
        }
    }
}
