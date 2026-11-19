package paperknife.core

import com.sun.jna.Pointer
import paperknife.core.content.ContentPage
import paperknife.core.editing.ContentEditOutcome
import paperknife.core.editing.ContentEditResult
import paperknife.core.editing.ContentTextEdit
import paperknife.core.editing.EditVerifier
import paperknife.core.editing.RunChange
import paperknife.core.editing.TextEquivalence
import paperknife.core.editing.TextRunEdit
import paperknife.core.editing.TextSupport
import paperknife.core.fonts.Type1Font
import paperknife.core.pdfium.PdfiumLibrary
import paperknife.core.pdfium.PdfiumNative.FPDFFont_GetFlags
import paperknife.core.pdfium.PdfiumNative.FPDFFont_GetFontData
import paperknife.core.pdfium.SizeT
import paperknife.core.pdfium.SizeTByReference
import paperknife.core.support.Ref
import paperknife.core.support.dotNetTrim
import paperknife.core.support.dotNetTrimEnd
import paperknife.core.syntax.PdfFile
import paperknife.core.syntax.PdfPageNode
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID
import java.util.zip.DataFormatException
import kotlin.math.abs

/**
 * An open document and its edit history. Every change is a transaction: it is applied to a scratch
 * copy, serialized, reloaded and verified, and only then committed. A rejected change leaves the
 * document exactly as it was. Members are safe to call from any thread; calls are serialized.
 * [close] is C#'s `Dispose`.
 *
 * [saveTo] is an addition of PaperKnife for Android, which PaperKnife for Windows doesn't have:
 * the app saves through content URIs, where [saveAs]'s temporary file and rename aren't possible.
 */
class DocumentSession private constructor(snapshot: PdfSnapshot, private val password: String?, filePath: String?) : AutoCloseable {
    // C#'s LinkedList (oldest first) and Stack (the top is the last element).
    private val undoHistory = ArrayDeque<PdfSnapshot>()
    private val redoHistory = ArrayDeque<PdfSnapshot>()
    private val contentPages = HashMap<Int, ContentPage?>()
    private var document: PdfDocument = PdfDocument.load(snapshot, password)
    private var current: PdfSnapshot = snapshot
    private var saved: PdfSnapshot = snapshot
    private var contentSnapshot: PdfSnapshot? = null
    private var contentFile: PdfFile? = null
    private var contentPageTree: List<PdfPageNode>? = null
    private var disposed = false

    /** The file this session was opened from or last saved to, if any. */
    var filePath: String? = filePath
        private set

    /** Increments on every committed change, undo and redo. */
    var version: Int = 0
        private set

    /** Approximate memory the undo history may use. The newest undo step is always kept. */
    var historyLimitBytes: Long = 512L * 1024 * 1024

    /** Whether text edits may be made in the content stream; tests turn it off to exercise PDFium's page rewrite. */
    internal var useContentEditing: Boolean = true

    val isModified: Boolean
        get() = synchronized(PdfiumLibrary.sync) {
            current !== saved
        }

    val canUndo: Boolean
        get() = synchronized(PdfiumLibrary.sync) {
            undoHistory.size > 0
        }

    val canRedo: Boolean
        get() = synchronized(PdfiumLibrary.sync) {
            redoHistory.size > 0
        }

    /** The verified, serialized state of the document. Saving writes exactly these bytes. */
    val currentSnapshot: PdfSnapshot
        get() = synchronized(PdfiumLibrary.sync) {
            current
        }

    val pageCount: Int
        get() = synchronized(PdfiumLibrary.sync) {
            throwIfDisposed()
            document.pageCount
        }

    val signatureCount: Int
        get() = synchronized(PdfiumLibrary.sync) {
            throwIfDisposed()
            document.signatureCount
        }

    fun getPageSize(pageIndex: Int): PageSize {
        synchronized(PdfiumLibrary.sync) {
            val page = getPage(pageIndex)
            return PageSize(page.width, page.height)
        }
    }

    fun renderPage(pageIndex: Int, pixelWidth: Int, pixelHeight: Int): RenderedBitmap {
        synchronized(PdfiumLibrary.sync) {
            return getPage(pageIndex).render(pixelWidth, pixelHeight)
        }
    }

    fun getTextRuns(pageIndex: Int): List<PdfTextRun> {
        synchronized(PdfiumLibrary.sync) {
            return getPage(pageIndex).getTextRuns()
        }
    }

    fun getTextRun(pageIndex: Int, objectIndex: Int): PdfTextRun? {
        synchronized(PdfiumLibrary.sync) {
            throwIfDisposed()
            return if (isValidPage(pageIndex)) document.getPage(pageIndex).tryGetTextRun(objectIndex) else null
        }
    }

    fun hitTestText(pageIndex: Int, pixelWidth: Int, pixelHeight: Int, x: Int, y: Int): PdfTextRun? {
        synchronized(PdfiumLibrary.sync) {
            return getPage(pageIndex).hitTestText(pixelWidth, pixelHeight, x, y)
        }
    }

    fun mapToPixels(pageIndex: Int, rect: PdfRect, pixelWidth: Int, pixelHeight: Int): PixelRect {
        synchronized(PdfiumLibrary.sync) {
            return getPage(pageIndex).mapToPixels(rect, pixelWidth, pixelHeight)
        }
    }

