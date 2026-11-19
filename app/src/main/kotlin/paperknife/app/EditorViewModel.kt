package paperknife.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import paperknife.core.support.isDotNetWhiteSpace
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Severity { Informational, Success, Warning, Error }

/** The message bar at the top of the screen. */
data class Banner(val severity: Severity, val title: String, val message: String)

enum class DialogChoice { Primary, Secondary, Cancel }

/** A question the editor waits for the user to answer. */
sealed interface Question {
    class Choice(
        val title: String,
        val message: String,
        val primary: String,
        val secondary: String?,
        val destructive: Boolean,
        val answer: CompletableDeferred<DialogChoice>,
    ) : Question

    class Password(val fileName: String, val retry: Boolean, val answer: CompletableDeferred<String?>) : Question
}

/** A file the editor waits for the user to choose, with the system's file picker. */
sealed interface FileRequest {
    val result: CompletableDeferred<Uri?>

    class Open(override val result: CompletableDeferred<Uri?>) : FileRequest

    class Create(val suggestedName: String, override val result: CompletableDeferred<Uri?>) : FileRequest
}

/** A page as the screen shows it, in pixels. */
class DisplayedPage(val bitmap: PageBitmap)

private class Rendering(val bitmap: PageBitmap, val blockers: List<String>, val runs: List<TextRun>, val selection: PixelRect?)

