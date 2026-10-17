package io.github.gwenvador.pushbox

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Screen state on top of the Java service layer (Hub, Api, Sender). */
class AppViewModel(app: Application) : AndroidViewModel(app), Hub.Listener {
    private val prefs = Prefs(app)

    var signedIn by mutableStateOf(prefs.token() != null); private set
    var deviceName by mutableStateOf(prefs.deviceName() ?: ""); private set
    var connected by mutableStateOf(Hub.connected); private set
    var refreshing by mutableStateOf(false); private set
    var devices by mutableStateOf<List<Device>>(emptyList()); private set
    var online by mutableStateOf<Set<String>>(emptySet()); private set
    var myId by mutableStateOf(Hub.myId); private set
    var directIds by mutableStateOf<Set<String>>(emptySet()); private set
    var sending by mutableStateOf(0); private set
    val pushes = mutableStateListOf<Push>()
    /** Direct files waiting for the user to download them. */
    var inbox by mutableStateOf<List<Inbox.Item>>(emptyList()); private set

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val server get() = prefs.server()

    init {
        Hub.add(this)
        syncDevices()
        loadInbox()
        if (signedIn) refresh()
    }

    override fun onCleared() {
        Hub.remove(this)
    }

    fun say(msg: String) { _messages.trySend(msg) }

    fun deviceName(id: String?): String = when {
        id == null -> "API"
        else -> devices.firstOrNull { it.id == id }?.name ?: "Removed device"
    }

    fun deviceKind(id: String?): String = devices.firstOrNull { it.id == id }?.kind ?: "api"

    /** Devices a push can be sent to. */
    val targets get() = devices.filter { it.id != myId && it.kind != "api" }

    // ---------- auth ----------
    fun completeSignIn(token: String) {
        viewModelScope.launch {
            try {
                val me = io { Api.call(prefs.server(), token, "GET", "/api/me", null) }.getJSONObject("device")
                prefs.signIn(token, me.getString("name"))
                Hub.myId = me.getString("id")
                myId = Hub.myId
                deviceName = me.getString("name")
                signedIn = true
                PushService.start(getApplication())
                refresh()
                say("Signed in as $deviceName")
            } catch (e: Exception) {
                say("Sign-in failed: ${e.message}")
            }
        }
    }

    fun signOut() {
        val server = prefs.server(); val token = prefs.token()
        viewModelScope.launch(Dispatchers.IO) { runCatching { Api.call(server, token, "POST", "/api/logout", null) } }
        prefs.signOut()
        PushService.stop(getApplication())
        pushes.clear()
        signedIn = false
    }

    fun setServer(url: String) = prefs.server(url)

    /** Open the website in the browser, signed in with this app's session (single-use link). */
    fun openWebsite(ctx: android.content.Context) = viewModelScope.launch {
        val url = runCatching { io { call("POST", "/api/handoff").getString("url") } }
            .getOrElse { say("Could not open the website: ${it.message}"); return@launch }
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(prefs.server() + url)))
    }

    // ---------- data ----------
    fun refresh() {
        if (!signedIn) return
        refreshing = true
        viewModelScope.launch {
            try {
                val (list, devs, me) = io {
                    Triple(
                        call("GET", "/api/pushes?limit=80").getJSONArray("pushes"),
                        call("GET", "/api/devices"),
                        call("GET", "/api/me").getJSONObject("device"),
                    )
                }
                Hub.myId = me.getString("id")
                Hub.devices = devs.getJSONArray("devices")
                devs.optJSONObject("direct")?.let { Hub.direct = it }
                val on = HashSet<String>()
                devs.optJSONArray("online")?.let { for (i in 0 until it.length()) on.add(it.optString(i)) }
                Hub.online = on
                deviceName = me.getString("name")
                syncDevices()
                pushes.clear()
                for (i in 0 until list.length()) pushes.add(Push.from(list.getJSONObject(i)))
            } catch (e: Api.HttpError) {
                if (e.status == 401) { prefs.signOut(); signedIn = false }
                else say(e.message ?: "Error")
            } catch (e: Exception) {
                say("Offline: ${e.message}")
            } finally {
                refreshing = false
            }
        }
    }

    fun delete(p: Push) {
        pushes.remove(p)
        viewModelScope.launch {
            runCatching { io { call("DELETE", "/api/pushes/${p.id}") } }.onFailure { say("Delete failed") }
        }
    }

    fun renameDevice(d: Device, name: String) = viewModelScope.launch {
        runCatching { io { call("PATCH", "/api/devices/${d.id}", JSONObject().put("name", name)) } }
            .onSuccess { if (d.id == myId) { deviceName = name; prefs.signIn(prefs.token(), name) } }
            .onFailure { say("Rename failed") }
    }

    fun removeDevice(d: Device) = viewModelScope.launch {
        runCatching { io { call("DELETE", "/api/devices/${d.id}") } }
            .onSuccess { say("${d.name} removed") }
            .onFailure { say("Remove failed") }
    }

    fun send(text: String, files: List<Uri>, target: String) {
        val app = getApplication<Application>()
        for (u in files) {
            sending++
            Sender.file(app, u, target) { ok, how ->
                sending--
                say(if (ok) "${Files.meta(app, u).name} $how" else "Failed: $how")
            }
        }
        if (text.isNotBlank()) {
            sending++
            Sender.text(app, text.trim(), target) { ok, how ->
                sending--
                if (!ok) say("Failed: $how")
            }
        }
    }

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject =
        Api.call(prefs.server(), prefs.token(), method, path, body)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun syncDevices() {
        val arr = Hub.devices
        devices = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Device::from) }
        online = Hub.online
        myId = Hub.myId
        directIds = devices.map { it.id }.filter { Hub.canDirect(it) }.toSet()
    }

    // ---------- Hub.Listener (main thread) ----------
    override fun onConnection(connected: Boolean) { this.connected = connected }
    override fun onDevices() = syncDevices()
    override fun onPush(push: JSONObject) {
        val p = Push.from(push)
        if (pushes.none { it.id == p.id }) pushes.add(0, p)
    }
    override fun onDelete(id: String) { pushes.removeAll { it.id == id } }
    override fun onClear() = pushes.clear()
    override fun onInbox() = loadInbox()

    /** Push ids already saved to Download/Pushbox (their button becomes "Open"). */
    var savedIds by mutableStateOf<Set<String>>(emptySet()); private set

    private fun loadInbox() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            inbox = io { Inbox.list(app) }
            savedIds = io { Prefs(app).savedIds() }
        }
    }

    fun removeInbox(item: Inbox.Item) = viewModelScope.launch {
        val app = getApplication<Application>()
        io { Inbox.remove(app, item.id) }
    }
}