    /** Reasons text on this page can't be edited yet; empty when editing is possible. */
    fun getTextEditBlockers(pageIndex: Int): List<String> {
        synchronized(PdfiumLibrary.sync) {
            val page = getPage(pageIndex)
            val blockers = ArrayList<String>()
            if (!document.allowsContentChanges) {
                blockers.add("The author of this PDF restricted editing.")
            }

            // Content PDFium can't rewrite only matters when PDFium has to rewrite the page.
            val content = getContentPage(pageIndex)
            if (content == null || content.text.objectShows.size != page.countObjects().text) {
                blockers.addAll(findContentBlockers(page))
            }

            return blockers
        }
    }

    /**
     * Replaces the text of a run, keeping its font, size, position and color. Only the text
     * objects whose characters change are rewritten; the rest of the run keeps its characters and
     * moves along the baseline to make room. Trailing whitespace is dropped: it is invisible, and
     * a character the font can't draw there would be impossible to detect. Empty text deletes the
     * whole run.
     *
     * When PaperKnife can read the page's content with certainty, only the text-showing operations
     * involved change, in the content stream itself (see [ContentTextEdit]); otherwise
     * PDFium regenerates the page.
     *
     * @param objectIndex Any page object of the run, normally [PdfTextRun.objectIndex].
     */
    fun replaceText(pageIndex: Int, objectIndex: Int, newText: String): EditResult {
        val text = newText.dotNetTrimEnd()
        synchronized(PdfiumLibrary.sync) {
            throwIfDisposed()
            if (!isValidPage(pageIndex)) {
                return EditResult.rejected(EditRejection.InvalidTarget, "That page no longer exists.")
            }

            val page = document.getPage(pageIndex)
            val (pagePieces, runs) = page.getTextLayout()
            val layout = runs.firstOrNull { r -> r.contains(objectIndex) }
                ?: return EditResult.rejected(EditRejection.InvalidTarget, "The selected text no longer exists. Select it again.")

            val original = layout.run
            val deletes = text.isEmpty()
            if (!deletes && TextEquivalence.areEquivalent(text, original.text)) {
                return EditResult.noChange("The text is unchanged.")
            }

            if (!document.allowsContentChanges) {
                return EditResult.rejected(EditRejection.EditingRestricted, "The author of this PDF restricted editing.")
            }

            val edit = if (deletes) {
                TextRunEdit.deleteAll(layout.pieces.size)
            } else {
                TextRunEdit.plan(layout.pieces.map { p -> p.text }, layout.separators, text)
            }
            val pageCount = document.pageCount
            val countsBefore = page.countObjects()
            val (width, height) = EditVerifier.checkSize(page.width, page.height)

            var candidate: PdfSnapshot? = null
            var change: RunChange? = null
            var addedFont: String? = null
            var glyphsToCheck: String? = null
            var copyFont: String? = null
            val content = getContentPage(pageIndex)
            if (content != null) {
                val prepared = try {
                    ContentTextEdit.prepare(content, pagePieces, layout, edit)
                } catch (ex: Exception) {
                    if (!isReadFailure(ex)) {
                        throw ex
                    }

                    ContentEditResult(ContentEditOutcome.NotApplicable, ex.message)
                }

                if (prepared.outcome == ContentEditOutcome.Rejected) {
                    return prepared.rejection!!
                }

                if (prepared.outcome == ContentEditOutcome.Ready) {
                    candidate = PdfSnapshot.fromBytes(prepared.candidate!!)
                    change = prepared.change!!
                    addedFont = prepared.addedFont
                    glyphsToCheck = prepared.glyphsToCheck
                    copyFont = prepared.copyFont
                }
            }

            if (candidate == null) {
                val rewritten = Ref<PdfSnapshot?>(null)
                val applied = Ref<RunChange?>(null)
                val failure = rewriteWithPdfium(pageIndex, page, pagePieces, layout, edit, width, height, rewritten, applied)
                if (failure != null) {
                    return failure
                }

                candidate = rewritten.value!!
                change = applied.value!!
            }

            val before = renderWithoutObjects(current, pageIndex, change!!.hiddenBefore, width, height)
            var verified: PdfDocument? = PdfDocument.load(candidate, password)
            try {
                val edited = Ref<PdfTextRun?>(null)
                val warnings = Ref<MutableList<String>>(ArrayList())
                val problem = verifyTextEdit(
                    verified!!, candidate, pageCount, change, countsBefore, before, pagePieces, glyphsToCheck, copyFont, edited, warnings,
                )
                if (problem != null) {
                    return EditResult.rejected(EditRejection.CollateralChange, problem)
                }

                commit(candidate, verified)
                verified = null
                val message = if (deletes) {
                    "Text deleted. Verified: nothing else on this page changed."
                } else if (change.movedRest) {
                    "Text updated, and the rest of the line moved to fit. Verified: nothing else on this page changed."
                } else {
                    "Text updated. Verified: nothing else on this page changed."
                }
                val details = if (addedFont == null) {
                    emptyList()
                } else {
                    listOf(
                        "This PDF contained only the letters it used from the font “$addedFont”, so PaperKnife added a complete copy of that font from its own fonts for the new text. The rest of the document still uses the PDF's own font.",
                    )
                }
                return EditResult.applied(message, warnings.value, edited.value, details)
            } finally {
                verified?.close()
            }
        }
    }

