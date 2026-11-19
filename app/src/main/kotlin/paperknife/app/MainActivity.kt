package paperknife.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** PaperKnife's screen. PDFs come from its Open button or from other apps (Open with). */
class MainActivity : ComponentActivity() {
    private val model: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handle(intent)
        }

        setContent {
            PaperKnifeTheme {
                FilePickers(model)
                BackHandler(enabled = model.busy || model.state.isModified) { leave() }
                PaperKnifeScreen(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && uri != null) {
            model.openFrom(uri)
        }
    }

    /** Leaves the screen, which closes the document, after asking when changes would be lost. */
    private fun leave() {
        lifecycleScope.launch {
            if (model.confirmLeave()) {
                finish()
            }
        }
    }
}

/** Starts the system's file picker whenever the editor waits for a file. */
@Composable
private fun FilePickers(model: EditorViewModel) {
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.completeFileRequest(it) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { model.completeFileRequest(it) }
    val request = model.fileRequest
    LaunchedEffect(request) {
        if (request != null && !model.fileRequestLaunched) {
            model.fileRequestLaunched = true
            try {
                when (request) {
                    is FileRequest.Open -> open.launch(arrayOf("application/pdf"))
                    is FileRequest.Create -> create.launch(request.suggestedName)
                }
            } catch (e: ActivityNotFoundException) {
                model.completeFileRequest(null)
            }
        }
    }
}

@Composable
private fun PaperKnifeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colors, content = content)
}
