package paperknife.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.yield
import kotlin.math.max

/** The width from which the screen shows pages, page and edit pane side by side, as on Windows. */
private val wideLayout = 840.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperKnifeScreen(model: EditorViewModel) {
    Scaffold(
        topBar = { TopBar(model) },
        bottomBar = { StatusBar(model) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            model.banner?.let { banner ->
                Notice(
                    banner.severity, banner.title, banner.message,
                    onClose = { model.banner = null },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            if (model.isOpen) {
                Workspace(model)
            } else {
                EmptyState(model)
            }
        }
    }

    Questions(model)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(model: EditorViewModel) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(model.documentName ?: "PaperKnife", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            IconButton(onClick = model::openWithPicker, enabled = model.canOpen) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = "Open")
            }
            IconButton(onClick = model::saveAsFromMenu, enabled = model.canUseDocument) {
                Icon(Icons.Outlined.Save, contentDescription = "Save as")
            }
            IconButton(onClick = { model.changeHistory(undo = true) }, enabled = model.canUndo) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = { model.changeHistory(undo = false) }, enabled = model.canRedo) {
                Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem("Rotate page", Icons.Outlined.Rotate90DegreesCw, model.canUseDocument) { menuOpen = false; model.rotatePage() }
                    MenuItem("Delete page", Icons.Outlined.Delete, model.canUseDocument) { menuOpen = false; model.deletePage() }
                    MenuItem("Add pages from PDF", Icons.Outlined.Add, model.canUseDocument) { menuOpen = false; model.appendPages() }
                    HorizontalDivider()
                    MenuItem("Zoom in", Icons.Outlined.ZoomIn, model.canZoomIn) { menuOpen = false; model.zoom(1) }
                    MenuItem("Zoom out", Icons.Outlined.ZoomOut, model.canZoomOut) { menuOpen = false; model.zoom(-1) }
                }
            }
        },
    )
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = { Icon(icon, contentDescription = null) },
    )
}