    /**
     * Makes an edit by having PDFium regenerate the page's content, for pages PaperKnife can't
     * edit in the content stream. Refuses pages whose content PDFium can't write back unchanged.
     *
     * @param pagePieces The page's text objects.
     */
    private fun rewriteWithPdfium(
        pageIndex: Int,
        page: PdfPage,
        pagePieces: List<TextPiece>,
        layout: TextRunLayout,
        edit: TextRunEdit,
        width: Int,
        height: Int,
        candidate: Ref<PdfSnapshot?>,
        change: Ref<RunChange?>,
    ): EditResult? {
        candidate.value = null
        change.value = null
        val blockers = findContentBlockers(page)
        if (blockers.isNotEmpty()) {
            return EditResult.rejected(EditRejection.PageNotEditable, blockers[0])
        }

        if (edit.last + 1 < layout.pieces.size && !layout.pieces[edit.last].isEndKnown) {
            return EditResult.rejected(
                EditRejection.PageNotEditable,
                "PaperKnife can't measure exactly where this part of the line ends, so it can't move the rest of the line to fit.",
            )
        }

        if (edit.newText.isNotEmpty()) {
            val support = TextSupport.check(layout.pieces[edit.first].font, layout.run.fontName, edit.newText)
            if (!support.isSupported) {
                return EditResult.rejected(EditRejection.UnsupportedCharacters, support.problem!!, support.characters)
            }
        }

        // PDFium's page rewrite encodes the edited object's whole text again, by the character map
        // or else its own reverse lookup. In a symbolic Type 1 font, as pdfTeX's are, the code it
        // picks may draw another glyph than the character, or carry the width of another one (see
        // ContentTextEdit), and PaperKnife can't check that here. Removing objects is safe.
        val piece = layout.pieces[edit.first]
        if (edit.newText.isNotEmpty() && piece.isEmbedded) {
            val flags = FPDFFont_GetFlags(piece.font)
            if (flags >= 0 && (flags and 4) != 0 && isType1Program(piece.font)) {
                return EditResult.rejected(
                    EditRejection.PageNotEditable,
                    "PaperKnife can change text in the font “${layout.run.fontName}” only by editing the page's drawing instructions directly, which it can't do on this page (for example because the PDF is encrypted). It can still delete the whole line.",
                )
            }
        }

        // Deleting needs no rewrite check: nothing of the deleted text remains, and the checks after
        // the change cover everything else on the page, invisible text included.
        if (!edit.deletesAll(layout.pieces.size)) {
            val runObjects = layout.pieces.map { p -> p.objectIndex }
            val rewriteProblem = checkRewriteKeepsAppearance(pageIndex, runObjects, page.render(width, height), pagePieces)
            if (rewriteProblem != null) {
                return EditResult.rejected(EditRejection.PageNotEditable, rewriteProblem)
            }
        }

        PdfDocument.load(current, password).use { scratch ->
            val applied = Ref<RunChange?>(null)
            val failure = applyRunEdit(scratch.getPage(pageIndex), layout, edit, applied)
            if (failure != null) {
                return failure
            }

            candidate.value = scratch.save()
            change.value = applied.value
            return null
        }
    }

    /** Rotates a page clockwise by the given number of quarter turns. */
    fun rotatePage(pageIndex: Int, quarterTurns: Int): EditResult {
        synchronized(PdfiumLibrary.sync) {
            throwIfDisposed()
            if (!isValidPage(pageIndex)) {
                return EditResult.rejected(EditRejection.InvalidTarget, "That page no longer exists.")
            }

            val turns = ((quarterTurns % 4) + 4) % 4
            if (turns == 0) {
                return EditResult.noChange("The page orientation is unchanged.")
            }

            val expected = (document.getPage(pageIndex).rotation + turns) % 4
            val pageCount = document.pageCount
            return applyPageChange(
                { scratch ->
                    scratch.rotatePage(pageIndex, turns)
                    true
                },
                { verified -> verified.pageCount == pageCount && verified.getPage(pageIndex).rotation == expected },
                "Page ${pageIndex + 1} rotated.",
            )
        }
    }

    fun deletePage(pageIndex: Int): EditResult {
        synchronized(PdfiumLibrary.sync) {
            throwIfDisposed()
            if (!isValidPage(pageIndex)) {
                return EditResult.rejected(EditRejection.InvalidTarget, "That page no longer exists.")
            }

            val pageCount = document.pageCount
            if (pageCount == 1) {
                return EditResult.rejected(EditRejection.InvalidTarget, "A PDF needs at least one page.")
            }

            return applyPageChange(
                { scratch ->
                    scratch.deletePage(pageIndex)
                    true
                },
                { verified -> verified.pageCount == pageCount - 1 },
                "Page ${pageIndex + 1} deleted.",
            )
        }
    }

    /** Moves a page so that it ends up at [toIndex]. */
    fun movePage(fromIndex: Int, toIndex: Int): EditResult {
        synchronized(PdfiumLibrary.sync) {
            throwIfDisposed()
            if (!isValidPage(fromIndex) || !isValidPage(toIndex)) {
                return EditResult.rejected(EditRejection.InvalidTarget, "That page no longer exists.")
            }

            if (fromIndex == toIndex) {
                return EditResult.noChange("The page is already there.")
            }

            val pageCount = document.pageCount
            return applyPageChange(
                { scratch -> scratch.movePage(fromIndex, toIndex) },
                { verified -> verified.pageCount == pageCount },
                "Page ${fromIndex + 1} moved to position ${toIndex + 1}.",
            )
        }
    }

