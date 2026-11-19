package paperknife.core.editing

import com.sun.jna.Pointer
import paperknife.core.EditRejection
import paperknife.core.EditResult
import paperknife.core.TextPiece
import paperknife.core.TextRunLayout
import paperknife.core.content.ContentPage
import paperknife.core.content.ContentTextLayout
import paperknife.core.content.PdfFontInfo
import paperknife.core.content.PdfPoint
import paperknife.core.content.ShowOp
import paperknife.core.content.TextShowSimulator
import paperknife.core.fonts.FontCompletion
import paperknife.core.pdfium.PdfiumNative.FPDFFont_GetGlyphWidth
import paperknife.core.support.DotNetDouble
import paperknife.core.support.Ref
import paperknife.core.syntax.CosNumber
import paperknife.core.syntax.CosString
import paperknife.core.syntax.CosWriter
import paperknife.core.syntax.IncrementalUpdate
import paperknife.core.syntax.ObjectUpdate
import paperknife.core.syntax.StreamUpdate
import java.io.ByteArrayOutputStream
import kotlin.math.abs

internal enum class ContentEditOutcome {
    /** This edit can't be made in the content stream; PDFium's page rewrite may still do it. */
    NotApplicable,

    Rejected,

    Ready,
}

internal data class ContentEditResult(
    val outcome: ContentEditOutcome,
    val reason: String? = null,
    val rejection: EditResult? = null,
    val candidate: ByteArray? = null,
    val change: RunChange? = null,
    val addedFont: String? = null,
    val glyphsToCheck: String? = null,
    val copyFont: String? = null,
)

/**
 * Edits a run in the page's content stream itself instead of having PDFium regenerate the page:
 * only the text-showing operations of the run change, and the result is appended to the file as
 * an incremental update, so every other byte stays as it was. Content PDFium can't write back
 * (Type 3 fonts, letter and word spacing, print colors, inline images, shadings) is kept as is.
 * The candidate is verified like any other edit.
 */
internal object ContentTextEdit {
    private const val POSITION_TOLERANCE = 0.01