@Composable
private fun EmptyState(model: EditorViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PaperKnife", style = MaterialTheme.typography.displaySmall)
        Text(
            "Edit PDFs on this device. Your files are never uploaded.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 440.dp),
        )
        Button(onClick = model::openWithPicker, enabled = model.canOpen) {
            Text("Open a PDF")
        }
        Text("or open one from another app", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Workspace(model: EditorViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= wideLayout) {
            Row(Modifier.fillMaxSize()) {
                PageList(model, Modifier.width(140.dp).fillMaxHeight())
                VerticalDivider()
                PageView(model, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                Column(
                    Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Edit text", style = MaterialTheme.typography.titleLarge)
                    PageBlockers(model)
                    EditControls(model)
                    Text("Text on this page", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    RunList(model, Modifier.weight(1f))
                }
            }
        } else {
            var showRuns by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxSize()) {
                PageNavigator(model, onShowRuns = { showRuns = true })
                PageBlockers(model, Modifier.padding(horizontal = 12.dp))
                PageView(model, Modifier.weight(1f).fillMaxWidth())
                HorizontalDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EditControls(model)
                }
            }

            if (showRuns) {
                ModalBottomSheet(onDismissRequest = { showRuns = false }) {
                    Text(
                        "Text on this page",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    RunList(model, Modifier.fillMaxWidth().heightIn(max = 480.dp), onPick = { showRuns = false })
                }
            }
        }
    }
}

@Composable
private fun PageList(model: EditorViewModel, modifier: Modifier) {
    LazyColumn(modifier) {
        items(model.state.pageCount) { index ->
            val current = index == model.pageIndex
            Text(
                "Page ${index + 1}",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(enabled = model.canUseDocument) { model.selectPage(index) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun PageNavigator(model: EditorViewModel, onShowRuns: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { model.selectPage(model.pageIndex - 1) }, enabled = model.canUseDocument && model.pageIndex > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
        }
        Text("Page ${model.pageIndex + 1} of ${model.state.pageCount}")
        IconButton(onClick = { model.selectPage(model.pageIndex + 1) }, enabled = model.canUseDocument && model.pageIndex < model.state.pageCount - 1) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onShowRuns, enabled = model.canUseDocument) {
            Text("Text on this page")
        }
    }
}

@Composable
private fun PageBlockers(model: EditorViewModel, modifier: Modifier = Modifier) {
    if (model.pageBlockers.isNotEmpty()) {
        Notice(Severity.Warning, "Editing is off on this page", model.pageBlockers.joinToString(" "), modifier = modifier)
    }
}

/** The page, with the selected text's box over it. A tap selects the text under the finger. */
@Composable
private fun PageView(model: EditorViewModel, modifier: Modifier) {
    val density = LocalDensity.current
    val margin = 16.dp
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val available = maxWidth
        val viewport = with(density) { (available - margin * 2).roundToPx() }
        LaunchedEffect(viewport) { model.setViewportWidth(viewport) }
        val shown = model.page ?: return@BoxWithConstraints
        val bitmap = shown.bitmap
        val image = remember(bitmap) { bitmap.bitmap.asImageBitmap() }
        val accent = MaterialTheme.colorScheme.primary
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            Box(Modifier.widthIn(min = available), contentAlignment = Alignment.TopCenter) {
                Box(
                    Modifier
                        .padding(margin)
                        .size(with(density) { bitmap.width.toDp() }, with(density) { bitmap.height.toDp() })
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        .pointerInput(bitmap) {
                            detectTapGestures { offset ->
                                model.click((offset.x * bitmap.width / size.width).toInt(), (offset.y * bitmap.height / size.height).toInt())
                            }
                        },
                ) {
                    Image(image, contentDescription = "Page", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    model.selectionBox?.let { box ->
                        Canvas(Modifier.fillMaxSize()) {
                            val scaleX = size.width / bitmap.width
                            val scaleY = size.height / bitmap.height
                            val topLeft = Offset(box.x * scaleX, box.y * scaleY)
                            val boxSize = Size(max(2f, box.width * scaleX), max(2f, box.height * scaleY))
                            val corner = CornerRadius(2.dp.toPx())
                            drawRoundRect(accent.copy(alpha = 0.13f), topLeft, boxSize, corner)
                            drawRoundRect(accent, topLeft, boxSize, corner, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditControls(model: EditorViewModel) {
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(model.editText)) }
    val value = if (field.text == model.editText) field else TextFieldValue(model.editText, TextRange(model.editText.length))
    LaunchedEffect(model.focusRequest) {
        if (model.focusRequest > 0) {
            // Select the whole text, after the field is enabled for the new selection.
            field = TextFieldValue(model.editText, TextRange(0, model.editText.length))
            yield()
            runCatching { focus.requestFocus() }
        }
    }

    Text(model.editHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = value,
        onValueChange = {
            field = it
            model.editText = it.text
        },
        label = { Text("Selected text") },
        enabled = model.canEditText,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { model.applyEdit(deleting = false) }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
    )
    if (model.fontInfo.isNotEmpty()) {
        Text(model.fontInfo, style = MaterialTheme.typography.bodySmall)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { model.applyEdit(deleting = false) }, enabled = model.canApply) {
            Text("Apply")
        }
        OutlinedButton(onClick = model::reset, enabled = model.canReset) {
            Text("Reset")
        }
        OutlinedButton(onClick = { model.applyEdit(deleting = true) }, enabled = model.canDeleteText) {
            Text("Delete text")
        }
    }

    Text(
        "PaperKnife changes only the selected text. After every edit it re-reads the saved result and compares the whole page; if anything else would change, the edit is cancelled.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RunList(model: EditorViewModel, modifier: Modifier, onPick: () -> Unit = {}) {
    val selectedIndex = model.selectedRunIndex
    LazyColumn(modifier) {
        itemsIndexed(model.runs) { index, run ->
            Text(
                model.listText(run),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (index == selectedIndex) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(enabled = model.canUseDocument) {
                        model.selectRun(index)
                        onPick()
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun StatusBar(model: EditorViewModel) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.navigationBarsPadding()) {
            if (model.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Text(
                model.statusText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** A message with its severity's color and symbol, closable when [onClose] is given. */
@Composable
private fun Notice(severity: Severity, title: String, message: String, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val (icon, color) = when (severity) {
        Severity.Informational -> Icons.Filled.Info to Color(0xFF1E5BB8)
        Severity.Success -> Icons.Filled.CheckCircle to Color(0xFF1B7F3A)
        Severity.Warning -> Icons.Filled.Warning to Color(0xFFB45F06)
        Severity.Error -> Icons.Filled.Error to Color(0xFFC0261D)
    }

    Row(
        modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (message.isNotEmpty()) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Close")
            }
        }
    }
}

@Composable
private fun Questions(model: EditorViewModel) {
    when (val question = model.question) {
        is Question.Choice -> AlertDialog(
            onDismissRequest = { model.answer(DialogChoice.Cancel) },
            title = { Text(question.title) },
            text = { Text(question.message) },
            confirmButton = {
                TextButton(onClick = { model.answer(DialogChoice.Primary) }) {
                    Text(question.primary, color = if (question.destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
                }
            },
            dismissButton = {
                Row {
                    question.secondary?.let { secondary ->
                        TextButton(onClick = { model.answer(DialogChoice.Secondary) }) {
                            Text(secondary)
                        }
                    }
                    TextButton(onClick = { model.answer(DialogChoice.Cancel) }) {
                        Text("Cancel")
                    }
                }
            },
        )
        is Question.Password -> {
            var password by remember(question) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { model.answerPassword(null) },
                title = { Text("Password required") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (question.retry) "That password didn't work. Try again." else "“${question.fileName}” is protected with a password.")
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { model.answerPassword(password) }),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { model.answerPassword(password) }) {
                        Text("Open")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { model.answerPassword(null) }) {
                        Text("Cancel")
                    }
                },
            )
        }
        null -> {}
    }
}