    /** Adds every page of another PDF to the end of this one. */
    fun appendDocument(other: PdfSnapshot, password: String? = null): EditResult {
        synchronized(PdfiumLibrary.sync) {
            throwIfDisposed()
            PdfDocument.load(other, password).use { source ->
                if (!source.allowsCopyingPages) {
                    return EditResult.rejected(EditRejection.EditingRestricted, "The author of that PDF restricted copying its pages.")
                }

                val added = source.pageCount
                val pageCount = document.pageCount
                return applyPageChange(
                    { scratch -> scratch.importPages(source, pageCount) },
                    { verified -> verified.pageCount == pageCount + added },
                    if (added == 1) "1 page added." else "$added pages added.",
                )
            }
        }
    }

    fun undo(): Boolean {
        synchronized(PdfiumLibrary.sync) {
            throwIfDisposed()
            val last = undoHistory.lastOrNull() ?: return false
            val loaded = PdfDocument.load(last, password)
            undoHistory.removeLast()
            redoHistory.addLast(current)
            replace(last, loaded)
            return true
        }
    }

    fun redo(): Boolean {
        synchronized(PdfiumLibrary.sync) {
            throwIfDisposed()
            val next = redoHistory.lastOrNull() ?: return false
            val loaded = PdfDocument.load(next, password)
            redoHistory.removeLast()
            undoHistory.addLast(current)
            replace(next, loaded)
            return true
        }
    }