    fun prepare(page: ContentPage, pagePieces: List<TextPiece>, layout: TextRunLayout, edit: TextRunEdit): ContentEditResult {
        if (page.text.objectShows.size != pagePieces.size) {
            return notApplicable("The page's text objects don't match its content.")
        }

        // Fonts without a /Widths array (the standard 14) take their widths from PDFium.
        fun width(show: ShowOp, code: UInt): Double? =
            show.font?.width(code) ?: (if (show.ordinal >= 0) pdfiumWidth(pagePieces[show.ordinal].font, show.font?.unicode(code)) else null)

        val text = TextShowSimulator.run(page.ops, page.file, page.page.resources, ::width)
        val ordinals = HashMap<Int, Int>()
        for (i in pagePieces.indices) {
            ordinals[pagePieces[i].objectIndex] = i
        }

        val runShows = layout.pieces.map { p -> text.objectShows[ordinals.getValue(p.objectIndex)] }
        if (edit.deletesAll(layout.pieces.size)) {
            return delete(page, text, runShows, layout, edit, pagePieces)
        }

        val first = runShows[edit.first]
        val firstPiece = layout.pieces[edit.first]
        val font = first.font
        if (font == null || (font.codeLength != 1 && font.codeLength != 2) || first.inReplacedText) {
            return notApplicable("PaperKnife can't encode text for this font.")
        }

        val layoutProblem = TextSupport.check(firstPiece.font, layout.run.fontName, edit.newText, checkGlyphs = false)
        if (!layoutProblem.isSupported) {
            return rejected(layoutProblem.problem!!, layoutProblem.characters)
        }

        val count = edit.last - edit.first + 1
        val oldShows = runShows.subList(edit.first, edit.first + count).toList()
        val preferred = oldShows.flatMap { s -> s.codes() }.map { c -> c.code }.toHashSet()
        var wordGap: Double? = null
        val planner = GlyphPlanner(
            font = font,
            width = { code -> width(first, code) },
            preferred = preferred,
            spaceCode = spaceCode(font, firstPiece.font, layout.run.fontName, preferred),
            wordGap = { wordGap ?: this.wordGap(text, pagePieces, font, ::width).also { gap -> wordGap = gap } },
            old = GlyphPlanner.readOldGlyphs(
                oldShows, layout.pieces.drop(edit.first).take(count), layout.separators.drop(edit.first).take(count - 1), ::width, first,
            ),
        )
        var plan = planner.plan(edit.newText)
        if (plan.missing.isNotEmpty() && font.isType3) {
            return rejected(TextSupport.missingMessage(layout.run.fontName, plan.missing), plan.missing)
        }

        // Letters the font lacks: TeX embeds only those a document uses, and PaperKnife may have
        // the whole font. The new text then uses a complete copy of the font; nothing else does.
        var completion: FontCompletion? = null
        var support = if (plan.missing.isEmpty() && plan.newCharacters.isNotEmpty() && !font.isType3) {
            TextSupport.check(firstPiece.font, layout.run.fontName, plan.newCharacters)
        } else {
            TextSupportResult.supported
        }

        if (support.isSupported && plan.newGlyphs.isNotEmpty() && FontCompletion.selectsGlyphsByName(page, first.fontName!!)) {
            val other = plan.newGlyphs.filter { g -> !font.vouches(g.code, g.text) }.map { g -> g.text }.distinct()
            if (other.isNotEmpty()) {
                // pdfTeX's fonts name only the codes a document uses. At the others PDFium draws the
                // glyph the subset's own encoding gives them, if the subset has it for another code or
                // font, while the character map and the widths follow TeX's encoding. A copy of the font
                // can draw and space such letters right; without one they are refused.
                support = TextSupportResult(TextSupport.missingMessage(layout.run.fontName, other), other)
            }
        }

        if (plan.missing.isNotEmpty() || !support.isSupported) {
            val difference = Ref<String?>(null)
            val created = FontCompletion.tryCreate(page, first.fontName!!, difference)
            if (created == null) {
                if (plan.missing.isNotEmpty()) {
                    // PDFium may still encode them on its own; if it can't draw them either, say so.
                    support = TextSupport.check(firstPiece.font, layout.run.fontName, plan.missing.joinToString(""))
                    if (support.isSupported) {
                        return notApplicable("The font's character map doesn't cover the new text.")
                    }
                }

                return rejected(
                    if (difference.value == null) {
                        support.problem!!
                    } else {
                        "${support.problem}. PaperKnife has its own copy of this font but can't use it here: ${difference.value}."
                    },
                    support.characters,
                )
            }

            // Kept letters keep their codes, which select the same glyphs with the same widths in the copy.
            completion = created
            val copy = created.info
            plan = GlyphPlanner(
                font = copy,
                width = { code -> copy.width(code) },
                preferred = preferred,
                spaceCode = planner.spaceCode,
                wordGap = planner.wordGap,
                old = planner.old,
            ).plan(edit.newText)
            val notDrawn = (plan.missing + plan.newGlyphs.filter { g -> !created.draws(g.code, g.text) }.map { g -> g.text }).distinct()
            if (notDrawn.isNotEmpty()) {
                return rejected(TextSupport.missingMessage(layout.run.fontName, notDrawn), notDrawn)
            }
        }

        val glyphs = plan.glyphs
        if (glyphs.any { g -> g.width.isNaN() }) {
            return notApplicable("A character's width isn't known.")
        }

        // Ends along the baseline, in the units of TextRunLayout: the end of the last glyph,
        // leaving out the spacing after it, so the gap to the rest of the run stays the same.
        // Spaces drawn as gaps count, at either end.
        fun start(show: ShowOp): Double = layout.distanceAlongBaseline(show.pageStart.x, show.pageStart.y)
        val lead = if (glyphs.isNotEmpty()) -plan.initialKerning / 1000 * first.fontSize else 0.0
        val trail = if (plan.trailingKerning == 0.0) 0.0 else (-plan.trailingKerning / 1000 * first.fontSize) + (if (glyphs.isNotEmpty()) first.charSpace else 0.0)
        val end = start(first) + lead + span(first, glyphs) + trail
        val trimmedEnd = start(first) + lead + span(first, glyphs.take(plan.visibleGlyphs))
        val oldEnd: Double
        val lastPiece = layout.pieces[edit.last]
        if (!lastPiece.hasCharacters) {
            oldEnd = layout.endAlongBaseline(lastPiece)
        } else {
            val lastShow = runShows[edit.last]
            val oldGlyphs = ArrayList<PlannedGlyph>()
            for ((code, kerning) in lastShow.codes()) {
                val w = width(lastShow, code) ?: return notApplicable("A character's width isn't known.")
                oldGlyphs.add(PlannedGlyph(code, w, kerning))
            }

            oldEnd = start(lastShow) + span(lastShow, oldGlyphs)
        }

        val rest = runShows.drop(edit.last + 1)
        val shift = end - oldEnd
        val moves = rest.isNotEmpty() && abs(shift) > firstPiece.fontSize * 1e-3
        val dx = if (moves) shift * firstPiece.matrix.a else 0.0
        val dy = if (moves) shift * firstPiece.matrix.b else 0.0

        val keepsFirst = glyphs.isNotEmpty()
        val removed = runShows.drop(if (keepsFirst) edit.first + 1 else edit.first).take(edit.last - edit.first + (if (keepsFirst) 0 else 1))
        val roles = HashMap<Int, Role>()
        if (keepsFirst) {
            roles[first.opIndex] = Role.Edited
        }

        for (show in removed) {
            roles[show.opIndex] = Role.Removed
        }

        for (show in if (moves) rest else emptyList()) {
            roles[show.opIndex] = Role.Moved
        }

        val blocks = (runShows.drop(edit.first).take(edit.last - edit.first + 1) + (if (moves) rest else emptyList()))
            .map { s -> s.block }.distinct().sorted()
        val problem = checkBlocks(text, blocks, pagePieces)
        if (problem != null) {
            return notApplicable(problem)
        }

        val switchFont = if (completion == null) null else SwitchFont(completion.resourceName, first.fontName!!, first.fontSize)
        val content = rewrite(page, text, blocks, roles, first, editedOperation(completion?.info ?: font, plan), PdfPoint(dx, dy), switchFont)
            ?: return notApplicable("A moved text object's transformation can't be inverted.")

        val updates = ArrayList<ObjectUpdate>(page.streams.mapIndexed { i, s -> StreamUpdate(s.number, s.generation, if (i == 0) content else ByteArray(0)) })
        if (completion != null) {
            updates.addAll(completion.updates())
        }

        val candidate = IncrementalUpdate.append(page.file, updates)

        val pieces = layout.pieces
        val hiddenBefore = pieces.drop(edit.first).take(edit.last - edit.first + 1).map { p -> p.objectIndex }.toMutableList()
        val hiddenAfter = if (keepsFirst) mutableListOf(firstPiece.objectIndex) else mutableListOf()
        if (moves) {
            hiddenBefore.addAll(pieces.drop(edit.last + 1).map { p -> p.objectIndex })
            hiddenAfter.addAll(pieces.drop(edit.last + 1).map { p -> p.objectIndex - removed.size })
        }

        val change = RunChange(layout, edit, removed.size, dx, dy, end, trimmedEnd, hiddenBefore, hiddenAfter)
        return ContentEditResult(
            ContentEditOutcome.Ready,
            candidate = candidate,
            change = change,
            addedFont = if (completion != null && !completion.reused) completion.fontName else null,
            glyphsToCheck = if (completion == null) null else plan.newCharacters,
            copyFont = completion?.fontName,
        )
    }