/**
 * The editor behind PaperKnife's screen, with the commands, rules and messages of PaperKnife for
 * Windows' window. Engine calls run one at a time on the engine thread. While a command runs,
 * every control that could start another engine call is disabled, and the screen only reads this
 * model's copy of the session's state, taken when the last command finished. The model survives
 * rotating the screen; the document closes when the screen is left for good.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        val ZOOM_LEVELS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0)

        /** The most pixels a page is drawn with: Android draws bitmaps of up to 100 MB. */
        private const val MAX_DISPLAY_PIXELS = 12_000_000.0
    }

    private val engineThread = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "PaperKnife engine") }
    private val engine = engineThread.asCoroutineDispatcher()
    private val resolver get() = getApplication<Application>().contentResolver

    var editor by mutableStateOf<Editor?>(null)
        private set
    var documentName by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf(SessionState())
        private set
    var pageIndex by mutableIntStateOf(0)
        private set
    var zoomStep by mutableIntStateOf(2)
        private set
    var page by mutableStateOf<DisplayedPage?>(null)
        private set
    var runs by mutableStateOf<List<TextRun>>(emptyList())
        private set
    var pageBlockers by mutableStateOf<List<String>>(emptyList())
        private set
    var selected by mutableStateOf<TextRun?>(null)
        private set
    var editText by mutableStateOf("")

    /** The selected text's box, in the drawn page's pixels. */
    var selectionBox by mutableStateOf<PixelRect?>(null)
        private set
    var banner by mutableStateOf<Banner?>(null)
    var busy by mutableStateOf(false)
        private set
    var busyStatus by mutableStateOf("")
        private set

    /** Changes whenever the text field should take the keyboard focus. */
    var focusRequest by mutableIntStateOf(0)
        private set
    var question by mutableStateOf<Question?>(null)
        private set
    var fileRequest by mutableStateOf<FileRequest?>(null)
        private set

    /** Whether the screen has started the file picker for [fileRequest]; kept here so a rotation doesn't start it again. */
    var fileRequestLaunched = false

    private var viewportWidth = 0
    private var renderRequest = 0
    private var selectionVersion = 0
    private var signatureWarningAccepted = false

    // State the screen shows

    val isOpen get() = editor != null
    val canOpen get() = !busy
    val canUseDocument get() = isOpen && !busy
    val canUndo get() = canUseDocument && state.canUndo
    val canRedo get() = canUseDocument && state.canRedo
    val canZoomIn get() = canUseDocument && zoomStep < ZOOM_LEVELS.size - 1
    val canZoomOut get() = canUseDocument && zoomStep > 0
    private val isEditable get() = isOpen && selected != null && pageBlockers.isEmpty()

    /** Whether the text differs from the selected text, code unit for code unit, as C# compares it. */
    private val isChanged get() = isEditable && editText != selected?.text
    val canEditText get() = isEditable && !busy
    val canApply get() = isChanged && !busy
    val canReset get() = isChanged && !busy
    val canDeleteText get() = isEditable && !busy

    val editHint
        get() = if (selected == null) {
            "Tap any text on the page to select it."
        } else {
            "Change the text, then tap Apply. To remove it, tap Delete text."
        }

    val fontInfo get() = selected?.let(::describe) ?: ""

    val selectedRunIndex
        get() = selected?.let { selection ->
            runs.indexOfFirst { it.objectIndex == selection.objectIndex && it.pageIndex == selection.pageIndex }.takeIf { it >= 0 }
        }

    val statusText: String
        get() = when {
            busy -> busyStatus
            !isOpen -> "Your files stay on this device."
            else -> "Page ${pageIndex + 1} of ${state.pageCount} · ${(ZOOM_LEVELS[zoomStep] * 100).roundToInt()}%" +
                if (state.isModified) " · Unsaved changes" else ""
        }

    fun listText(run: TextRun): String {
        val text = if (run.text.length > 80) run.text.substring(0, 79) + "…" else run.text
        return if (run.isInvisible) "$text (invisible)" else text
    }

    // Commands

    fun openWithPicker() {
        viewModelScope.launch {
            if (busy) {
                return@launch
            }

            val uri = chooseFileToOpen() ?: return@launch
            open(uri)
        }
    }

    /** Opens a PDF another app handed over. */
    fun openFrom(uri: Uri) {
        viewModelScope.launch { open(uri) }
    }

    private suspend fun open(uri: Uri) {
        if (busy || !confirmDiscardChanges()) {
            return
        }

        val name = displayName(uri)
        var password: String? = null
        while (true) {
            setBusy(true, "Opening…")
            val opened: Editor? = try {
                val attempt = password
                withContext(engine) { Editor.open(readBytes(uri), attempt) }
            } catch (e: EditorException) {
                if (!e.passwordRequired) {
                    setBusy(false)
                    showMessage(Severity.Error, "Couldn't open the file", e.message)
                    return
                }

                // Ask for the password below, once the busy state is cleared.
                null
            } catch (e: OutOfMemoryError) {
                setBusy(false)
                showMessage(Severity.Error, "Couldn't open the file", "The file is too large to open.")
                return
            } catch (e: Exception) {
                setBusy(false)
                showMessage(Severity.Error, "Couldn't open the file", e.message ?: "The file couldn't be read.")
                return
            }

            setBusy(false)
            if (opened != null) {
                show(opened, name)
                return
            }

            password = askPassword(name, retry = password != null) ?: return
        }
    }

    private suspend fun show(opened: Editor, name: String) {
        closeDocument()
        editor = opened
        documentName = name
        pageIndex = 0
        signatureWarningAccepted = false
        refreshState()
        select(null)
        renderPage()
        val signed = state.signatureCount > 0
        showMessage(
            if (signed) Severity.Warning else Severity.Informational,
            name,
            if (signed) "This PDF is digitally signed. Any edit will invalidate its signature." else "Tap any text on the page to edit it.",
        )
    }

    /** Closes the document without asking; the screen asks first. */
    private fun closeDocument() {
        val closing = editor
        editor = null
        documentName = null
        page = null
        runs = emptyList()
        pageBlockers = emptyList()
        selected = null
        selectionBox = null
        if (closing != null) {
            engineThread.execute { closing.close() }
        }
    }

    fun saveAsFromMenu() {
        viewModelScope.launch { saveAs() }
    }

    private suspend fun saveAs(): Boolean {
        val editor = editor ?: return false
        if (busy) {
            return false
        }

        val suggestedName = documentName?.let { "${it.substringBeforeLast('.')} (edited).pdf" } ?: "document.pdf"
        val uri = chooseSaveLocation(suggestedName) ?: return false
        val scratch = File(getApplication<Application>().cacheDir, "save-${UUID.randomUUID()}.pdf")
        setBusy(true, "Saving…")
        try {
            withContext(engine) { openForWriting(uri).use { editor.saveTo(it, scratch) } }
        } catch (e: Exception) {
            refreshState()
            setBusy(false)
            showMessage(Severity.Error, "Couldn't save", e.message ?: "The file couldn't be written.")
            return false
        }

        val name = displayName(uri)
        documentName = name
        refreshState()
        setBusy(false)
        showMessage(Severity.Success, "Saved", name)
        return true
    }

    fun changeHistory(undo: Boolean) {
        viewModelScope.launch {
            val editor = editor ?: return@launch
            if (busy || !(if (undo) state.canUndo else state.canRedo)) {
                return@launch
            }

            val selectedObject = selected?.objectIndex
            setBusy(true, if (undo) "Undoing…" else "Redoing…")
            withContext(engine) { if (undo) editor.undo() else editor.redo() }
            refreshState()
            setBusy(false)

            pageIndex = min(pageIndex, state.pageCount - 1)
            val index = pageIndex
            val reselected = selectedObject?.let { withContext(engine) { editor.textRun(index, it) } }
            select(reselected)
            banner = null
            renderPage()
        }
    }

    fun rotatePage() {
        val index = pageIndex
        runPageCommand("Rotating…") { it.rotatePage(index, 1) }
    }

    fun deletePage() {
        viewModelScope.launch {
            val index = pageIndex
            if (!isOpen || busy) {
                return@launch
            }

            val choice = ask("Delete page ${index + 1}?", "You can undo this until you close the document.", "Delete", destructive = true)
            if (choice == DialogChoice.Primary) {
                runPageCommand("Deleting page…") { it.deletePage(index) }
            }
        }
    }

    fun appendPages() {
        viewModelScope.launch {
            if (!isOpen || busy) {
                return@launch
            }

            val uri = chooseFileToOpen() ?: return@launch
            runPageCommand("Adding pages…") { it.appendDocument(readBytes(uri)) }
        }
    }

    private fun runPageCommand(status: String, command: (Editor) -> EditOutcome) {
        viewModelScope.launch {
            val editor = editor ?: return@launch
            if (busy) {
                return@launch
            }

            select(null)
            setBusy(true, status)
            val result = try {
                withContext(engine) { command(editor) }
            } catch (e: OutOfMemoryError) {
                refreshState()
                setBusy(false)
                showMessage(Severity.Error, "Nothing was changed", "The file is too large to add.")
                return@launch
            } catch (e: Exception) {
                refreshState()
                setBusy(false)
                showMessage(Severity.Error, "Nothing was changed", e.message ?: "The document couldn't be changed.")
                return@launch
            }

            refreshState()
            setBusy(false)
            showResult(result)
            pageIndex = min(pageIndex, state.pageCount - 1)
            renderPage()
        }
    }

    /** Replaces the selected text with the text field's text, or with nothing to delete it. */
    fun applyEdit(deleting: Boolean) {
        viewModelScope.launch {
            val editor = editor ?: return@launch
            val run = selected ?: return@launch
            if (busy || !(if (deleting) canDeleteText else canApply)) {
                return@launch
            }

            val newText = if (deleting) "" else editText
            if (state.signatureCount > 0 && !signatureWarningAccepted) {
                val choice = ask(
                    "Edit a signed PDF?",
                    "This PDF is digitally signed. Editing it will invalidate the signature.",
                    "Edit anyway",
                    destructive = true,
                )
                if (choice != DialogChoice.Primary) {
                    return@launch
                }

                signatureWarningAccepted = true
            }

            setBusy(true, if (newText.isDotNetWhiteSpace) "Deleting and verifying…" else "Applying and verifying…")
            val result = try {
                withContext(engine) { editor.replaceText(run.pageIndex, run.objectIndex, newText) }
            } catch (e: Exception) {
                refreshState()
                setBusy(false)
                showMessage(Severity.Error, "Nothing was changed", e.message ?: "The text couldn't be changed.")
                return@launch
            }

            refreshState()
            setBusy(false)
            showResult(result)
            if (result.status == EditOutcome.Status.Applied) {
                select(result.textRun)
                renderPage()
            }
        }
    }

    fun reset() {
        editText = selected?.text ?: ""
    }

    fun zoom(delta: Int) {
        viewModelScope.launch {
            val step = (zoomStep + delta).coerceIn(0, ZOOM_LEVELS.size - 1)
            if (!busy && step != zoomStep) {
                zoomStep = step
                renderPage()
            }
        }
    }

    fun selectPage(index: Int) {
        viewModelScope.launch {
            if (busy || !isOpen || index < 0 || index >= state.pageCount || index == pageIndex) {
                return@launch
            }

            pageIndex = index
            select(null)
            renderPage()
        }
    }

    fun selectRun(index: Int) {
        if (!busy && index in runs.indices) {
            select(runs[index])
        }
    }

    /** Selects the text under a point of the drawn page, in its pixels. */
    fun click(x: Int, y: Int) {
        viewModelScope.launch {
            val editor = editor ?: return@launch
            val shown = page ?: return@launch
            if (busy) {
                return@launch
            }

            val index = pageIndex
            val (width, height) = shown.bitmap.width to shown.bitmap.height
            val run = withContext(engine) { editor.hitTest(index, width, height, x, y) }
            if (editor === this@EditorViewModel.editor && index == pageIndex && !busy) {
                select(run, focusEditor = true)
            }
        }
    }

    /** The width the page is drawn for at 100%, in pixels; the page is drawn again when it changes. */
    fun setViewportWidth(width: Int) {
        if (width <= 0 || width == viewportWidth) {
            return
        }

        viewportWidth = width
        if (isOpen) {
            viewModelScope.launch { renderPage() }
        }
    }

    /**
     * Whether the screen may be left, which closes the document: nothing would be lost, or the
     * user agreed to lose it.
     */
    suspend fun confirmLeave(): Boolean {
        if (busy) {
            // Never trap the user: an engine call could take very long on a malformed file.
            return ask(
                "PaperKnife is still working",
                "Wait for the current action to finish, or quit now. Unsaved changes will be lost if you quit.",
                "Quit anyway",
                destructive = true,
            ) == DialogChoice.Primary
        }

        return confirmDiscardChanges()
    }

    /** Returns true when it's fine to drop the current document: saved, discarded or unmodified. */
    suspend fun confirmDiscardChanges(): Boolean {
        if (editor == null || !state.isModified) {
            return true
        }

        val name = documentName ?: "this document"
        return when (ask("Save changes to $name?", "Your changes will be lost if you don't save them.", "Save as…", "Don't save")) {
            DialogChoice.Primary -> saveAs()
            DialogChoice.Secondary -> true
            DialogChoice.Cancel -> false
        }
    }

    // Drawing and selection

    private suspend fun renderPage() {
        val editor = editor
        if (editor == null) {
            page = null
            return
        }

        // Until the screen is laid out; laying it out draws the page.
        val viewport = viewportWidth.takeIf { it > 0 } ?: return
        val index = pageIndex
        val selection = selected
        val version = selectionVersion
        val zoom = ZOOM_LEVELS[zoomStep]
        val request = ++renderRequest
        val rendering = try {
            withContext(engine) {
                val (pageWidth, pageHeight) = editor.pageSize(index)
                // At 100% the page is as wide as the screen.
                val pixelsPerPoint = zoom * viewport / pageWidth
                val scale = min(pixelsPerPoint, sqrt(MAX_DISPLAY_PIXELS / (pageWidth.toDouble() * pageHeight)))
                val width = max(1, Math.rint(pageWidth * scale).toInt())
                val height = max(1, Math.rint(pageHeight * scale).toInt())
                val runs = editor.textRuns(index).filter { !it.text.isDotNetWhiteSpace }
                val box = selection?.takeIf { it.pageIndex == index }?.let {
                    editor.mapToPixels(index, it.bounds.inflated(1f), width, height)
                }

                Rendering(editor.render(index, width, height), editor.editBlockers(index), runs, box)
            }
        } catch (e: OutOfMemoryError) {
            if (request == renderRequest && editor === this.editor && index == pageIndex) {
                showMessage(Severity.Error, "Couldn't draw this page", "The page is too large to draw at this zoom.")
            }

            return
        } catch (e: Exception) {
            // A page or document that went away while drawing isn't an error; a newer drawing follows.
            if (request == renderRequest && editor === this.editor && index == pageIndex) {
                showMessage(Severity.Error, "Couldn't draw this page", e.message ?: "")
            }

            return
        }

        if (request != renderRequest || editor !== this.editor || index != pageIndex) {
            return
        }

        page = DisplayedPage(rendering.bitmap)
        pageBlockers = rendering.blockers
        runs = rendering.runs
        if (version == selectionVersion) {
            selectionBox = rendering.selection
        } else {
            updateSelectionBox()
        }
    }

    private fun select(run: TextRun?, focusEditor: Boolean = false) {
        selected = run
        selectionVersion++
        editText = run?.text ?: ""
        updateSelectionBox()
        if (focusEditor && run != null && canEditText) {
            focusRequest++
        }
    }

    /** Maps the selection to pixels on the engine thread, then shows its box. */
    private fun updateSelectionBox() {
        val editor = editor
        val run = selected
        val shown = page
        if (editor == null || run == null || shown == null || run.pageIndex != pageIndex) {
            selectionBox = null
            return
        }

        val version = selectionVersion
        val index = pageIndex
        val (width, height) = shown.bitmap.width to shown.bitmap.height
        viewModelScope.launch {
            val rect = try {
                withContext(engine) { editor.mapToPixels(index, run.bounds.inflated(1f), width, height) }
            } catch (e: Exception) {
                null
            }

            if (rect != null && version == selectionVersion && editor === this@EditorViewModel.editor &&
                width == page?.bitmap?.width && height == page?.bitmap?.height
            ) {
                selectionBox = rect
            }
        }
    }

    private fun describe(run: TextRun): String {
        val embedding = when {
            run.isFontSubset -> "embedded subset"
            run.isFontEmbedded -> "embedded"
            else -> "not embedded"
        }

        val details = "Font: ${run.fontName} · ${formatSize(run.fontSize)} pt · $embedding"
        return if (run.isInvisible) "$details · invisible text layer (for example from OCR)" else details
    }

    /** A font size as .NET formats it with "0.#": at most one decimal, rounded half away from zero. */
    private fun formatSize(size: Float): String {
        val tenths = paperknife.core.support.DotNetMath.round(size.toDouble(), 1, awayFromZero = true)
        return if (tenths == Math.rint(tenths)) tenths.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", tenths)
    }

    // Messages and state

    private fun showResult(result: EditOutcome) {
        when (result.status) {
            EditOutcome.Status.Applied -> {
                val body = (result.warnings + result.details).joinToString(" ")
                showMessage(if (result.warnings.isEmpty()) Severity.Success else Severity.Warning, result.message, body.ifEmpty { null })
            }
            EditOutcome.Status.NoChange -> showMessage(Severity.Informational, result.message, null)
            EditOutcome.Status.Rejected -> showMessage(Severity.Error, "Nothing was changed", result.message)
        }
    }

    private fun showMessage(severity: Severity, title: String, message: String?) {
        banner = Banner(severity, title, message ?: "")
    }

    /** Copies the session's state. Call only when no command is running. */
    private suspend fun refreshState() {
        val current = editor
        state = withContext(engine) { current?.state ?: SessionState() }
    }

    private fun setBusy(value: Boolean, status: String = "") {
        busy = value
        if (value) {
            busyStatus = status
        }
    }

    // Questions and files

    private suspend fun ask(title: String, message: String, primary: String, secondary: String? = null, destructive: Boolean = false): DialogChoice {
        val answer = CompletableDeferred<DialogChoice>()
        question = Question.Choice(title, message, primary, secondary, destructive, answer)
        return try {
            answer.await()
        } finally {
            question = null
        }
    }

    private suspend fun askPassword(fileName: String, retry: Boolean): String? {
        val answer = CompletableDeferred<String?>()
        question = Question.Password(fileName, retry, answer)
        return try {
            answer.await()
        } finally {
            question = null
        }
    }

    fun answer(choice: DialogChoice) {
        (question as? Question.Choice)?.answer?.complete(choice)
    }

    fun answerPassword(password: String?) {
        (question as? Question.Password)?.answer?.complete(password)
    }

    private suspend fun chooseFileToOpen(): Uri? = requestFile(FileRequest.Open(CompletableDeferred()))

    private suspend fun chooseSaveLocation(suggestedName: String): Uri? = requestFile(FileRequest.Create(suggestedName, CompletableDeferred()))

    private suspend fun requestFile(request: FileRequest): Uri? {
        fileRequestLaunched = false
        fileRequest = request
        return try {
            request.result.await()
        } finally {
            fileRequest = null
            fileRequestLaunched = false
        }
    }

    /** The file picker's answer for [fileRequest]: the chosen file, or null when cancelled. */
    fun completeFileRequest(uri: Uri?) {
        fileRequest?.result?.complete(uri)
    }

    private fun readBytes(uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw EditorException("The file couldn't be read.")

    /** A stream that replaces the file's content, for providers that don't support truncating too. */
    private fun openForWriting(uri: Uri): OutputStream =
        try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        } ?: throw EditorException("The file couldn't be written.")

    private fun displayName(uri: Uri): String {
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }

        return name ?: uri.lastPathSegment ?: "Document"
    }

    override fun onCleared() {
        val closing = editor
        editor = null
        engineThread.execute { closing?.close() }
        engineThread.shutdown()
    }
}