    /**
     * Writes the current snapshot to [path] through a temporary file in the same
     * folder, so an existing file is replaced in one step and never left half-written.
     */
    fun saveAs(path: String) {
        val fullPath = getFullPath(path)
        val directory = Paths.get(fullPath).parent ?: throw IllegalArgumentException("Choose a file in a folder. (Parameter 'path')")
        val snapshot = currentSnapshot
        val temporary = directory.resolve("~${Paths.get(fullPath).fileName}.${UUID.randomUUID().toString().replace("-", "")}.tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                snapshot.writeTo(Channels.newOutputStream(channel))
                channel.force(true)
            }

            // C#'s File.Move(overwrite: true) replaces the file in one step; without ATOMIC_MOVE,
            // Java deletes an existing file first on Linux and Android.
            Files.move(temporary, Paths.get(fullPath), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (ex: Throwable) {
            tryDelete(temporary)
            throw ex
        }

        synchronized(PdfiumLibrary.sync) {
            saved = snapshot
            filePath = fullPath
        }
    }

    /**
     * Writes the current snapshot to [output] and flushes it; then, only if that succeeded,
     * records the snapshot as saved, so [isModified] is false while it is current. [filePath]
     * doesn't change, and the stream stays open.
     *
     * Not in PaperKnife for Windows: the Android app saves through content URIs, where [saveAs]'s
     * temporary file and rename aren't possible.
     */
    fun saveTo(output: OutputStream) {
        val snapshot = currentSnapshot
        snapshot.writeTo(output)
        output.flush()
        synchronized(PdfiumLibrary.sync) {
            saved = snapshot
        }
    }

    override fun close() {
        synchronized(PdfiumLibrary.sync) {
            if (disposed) {
                return
            }

            document.close()
            undoHistory.clear()
            redoHistory.clear()
            disposed = true
        }
    }

    /**
     * Applies a planned edit to the scratch copy of a page: the first changed object takes the new
     * text, the other changed objects are removed, and the objects after them move along the
     * baseline by however much longer or shorter the new text is, so the rest of the run keeps its
     * spacing.
     */
    private fun applyRunEdit(page: PdfPage, layout: TextRunLayout, edit: TextRunEdit, change: Ref<RunChange?>): EditResult? {
        change.value = null
        val pieces = layout.pieces
        val first = pieces[edit.first]
        val keepsFirst = edit.newText.isNotEmpty()
        val start = layout.distanceAlongBaseline(first.matrix.e.toDouble(), first.matrix.f.toDouble())
        var end = start
        var trimmedEnd = start
        if (keepsFirst) {
            val textObject = page.getTextObject(first.objectIndex)
            if (textObject == null || !page.setText(textObject, edit.newText)) {
                return EditResult.rejected(EditRejection.EngineError, "PDFium couldn't change this text.")
            }

            // PDFium sets new text without kerning, one advance width after another.
            end = start + page.measureText(first.objectIndex, edit.newText)
            trimmedEnd = start + page.measureText(first.objectIndex, edit.newText.dotNetTrimEnd())
        }

        // Remove from the end, so the indexes of the objects still to remove stay valid.
        val firstRemoved = if (keepsFirst) edit.first + 1 else edit.first
        for (i in edit.last downTo firstRemoved) {
            if (!page.removeObject(pieces[i].objectIndex)) {
                return EditResult.rejected(EditRejection.EngineError, "PDFium couldn't change this text.")
            }
        }

        val removed = edit.last - firstRemoved + 1
        val rest = pieces.drop(edit.last + 1)
        val delta = end - layout.endAlongBaseline(pieces[edit.last])
        val moves = rest.isNotEmpty() && abs(delta) > first.fontSize * 1e-3
        val dx = if (moves) delta * first.matrix.a else 0.0
        val dy = if (moves) delta * first.matrix.b else 0.0
        for (piece in if (moves) rest else emptyList()) {
            if (!page.translateObject(piece.objectIndex - removed, dx, dy)) {
                return EditResult.rejected(EditRejection.EngineError, "PDFium couldn't move the rest of the line.")
            }
        }

        if (edit.newText.dotNetTrim().isNotEmpty()) {
            // FPDFText_SetText silently turns characters it can't encode into character code 0.
            val stored = page.getTextLayout().pieces.firstOrNull { p -> p.objectIndex == first.objectIndex }
            if (stored == null || !TextEquivalence.areEquivalentIgnoringSpacing(stored.text, edit.newText)) {
                return EditResult.rejected(
                    EditRejection.UnsupportedCharacters,
                    "The font “${layout.run.fontName}” in this PDF can't represent some of these characters.",
                    TextEquivalence.lost(edit.newText, stored?.text ?: ""),
                )
            }
        }

        val hiddenBefore = pieces.drop(edit.first).take(edit.last - edit.first + 1).map { p -> p.objectIndex }.toMutableList()
        val hiddenAfter = if (keepsFirst) mutableListOf(first.objectIndex) else mutableListOf()
        if (moves) {
            hiddenBefore.addAll(rest.map { p -> p.objectIndex })
            hiddenAfter.addAll(rest.map { p -> p.objectIndex - removed })
        }

        change.value = RunChange(layout, edit, removed, dx, dy, end, trimmedEnd, hiddenBefore, hiddenAfter)
        return null
    }

    /**
     * @param piecesBefore The page's text objects before the edit.
     * @param glyphsToCheck New characters that must have a glyph in the edited text's font in the candidate (the font changed with the edit).
     * @param copyFont The name of the complete copy of a font the edited text is drawn with, if any (see [paperknife.core.fonts.FontCompletion]).
     */
    private fun verifyTextEdit(
        verified: PdfDocument,
        candidate: PdfSnapshot,
        pageCount: Int,
        change: RunChange,
        countsBefore: PageObjectCounts,
        before: RenderedBitmap,
        piecesBefore: List<TextPiece>,
        glyphsToCheck: String?,
        copyFont: String?,
        edited: Ref<PdfTextRun?>,
        warnings: Ref<MutableList<String>>,
    ): String? {
        edited.value = null
        warnings.value = ArrayList()
        if (verified.pageCount != pageCount) {
            return "Saving changed the number of pages, so the edit was cancelled."
        }

        val original = change.layout.run
        val page = verified.getPage(original.pageIndex)
        val expected = countsBefore.copy(text = countsBefore.text - change.removed)
        val countsAfter = page.countObjects()
        if (countsAfter != expected) {
            return "Rewriting this page would drop or alter other content ($expected expected; $countsAfter after), so the edit was cancelled."
        }

        val (pieces, runs) = page.getTextLayout()
        val byIndex = pieces.associateBy { p -> p.objectIndex }
        val edit = change.edit
        val originalPieces = change.layout.pieces
        val deletes = edit.deletesAll(originalPieces.size)
        if (deletes && !keepsOtherText(piecesBefore, change.hiddenBefore, byIndex)) {
            return "Deleting this text would also change other text on the page, so it was cancelled."
        }

        val rest = originalPieces.drop(edit.last + 1)
        if (edit.newText.dotNetTrim().isNotEmpty()) {
            val stored = byIndex[originalPieces[edit.first].objectIndex]
            if (stored == null || !TextEquivalence.areEquivalentIgnoringSpacing(stored.text, edit.newText)) {
                return "The new text didn't survive saving, so the edit was cancelled."
            }

            // A font PDFium can't load is replaced by one of its own, which may draw every letter.
            if (copyFont != null && (!stored.isEmbedded || Type1Font.baseName(stored.fontName) != copyFont)) {
                return "PaperKnife couldn't confirm that the new text uses the font it added, so the edit was cancelled."
            }

            if (!glyphsToCheck.isNullOrEmpty() && !TextSupport.check(stored.font, original.fontName, glyphsToCheck).isSupported) {
                return "PaperKnife couldn't confirm that the font draws the new letters, so the edit was cancelled."
            }

            // The rest of the run was placed after the advance widths of the new text, so PDFium
            // must lay it out that way. Checked here, not in memory: saving drops character and
            // word spacing. PDFium may or may not report the object's trailing spaces.
            val storedEnd = change.layout.endAlongBaseline(stored)
            val tolerance = originalPieces[edit.first].fontSize * 0.01
            if (rest.isNotEmpty() && abs(storedEnd - change.end) > tolerance && abs(storedEnd - change.trimmedEnd) > tolerance) {
                return "PDFium laid out the new text differently than expected, so the edit was cancelled."
            }
        }

        val visible = page.getVisibleBox()
        for (piece in rest) {
            // Spaces drawn on their own have no characters to compare; their position is checked.
            val moved = byIndex[piece.objectIndex - change.removed]
            if (moved == null || moved.hasCharacters != piece.hasCharacters ||
                (piece.hasCharacters && !TextEquivalence.areEquivalent(moved.text, piece.text)) ||
                abs(moved.matrix.e - (piece.matrix.e + change.shiftX)) > 0.01 ||
                abs(moved.matrix.f - (piece.matrix.f + change.shiftY)) > 0.01
            ) {
                return "The rest of the line didn't survive saving, so the edit was cancelled."
            }

            val from = piece.bounds
            val to = moved.bounds
            if (piece.hasCharacters && from != null && to != null && sticksOutFurther(from, to, visible)) {
                return "Moving the rest of the line would push part of it off the page, so the edit was cancelled."
            }
        }

        if (change.movedRest &&
            !movedTextKeepsItsClipping(original.pageIndex, rest.map { p -> p.objectIndex }, change.shiftX, change.shiftY, before.width, before.height)
        ) {
            return "Moving the rest of the line would change how much of it shows, because the PDF clips it to an area, so the edit was cancelled."
        }

        // Hide the changed objects in both versions and compare the entire page, so content around,
        // under and over the edited text is checked too.
        val after = renderWithoutObjects(candidate, original.pageIndex, change.hiddenAfter, before.width, before.height)
        val changed = EditVerifier.countChangedPixels(before, after)
        if (changed > 0) {
            // C#'s "N0" format in the invariant culture: digits grouped in threes with commas.
            val count = String.format(Locale.ROOT, "%,d", changed)
            return "This edit would also change $count pixels elsewhere on the page (PDFium can't rewrite some spacing, shading or images exactly), so it was cancelled."
        }

        if (!deletes) {
            val editedRun = runs.firstOrNull { r -> r.contains(original.objectIndex) }?.run
                ?: return "The new text didn't survive saving, so the edit was cancelled."
            edited.value = editedRun

            for (other in runs.map { r -> r.run }) {
                if (other.objectIndex != editedRun.objectIndex && !other.isInvisible &&
                    other.bounds.intersects(editedRun.bounds) && !other.bounds.intersects(original.bounds)
                ) {
                    warnings.value.add("The new text now overlaps nearby text. Undo if that isn't what you want.")
                    break
                }
            }
        }

        if (verified.signatureCount > 0) {
            warnings.value.add("This PDF is digitally signed; editing it invalidates the signature.")
        }

        return null
    }

    private fun applyPageChange(change: (PdfDocument) -> Boolean, verify: (PdfDocument) -> Boolean, successMessage: String): EditResult {
        if (!document.allowsPageChanges) {
            return EditResult.rejected(EditRejection.EditingRestricted, "The author of this PDF restricted changes to its pages.")
        }

        val candidate = PdfDocument.load(current, password).use { scratch ->
            if (!change(scratch)) {
                return EditResult.rejected(EditRejection.EngineError, "PDFium couldn't make this change.")
            }

            scratch.save()
        }

        var verified: PdfDocument? = PdfDocument.load(candidate, password)
        try {
            if (!verify(verified!!)) {
                return EditResult.rejected(EditRejection.CollateralChange, "The change didn't survive saving, so it was cancelled.")
            }

            commit(candidate, verified)
            verified = null
            return EditResult.applied(successMessage)
        } finally {
            verified?.close()
        }
    }

    private fun commit(snapshot: PdfSnapshot, document: PdfDocument) {
        undoHistory.addLast(current)
        redoHistory.clear()
        replace(snapshot, document)

        var total = undoHistory.sumOf { s -> s.length.toLong() }
        while (total > historyLimitBytes && undoHistory.size > 1) {
            total -= undoHistory.first().length
            undoHistory.removeFirst()
        }
    }

    private fun replace(snapshot: PdfSnapshot, document: PdfDocument) {
        this.document.close()
        this.document = document
        current = snapshot
        contentSnapshot = null
        contentFile = null
        contentPageTree = null
        contentPages.clear()
        version++
    }

    /** Renders a page of a snapshot with some objects hidden. The hidden state is never saved. */
    private fun renderWithoutObjects(snapshot: PdfSnapshot, pageIndex: Int, objectIndexes: List<Int>, width: Int, height: Int): RenderedBitmap {
        PdfDocument.load(snapshot, password).use { loaded ->
            val page = loaded.getPage(pageIndex)
            for (objectIndex in objectIndexes) {
                if (!page.setObjectActive(objectIndex, active = false)) {
                    throw PdfException(PdfErrorCode.OperationFailed, "PDFium couldn't hide the edited text to verify the page.")
                }
            }

            return page.render(width, height)
        }
    }

    /**
     * Has PDFium rewrite the page with nothing changed and checks it still looks the same. This
     * covers the edited text's own styling (letter and word spacing, print colors), which the
     * after-edit comparison can't see because it hides that text. Every text object must also keep
     * its geometry, which covers text the rendering can't show, such as the invisible text of a
     * scanned page: PDFium's rewrite drops letter and word spacing from all the text it rewrites.
     */
    private fun checkRewriteKeepsAppearance(pageIndex: Int, objectIndexes: List<Int>, original: RenderedBitmap, pagePieces: List<TextPiece>): String? {
        val rewritten = PdfDocument.load(current, password).use { probe ->
            val page = probe.getPage(pageIndex)
            if (!objectIndexes.all { i -> page.markObjectModified(i) }) {
                return "PDFium couldn't prepare this text for editing."
            }

            probe.save()
        }

        PdfDocument.load(rewritten, password).use { reloaded ->
            val rewrittenPage = reloaded.getPage(pageIndex)
            val after = rewrittenPage.render(original.width, original.height)
            if (EditVerifier.countChangedPixels(original, after) != 0) {
                return "PaperKnife can't rewrite this page without changing how it looks (for example letter spacing, print colors or small embedded images), so its text can't be edited yet."
            }

            val rewrittenPieces = rewrittenPage.getTextLayout().pieces.associateBy { p -> p.objectIndex }
            return if (pagePieces.all { p -> rewrittenPieces[p.objectIndex]?.let { kept -> sameGeometry(kept, p) } == true }) {
                null
            } else {
                "PaperKnife can't rewrite this page without changing the spacing of some of its text (for example the invisible text of a scanned page), so its text can't be edited yet."
            }
        }
    }

    /**
     * Whether moving the rest of a run leaves its clipping unchanged. Moving a text object doesn't
     * move its clipping path, so moved text could be cut off at the edge of a clipped area, or text
     * that was cut off could appear. On a copy of the original page, the objects are moved and
     * rendered on their own, then rendered again with their clipping paths moved along with them;
     * both must cover the same pixels. One copy keeps the glyph positions identical, and it keeps
     * the clipping paths as saved: PDFium drops a rectangular clipping path when it loads a page
     * in which the path contains its object. Comparing coverage rather than color catches white text.
     *
     * @param moved The moved objects' indexes in the original page.
     */
    private fun movedTextKeepsItsClipping(pageIndex: Int, moved: List<Int>, dx: Double, dy: Double, width: Int, height: Int): Boolean {
        PdfDocument.load(current, password).use { loaded ->
            val page = loaded.getPage(pageIndex)
            if (!moved.any { i -> page.hasClipPath(i) }) {
                return true
            }

            page.showOnly(moved)
            if (!moved.all { i -> page.translateObject(i, dx, dy) }) {
                return false
            }

            val clipped = page.renderOnTransparent(width, height)
            if (!moved.all { i -> page.translateClipPath(i, dx, dy) }) {
                return false
            }

            return EditVerifier.countChangedCoverage(clipped, page.renderOnTransparent(width, height)) == 0
        }
    }

    private fun getPage(pageIndex: Int): PdfPage {
        throwIfDisposed()
        return document.getPage(pageIndex)
    }

    private fun isValidPage(pageIndex: Int): Boolean = pageIndex >= 0 && pageIndex < document.pageCount

    // C#'s ObjectDisposedException, an InvalidOperationException.
    private fun throwIfDisposed() {
        if (disposed) {
            throw IllegalStateException("Cannot access a disposed object.\nObject name: 'PaperKnife.Core.DocumentSession'.")
        }
    }

    /**
     * The page's content, read by PaperKnife's own parser, or null when content editing is off or
     * the file is encrypted or can't be read with certainty. Cached for the current snapshot.
     */
    private fun getContentPage(pageIndex: Int): ContentPage? {
        if (!useContentEditing) {
            return null
        }

        if (contentSnapshot !== current) {
            contentSnapshot = current
            contentPages.clear()
            try {
                contentFile = PdfFile.tryOpen(current.memory)?.takeIf { file -> !file.isEncrypted }
                contentPageTree = contentFile?.getPages()?.takeIf { pages -> pages.size == document.pageCount }
            } catch (ex: Exception) {
                if (!isReadFailure(ex)) {
                    throw ex
                }

                contentFile = null
                contentPageTree = null
            }
        }

        val file = contentFile
        val tree = contentPageTree
        if (file == null || tree == null) {
            return null
        }

        if (contentPages.containsKey(pageIndex)) {
            return contentPages[pageIndex]
        }

        if (contentPages.size >= 16) {
            // The fonts and character maps read for those pages go with them.
            contentPages.clear()
            file.forgetDerived()
        }

        val page = try {
            ContentPage.tryLoad(file, tree, pageIndex)
        } catch (ex: Exception) {
            if (!isReadFailure(ex)) {
                throw ex
            }

            null
        }

        contentPages[pageIndex] = page
        return page
    }

    companion object {
        fun open(path: String, password: String? = null): DocumentSession {
            val fullPath = getFullPath(path)
            return DocumentSession(PdfSnapshot.fromFile(fullPath), password, fullPath)
        }

        fun fromSnapshot(snapshot: PdfSnapshot, password: String? = null): DocumentSession = DocumentSession(snapshot, password, null)

        /**
         * Whether a text object is placed and spaced as it was: the same matrix, and the same bounds,
         * which also change with letter and word spacing, as PDFium's page rewrite may drop them.
         */
        private fun sameGeometry(kept: TextPiece, piece: TextPiece): Boolean {
            val m = piece.matrix
            val tolerance = (maxOf(maxOf(abs(m.a), abs(m.b)), maxOf(abs(m.c), abs(m.d))) * 1e-4) + 1e-6
            val a = kept.bounds
            val b = piece.bounds
            return kept.hasMatrix == piece.hasMatrix &&
                abs(kept.matrix.e - m.e) <= 0.01 && abs(kept.matrix.f - m.f) <= 0.01 &&
                abs(kept.matrix.a - m.a) <= tolerance && abs(kept.matrix.b - m.b) <= tolerance &&
                abs(kept.matrix.c - m.c) <= tolerance && abs(kept.matrix.d - m.d) <= tolerance &&
                when {
                    a == null && b == null -> true
                    a != null && b != null -> abs(a.left - b.left) <= 0.01 && abs(a.bottom - b.bottom) <= 0.01 &&
                        abs(a.right - b.right) <= 0.01 && abs(a.top - b.top) <= 0.01
                    else -> false
                }
        }

        /**
         * Whether every text object but the deleted ones is still there, in the same order, with the
         * same text, geometry and rendering mode. Invisible text, which the page comparison can't
         * see, is checked this way too.
         *
         * PDFium reads an object's text in the context of its neighbours: a hyphen or soft hyphen that
         * ends a line reads as U+0002 only when more text follows on the next line, the spaces after
         * it may be left out, and a copy drawn over other text (as for fake bold) reads as nothing. So
         * text is compared as a reader sees it, and an object that read as nothing may now read as text.
         *
         * @param deleted The page's indexes of the deleted objects before the edit.
         * @param after The page's text objects after the edit, by index.
         */
        private fun keepsOtherText(before: List<TextPiece>, deleted: List<Int>, after: Map<Int, TextPiece>): Boolean {
            val removed = deleted.sorted()
            for (piece in before) {
                val position = removed.binarySearch(piece.objectIndex)
                if (position >= 0) {
                    continue
                }

                // The objects after a deleted one move up by one index each.
                val kept = after[piece.objectIndex - position.inv()]
                if (kept == null || kept.renderMode != piece.renderMode || !sameGeometry(kept, piece) ||
                    (piece.hasCharacters && (!kept.hasCharacters || !TextEquivalence.areEquivalentIgnoringSpacing(asWritten(kept.text), asWritten(piece.text))))
                ) {
                    return false
                }
            }

            return true
        }

        // A hyphen or soft hyphen that ends a line reads as U+0002 while the next line follows it.
        private fun asWritten(text: String): String = text.replace('\u0002', '-').replace('\u00AD', '-')

        /**
         * Content that PDFium drops or can't reproduce when it rewrites a page's content stream
         * (see docs/ARCHITECTURE.md). Pages containing it are refused up front.
         */
        private fun findContentBlockers(page: PdfPage): List<String> {
            val blockers = ArrayList<String>()
            val counts = page.countObjects()
            if (counts.shading > 0) {
                blockers.add("This page has gradient shading that PaperKnife can't rewrite safely yet.")
            }

            if (counts.other > 0) {
                blockers.add("This page has content PaperKnife doesn't recognize.")
            }

            return blockers
        }

        /** Whether a font is embedded as a Type 1 program, whose glyphs PDFium selects by name. */
        private fun isType1Program(font: Pointer?): Boolean {
            val length = SizeTByReference(0)
            if (font == null || FPDFFont_GetFontData(font, null, SizeT(0), length) == 0 || length.value < 2 || length.value > Int.MAX_VALUE) {
                return false
            }

            val data = ByteArray(length.value.toInt())
            if (FPDFFont_GetFontData(font, data, SizeT(length.value), length) == 0) {
                return false
            }

            // A Type 1 program starts with its cleartext ("%!PS-AdobeFont" or "%!FontType1"), or a PFB segment header.
            return (data[0].toInt() == '%'.code && data[1].toInt() == '!'.code) || ((data[0].toInt() and 0xFF) == 0x80 && data[1].toInt() == 0x01)
        }

        /** Whether a moved object sticks out of the visible page further than before, on any side. */
        private fun sticksOutFurther(before: PdfRect, after: PdfRect, page: PdfRect): Boolean {
            val tolerance = 0.01f
            return maxOf(0f, page.left - after.left) > maxOf(0f, page.left - before.left) + tolerance ||
                maxOf(0f, after.right - page.right) > maxOf(0f, before.right - page.right) + tolerance ||
                maxOf(0f, page.bottom - after.bottom) > maxOf(0f, page.bottom - before.bottom) + tolerance ||
                maxOf(0f, after.top - page.top) > maxOf(0f, before.top - page.top) + tolerance
        }

        private fun tryDelete(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            }
        }

        /** C#'s `Path.GetFullPath`: the absolute path, with "." and ".." resolved; links aren't followed. */
        private fun getFullPath(path: String): String {
            if (path.isEmpty()) {
                throw IllegalArgumentException("The value cannot be an empty string. (Parameter 'path')")
            }

            return Paths.get(path).toAbsolutePath().normalize().toString()
        }

        /**
         * Exceptions from reading a damaged or unusual file with PaperKnife's own parser. Such files
         * are left to PDFium rather than failing the operation. C#'s ArgumentException,
         * IndexOutOfRangeException, InvalidCastException, InvalidOperationException (with its
         * Kotlin counterparts NoSuchElementException and ConcurrentModificationException),
         * KeyNotFoundException, OverflowException and DivideByZeroException (ArithmeticException),
         * FormatException (NumberFormatException, an IllegalArgumentException), InvalidDataException
         * (DataFormatException) and NullReferenceException.
         */
        private fun isReadFailure(ex: Exception): Boolean =
            ex is IllegalArgumentException || ex is IndexOutOfBoundsException || ex is ClassCastException || ex is IllegalStateException ||
                ex is NoSuchElementException || ex is ConcurrentModificationException || ex is ArithmeticException ||
                ex is DataFormatException || ex is NullPointerException
    }
}