    /**
     * Deletes a whole run: its text-showing operations are left out of their blocks, which are
     * rewritten as for an edit, so the rest of each block keeps its place. Nothing is encoded, so
     * any font will do.
     */
    private fun delete(
        page: ContentPage,
        text: ContentTextLayout,
        runShows: List<ShowOp>,
        layout: TextRunLayout,
        edit: TextRunEdit,
        pagePieces: List<TextPiece>,
    ): ContentEditResult {
        if (runShows.any { s -> s.inReplacedText }) {
            return notApplicable("The text has replacement text (/ActualText).")
        }

        val roles = HashMap<Int, Role>()
        for (show in runShows) {
            roles[show.opIndex] = Role.Removed
        }

        val blocks = runShows.map { s -> s.block }.distinct().sorted()
        val problem = checkBlocks(text, blocks, pagePieces)
        if (problem != null) {
            return notApplicable(problem)
        }

        val content = rewrite(page, text, blocks, roles, runShows[0], "", PdfPoint(0.0, 0.0), switchFont = null)
            ?: return notApplicable("The text blocks can't be rewritten.")

        val updates = page.streams.mapIndexed { i, s -> StreamUpdate(s.number, s.generation, if (i == 0) content else ByteArray(0)) }
        val deleted = layout.pieces.map { p -> p.objectIndex }
        val change = RunChange(layout, edit, deleted.size, 0.0, 0.0, 0.0, 0.0, deleted, emptyList())
        return ContentEditResult(ContentEditOutcome.Ready, candidate = IncrementalUpdate.append(page.file, updates), change = change)
    }

    /**
     * The advance from a glyph sequence's origin to the end of its last glyph, along the baseline in
     * TextRunLayout units (where horizontal scaling cancels out): widths, character and word
     * spacing and TJ adjustments, but not the spacing after the last glyph.
     */
    private fun span(show: ShowOp, glyphs: List<PlannedGlyph>): Double {
        var total = 0.0
        for (i in glyphs.indices) {
            total += glyphs[i].width * show.fontSize
            if (i < glyphs.size - 1) {
                total += show.charSpace + (if (glyphs[i].code == 32u && show.font!!.codeLength == 1) show.wordSpace else 0.0) -
                    (glyphs[i].kerningAfter / 1000 * show.fontSize)
            }
        }

        return total
    }

