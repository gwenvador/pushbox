package io.github.gwenvador.pushbox

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Saves a file to Download/Pushbox if needed, then opens it in the matching app.
 * An activity (not a broadcast) so notification buttons may open the file on Android 12+.
 */
class OpenFileActivity : ComponentActivity() {
    companion object {
        fun forPush(c: Context, push: JSONObject): Intent =
            Intent(c, OpenFileActivity::class.java).putExtra("push", push.toString())

        fun forInbox(c: Context, id: String): Intent =
            Intent(c, OpenFileActivity::class.java).putExtra("inbox", id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val push = intent.getStringExtra("push")?.let(::JSONObject)
        val inboxId = intent.getStringExtra("inbox")
        val name = push?.optString("file_name") ?: inboxId?.let { Inbox.get(this, it)?.name } ?: "file"

        setContent {
            PushboxTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                        Column(Modifier.padding(24.dp)) {
                            Text("Downloading", style = MaterialTheme.typography.titleMedium)
                            Text(name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            try {
                val (uri, mime) = withContext(Dispatchers.IO) {
                    when {
                        inboxId != null -> {
                            val it = Inbox.get(this@OpenFileActivity, inboxId) ?: error("File no longer available")
                            Inbox.saveToDownloads(this@OpenFileActivity, inboxId) to it.mime
                        }
                        push != null -> {
                            val prefs = Prefs(this@OpenFileActivity)
                            val id = push.optString("id")
                            val known = prefs.savedUri(id)?.let(Uri::parse)?.takeIf(::exists)
                            (known ?: Files.fetchToDownloads(this@OpenFileActivity, push)) to push.optString("file_type", "application/octet-stream")
                        }
                        else -> error("Nothing to open")
                    }
                }
                open(uri, mime, name)
            } catch (e: Exception) {
                Toast.makeText(this@OpenFileActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun exists(uri: Uri) = runCatching { contentResolver.openInputStream(uri)?.close(); true }.getOrDefault(false)

    private fun open(uri: Uri, mime: String, name: String) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "$name saved in Download/Pushbox (no app opens this type)", Toast.LENGTH_LONG).show()
        }
    }
}