    /** A code that draws a space in this font, or null when spaces have to be drawn as gaps. */
    private fun spaceCode(font: PdfFontInfo, pdfiumFont: Pointer?, fontName: String, preferred: Set<UInt>): UInt? {
        val missing = Ref<MutableList<String>>(ArrayList())
        val codes = font.encode(" ", preferred, missing)
        if (missing.value.isNotEmpty() || codes.size != 1) {
            return null
        }

        return if (font.isType3 || TextSupport.check(pdfiumFont, fontName, " ").isSupported) codes[0] else null
    }

    /**
     * The median gap between words, in thousandths of an em, among this font's text on the page:
     * the TJ adjustments PDFium reads as spaces. A third of an em, TeX's usual word space, when
     * there are none.
     */
    private fun wordGap(text: ContentTextLayout, pagePieces: List<TextPiece>, font: PdfFontInfo, width: (ShowOp, UInt) -> Double?): Double {
        val gaps = ArrayList<Double>()
        for (show in text.objectShows) {
            if (show.font === font) {
                val glyphs = GlyphPlanner.readOldGlyphs(listOf(show), listOf(pagePieces[show.ordinal]), emptyList(), width, show)
                if (glyphs != null) {
                    gaps.addAll(glyphs.filter { g -> g.spaceAfter }.map { g -> -g.kerningAfter })
                }
            }
        }

        if (gaps.isEmpty()) {
            return 1000.0 / 3
        }

        // C#'s List<double>.Sort, which isn't stable; gaps that compare equal are the same number
        // (a gap is never 0), so the median doesn't depend on their order.
        gaps.sortWith { a, b -> DotNetDouble.compare(a, b) }
        return gaps[gaps.size / 2]
    }

    /** The text-showing operation for the new text: Tj, or TJ when it needs adjustments. */
    private fun editedOperation(font: PdfFontInfo, plan: GlyphPlan): String {
        if (!plan.hasAdjustments) {
            return CosWriter.toText(CosString(font.joinCodes(plan.glyphs.map { g -> g.code }))) + " Tj"
        }

        val operation = StringBuilder("[")
        if (plan.initialKerning != 0.0) {
            operation.append(CosWriter.number(plan.initialKerning)).append(' ')
        }

        val codes = ArrayList<UInt>()
        for (i in plan.glyphs.indices) {
            codes.add(plan.glyphs[i].code)
            if (i == plan.glyphs.size - 1 || plan.glyphs[i].kerningAfter != 0.0) {
                operation.append(CosWriter.toText(CosString(font.joinCodes(codes))))
                if (i < plan.glyphs.size - 1) {
                    operation.append(' ').append(CosWriter.number(plan.glyphs[i].kerningAfter)).append(' ')
                }

                codes.clear()
            }
        }

        return operation.append("] TJ").toString()
    }

    /**
     * The blocks are rewritten with an explicit position for every text-showing operation, so each
     * one's position must be known and match where PDFium draws it.
     */
    private fun checkBlocks(text: ContentTextLayout, blocks: List<Int>, pagePieces: List<TextPiece>): String? {
        for (index in blocks) {
            if (index < 0 || text.blocks[index].let { block -> block.end < 0 || block.irregular }) {
                return "The text isn't in a simple text block."
            }

            for (show in text.shows.filter { s -> s.block == index && s.producesObject }) {
                val piece = pagePieces[show.ordinal]
                val (x, y) = show.pageStart
                val m = show.objectMatrix
                val scale = maxOf(maxOf(abs(m.a), abs(m.b)), maxOf(abs(m.c), abs(m.d)))
                val tolerance = (scale * 1e-4) + 1e-6
                if (!show.positionKnown || !piece.hasMatrix ||
                    abs(x - piece.matrix.e) > POSITION_TOLERANCE || abs(y - piece.matrix.f) > POSITION_TOLERANCE ||
                    abs(m.a - piece.matrix.a) > tolerance || abs(m.b - piece.matrix.b) > tolerance ||
                    abs(m.c - piece.matrix.c) > tolerance || abs(m.d - piece.matrix.d) > tolerance
                ) {
                    return "PaperKnife couldn't confirm where the text is drawn."
                }
            }
        }

        return null
    }

    /**
     * The page content with each affected text block rewritten: positioning operators are replaced
     * by an explicit Tm before every text-showing operation, the edited operation shows the new
     * codes, removed ones are left out, and moved ones are shifted. Everything else is copied
     * byte for byte.
     */
    private fun rewrite(
        page: ContentPage,
        text: ContentTextLayout,
        blocks: List<Int>,
        roles: Map<Int, Role>,
        edited: ShowOp,
        editedOperation: String,
        shift: PdfPoint,
        switchFont: SwitchFont?,
    ): ByteArray? {
        val content = page.content
        val shows = text.shows.associateBy { s -> s.opIndex }
        val output = ByteArrayOutputStream(content.size + 256)
        var copied = 0
        for (index in blocks) {
            val block = text.blocks[index]
            val start = page.ops[block.begin].start
            output.write(content, copied, start - copied)
            for (i in block.begin..block.end) {
                val op = page.ops[i]
                val operator = op.operator
                if (operator == "Td" || operator == "Tm" || operator == "T*") {
                    continue
                }

                if (operator == "TD") {
                    // TD also sets the leading, which later text may use.
                    val ty = op.operands.getOrNull(1)
                    if (op.operands.size == 2 && op.operands[0] is CosNumber && ty is CosNumber) {
                        write(output, "${CosWriter.number(-ty.value)} TL\n")
                    }

                    continue
                }

                val show = if (operator == "Tj" || operator == "TJ" || operator == "'" || operator == "\"") shows[i] else null
                if (show == null) {
                    output.write(content, op.start, op.end - op.start)
                    output.write('\n'.code)
                    continue
                }

                if (operator == "\"") {
                    write(output, "${(op.operands[0] as CosNumber).raw} Tw ${(op.operands[1] as CosNumber).raw} Tc\n")
                }

                val role = roles[i] ?: Role.Unchanged
                if (role == Role.Removed) {
                    continue
                }

                if (role == Role.Edited) {
                    // Text drawn with a completed copy of the font selects the copy for itself only.
                    if (switchFont != null) {
                        write(output, "${name(switchFont.copy)} ${CosWriter.number(switchFont.size)} Tf\n")
                    }

                    write(output, "${edited.glyphMatrix.toOperands()} Tm $editedOperation\n")
                    if (switchFont != null) {
                        write(output, "${name(switchFont.original)} ${CosWriter.number(switchFont.size)} Tf\n")
                    }

                    continue
                }

                var matrix = show.opMatrix
                if (role == Role.Moved) {
                    val inverse = show.ctm.invert() ?: return null
                    val (ux, uy) = inverse.transformVector(shift.x, shift.y)
                    matrix = matrix.copy(e = matrix.e + ux, f = matrix.f + uy)
                }

                if (show.producesObject) {
                    write(output, "${matrix.toOperands()} Tm ")
                }

                if (operator == "Tj" || operator == "TJ") {
                    output.write(content, op.start, op.end - op.start)
                } else {
                    write(output, CosWriter.toText(op.operands[op.operands.size - 1]))
                    write(output, " Tj")
                }

                output.write('\n'.code)
            }

            copied = page.ops[block.end].end
        }

        output.write(content, copied, content.size - copied)
        return output.toByteArray()
    }

    private fun pdfiumWidth(font: Pointer?, text: String?): Double? {
        val width = FloatArray(1)
        return if (font != null && text != null && text.length == 1 && FPDFFont_GetGlyphWidth(font, text[0].code, 1f, width) != 0 && width[0] > 0) {
            width[0].toDouble()
        } else {
            null
        }
    }

    // C#'s Encoding.Latin1.GetBytes: everything written here comes from CosWriter, whose characters are bytes.
    private fun write(output: ByteArrayOutputStream, text: String) = output.write(text.toByteArray(Charsets.ISO_8859_1))

    private fun name(name: String): String {
        val text = StringBuilder()
        CosWriter.writeName(text, name)
        return text.toString()
    }

    private fun notApplicable(reason: String): ContentEditResult = ContentEditResult(ContentEditOutcome.NotApplicable, reason)

    private fun rejected(message: String, characters: List<String>): ContentEditResult =
        ContentEditResult(ContentEditOutcome.Rejected, rejection = EditResult.rejected(EditRejection.UnsupportedCharacters, message, characters))

    /** The copy of a font the edited text selects, and the font to select again after it (C#'s `(string Copy, string Original, double Size)`). */
    private class SwitchFont(val copy: String, val original: String, val size: Double)

    private enum class Role {
        Unchanged,
        Edited,
        Removed,
        Moved,
    }
}
