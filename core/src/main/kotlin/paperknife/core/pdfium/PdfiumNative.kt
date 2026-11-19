package paperknife.core.pdfium

import com.sun.jna.Callback
import com.sun.jna.IntegerType
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.ByReference

/**
 * PDFium's `FPDF_FILEWRITE`. PDFium only reads these two fields and hands the struct pointer back
 * to the callback. (C# follows them with a GCHandle to the managed sink; here the callback object
 * holds its sink.) Keep the struct and its callback strongly referenced while PDFium may call it.
 */
@Structure.FieldOrder("version", "writeBlock")
internal class FpdfFileWrite : Structure() {
    @JvmField
    var version: Int = 0

    @JvmField
    var writeBlock: WriteBlock? = null

    /** `int (*WriteBlock)(FPDF_FILEWRITE* self, const void* data, unsigned long size)`. */
    fun interface WriteBlock : Callback {
        fun invoke(self: Pointer?, data: Pointer?, size: NativeLong): Int
    }
}

/** PDFium's `FS_MATRIX`. Functions that fill one take a `FloatArray` of [SIZE] floats, in this order. */
internal data class FsMatrix(
    val a: Float = 0f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 0f,
    val e: Float = 0f,
    val f: Float = 0f,
) {
    /** The matrix PDFium wrote into a `FloatArray` of [SIZE] floats. */
    constructor(native: FloatArray) : this(native[0], native[1], native[2], native[3], native[4], native[5])

    companion object {
        const val SIZE = 6
    }
}

/**
 * PDFium's `FS_RECTF`. Note the field order: left, top, right, bottom. Functions that fill one
 * take a `FloatArray` of [SIZE] floats, in this order.
 */
internal data class FsRectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** The rectangle PDFium wrote into a `FloatArray` of [SIZE] floats. */
    constructor(native: FloatArray) : this(native[0], native[1], native[2], native[3])

    companion object {
        const val SIZE = 4
    }
}

/** C's `size_t` (C#'s `nuint`), which is 32 bits on 32-bit Android and 64 bits elsewhere. */
internal class SizeT(value: Long = 0) : IntegerType(Native.SIZE_T_SIZE, value, true) {
    override fun toByte() = toLong().toByte()

    override fun toShort() = toLong().toShort()
}

/** A `size_t` that PDFium writes (C#'s `nuint*`). */
internal class SizeTByReference(value: Long = 0) : ByReference(Native.SIZE_T_SIZE) {
    var value: Long
        get() = if (Native.SIZE_T_SIZE == 8) pointer.getLong(0) else pointer.getInt(0).toLong() and 0xFFFF_FFFFL
        set(value) {
            if (Native.SIZE_T_SIZE == 8) {
                pointer.setLong(0, value)
            } else {
                pointer.setInt(0, value.toInt())
            }
        }

    init {
        this.value = value
    }
}

/** C's `unsigned long` as C#'s `uint`: on Windows `FPDF_DWORD` is 32 bits wide, and PDFium's values fit. */
internal fun NativeLong.toUInt32(): UInt = toInt().toUInt()

/** A `uint` as C's `unsigned long` (`FPDF_DWORD`). */
internal fun UInt.toNativeLong(): NativeLong = NativeLong(toLong(), true)

/**
 * The subset of the PDFium C API that PaperKnife uses, matching the headers shipped in
 * bblanchon.PDFium.Win32 159.0.8152 (chromium/8152). PDFium is not thread-safe: call these only
 * while holding [PdfiumLibrary.sync]. C `unsigned long` (`FPDF_DWORD`) is [NativeLong], 32 bits
 * on Windows and 32-bit Android and 64 bits elsewhere; `size_t` is [SizeT]; `int`, `unsigned int`
 * and `uint32_t` are `Int`; handles are [Pointer], null for C's NULL. Out parameters are arrays
 * of one element, and `FS_MATRIX*` and `FS_RECTF*` arrays of their floats. `FPDF_WIDESTRING` is
 * UTF-16LE with a two-byte terminator (see [wideString]), never JNA's `WString`, which is the
 * 32-bit `wchar_t` on Linux and Android.
 *
 * The functions are bound with JNA's direct mapping, which calls them nearly as fast as JNI.
 */
@Suppress("FunctionName")
internal object PdfiumNative {
    const val FPDF_ERR_FILE: UInt = 2u
    const val FPDF_ERR_FORMAT: UInt = 3u
    const val FPDF_ERR_PASSWORD: UInt = 4u
    const val FPDF_ERR_SECURITY: UInt = 5u
    const val FPDF_ERR_PAGE: UInt = 6u

    const val FPDF_ANNOT = 0x01
    const val FPDFBitmap_BGRA = 4
    val FPDF_NO_INCREMENTAL: UInt = 1u shl 1

    const val FPDF_PAGEOBJ_TEXT = 1
    const val FPDF_PAGEOBJ_PATH = 2
    const val FPDF_PAGEOBJ_IMAGE = 3
    const val FPDF_PAGEOBJ_SHADING = 4
    const val FPDF_PAGEOBJ_FORM = 5

    const val FPDF_TEXTRENDERMODE_INVISIBLE = 3
    const val FPDF_FONT_TRUETYPE = 2
    const val FPDF_FILLMODE_ALTERNATE = 1

    /** Permission bit 4 (ISO 32000-1, table 22): modify the contents of the document. */
    val permissionModifyContents: UInt = 1u shl 3

    /** Permission bit 5: copy or otherwise extract text and graphics. */
    val permissionCopy: UInt = 1u shl 4

    /** Permission bit 11 (security handler revision 3 and later): assemble the document. */
    val permissionAssemble: UInt = 1u shl 10

    init {
        Native.register(PdfiumNative::class.java, "pdfium")
    }

    /**
     * What `FPDF_GetLastError()` returns right after the failing call. On Windows PDFium keeps the
     * error in the thread's Win32 last error, which the JVM clears between two native calls; JNA
     * reads it right after every call and keeps a copy, so read that copy there. Elsewhere PDFium
     * keeps it in a variable of its own.
     */
    fun lastError(): UInt = if (Platform.isWindows()) Native.getLastError().toUInt() else FPDF_GetLastError().toUInt32()

    /**
     * [text] as an `FPDF_WIDESTRING`: its UTF-16 code units, unpaired surrogates included,
     * little-endian, followed by the two-byte terminator. (C# passes a .NET string, which is
     * NUL-terminated.)
     */
    fun wideString(text: String): ByteArray {
        val bytes = ByteArray((text.length + 1) * 2)
        for (i in text.indices) {
            val unit = text[i].code
            bytes[i * 2] = unit.toByte()
            bytes[(i * 2) + 1] = (unit shr 8).toByte()
        }

        return bytes
    }

    // fpdfview.h
    @JvmStatic external fun FPDF_InitLibrary()

    @JvmStatic external fun FPDF_GetLastError(): NativeLong

    @JvmStatic external fun FPDF_LoadMemDocument64(dataBuffer: Pointer?, size: SizeT, password: ByteArray?): Pointer?

    @JvmStatic external fun FPDF_CloseDocument(document: Pointer?)

    @JvmStatic external fun FPDF_GetPageCount(document: Pointer?): Int

    @JvmStatic external fun FPDF_GetDocPermissions(document: Pointer?): NativeLong

    @JvmStatic external fun FPDF_GetSecurityHandlerRevision(document: Pointer?): Int

    @JvmStatic external fun FPDF_LoadPage(document: Pointer?, pageIndex: Int): Pointer?

    @JvmStatic external fun FPDF_ClosePage(page: Pointer?)

    @JvmStatic external fun FPDF_GetPageWidthF(page: Pointer?): Float

    @JvmStatic external fun FPDF_GetPageHeightF(page: Pointer?): Float

    @JvmStatic external fun FPDFBitmap_CreateEx(width: Int, height: Int, format: Int, firstScan: Pointer?, stride: Int): Pointer?

    @JvmStatic external fun FPDFBitmap_FillRect(bitmap: Pointer?, left: Int, top: Int, width: Int, height: Int, color: NativeLong): Int

    @JvmStatic external fun FPDFBitmap_Destroy(bitmap: Pointer?)

    @JvmStatic external fun FPDF_RenderPageBitmap(bitmap: Pointer?, page: Pointer?, startX: Int, startY: Int, sizeX: Int, sizeY: Int, rotate: Int, flags: Int)

    @JvmStatic external fun FPDF_DeviceToPage(
        page: Pointer?,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        rotate: Int,
        deviceX: Int,
        deviceY: Int,
        pageX: DoubleArray?,
        pageY: DoubleArray?,
    ): Int

    @JvmStatic external fun FPDF_PageToDevice(
        page: Pointer?,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        rotate: Int,
        pageX: Double,
        pageY: Double,
        deviceX: IntArray?,
        deviceY: IntArray?,
    ): Int

    /** [rect] receives an `FS_RECTF` ([FsRectF.SIZE] floats). */
    @JvmStatic external fun FPDF_GetPageBoundingBox(page: Pointer?, rect: FloatArray?): Int

    // fpdf_save.h
    @JvmStatic external fun FPDF_SaveAsCopy(document: Pointer?, fileWrite: FpdfFileWrite?, flags: NativeLong): Int

    // fpdf_edit.h
    @JvmStatic external fun FPDF_CreateNewDocument(): Pointer?

    @JvmStatic external fun FPDFPage_New(document: Pointer?, pageIndex: Int, width: Double, height: Double): Pointer?

    @JvmStatic external fun FPDFPage_Delete(document: Pointer?, pageIndex: Int)

    @JvmStatic external fun FPDF_MovePages(document: Pointer?, pageIndices: IntArray?, pageIndicesLength: NativeLong, destPageIndex: Int): Int

    @JvmStatic external fun FPDFPage_GetRotation(page: Pointer?): Int

    @JvmStatic external fun FPDFPage_SetRotation(page: Pointer?, rotate: Int)

    @JvmStatic external fun FPDFPage_InsertObject(page: Pointer?, pageObject: Pointer?): Int

    @JvmStatic external fun FPDFPage_RemoveObject(page: Pointer?, pageObject: Pointer?): Int

    @JvmStatic external fun FPDFPageObj_Destroy(pageObject: Pointer?)

    @JvmStatic external fun FPDFPage_CountObjects(page: Pointer?): Int

    @JvmStatic external fun FPDFPage_GetObject(page: Pointer?, index: Int): Pointer?

    @JvmStatic external fun FPDFPage_GenerateContent(page: Pointer?): Int

    @JvmStatic external fun FPDFPageObj_GetType(pageObject: Pointer?): Int

    @JvmStatic external fun FPDFPageObj_SetIsActive(pageObject: Pointer?, active: Int): Int

    @JvmStatic external fun FPDFPageObj_GetBounds(pageObject: Pointer?, left: FloatArray?, bottom: FloatArray?, right: FloatArray?, top: FloatArray?): Int

    /** [matrix] receives an `FS_MATRIX` ([FsMatrix.SIZE] floats). */
    @JvmStatic external fun FPDFPageObj_GetMatrix(pageObject: Pointer?, matrix: FloatArray?): Int

    @JvmStatic external fun FPDFPageObj_Transform(pageObject: Pointer?, a: Double, b: Double, c: Double, d: Double, e: Double, f: Double)

    @JvmStatic external fun FPDFPageObj_SetFillColor(pageObject: Pointer?, r: Int, g: Int, b: Int, a: Int): Int

    @JvmStatic external fun FPDFPageObj_GetFillColor(pageObject: Pointer?, r: IntArray?, g: IntArray?, b: IntArray?, a: IntArray?): Int

    @JvmStatic external fun FPDFPageObj_GetStrokeColor(pageObject: Pointer?, r: IntArray?, g: IntArray?, b: IntArray?, a: IntArray?): Int

    @JvmStatic external fun FPDFPageObj_CountMarks(pageObject: Pointer?): Int

    @JvmStatic external fun FPDFPageObj_GetMarkedContentID(pageObject: Pointer?): Int

    @JvmStatic external fun FPDFPageObj_CreateNewRect(x: Float, y: Float, width: Float, height: Float): Pointer?

    @JvmStatic external fun FPDFPath_SetDrawMode(path: Pointer?, fillMode: Int, stroke: Int): Int

    /** [font] is a NUL-terminated byte string. */
    @JvmStatic external fun FPDFPageObj_NewTextObj(document: Pointer?, font: ByteArray?, fontSize: Float): Pointer?

    @JvmStatic external fun FPDFPageObj_CreateTextObj(document: Pointer?, font: Pointer?, fontSize: Float): Pointer?

    /** [text] is an `FPDF_WIDESTRING` (see [wideString]). */
    @JvmStatic external fun FPDFText_SetText(textObject: Pointer?, text: ByteArray?): Int

    @JvmStatic external fun FPDFText_LoadFont(document: Pointer?, data: ByteArray?, size: Int, fontType: Int, cid: Int): Pointer?

    @JvmStatic external fun FPDFFont_Close(font: Pointer?)

    @JvmStatic external fun FPDFTextObj_GetFont(textObject: Pointer?): Pointer?

    @JvmStatic external fun FPDFTextObj_GetFontSize(textObject: Pointer?, size: FloatArray?): Int

    @JvmStatic external fun FPDFTextObj_GetTextRenderMode(textObject: Pointer?): Int

    /** [buffer] receives UTF-16LE code units (`FPDF_WCHAR`); [length] and the result count bytes. */
    @JvmStatic external fun FPDFTextObj_GetText(textObject: Pointer?, textPage: Pointer?, buffer: ByteArray?, length: NativeLong): NativeLong

    @JvmStatic external fun FPDFFont_GetBaseFontName(font: Pointer?, buffer: ByteArray?, length: SizeT): SizeT

    @JvmStatic external fun FPDFFont_GetIsEmbedded(font: Pointer?): Int

    @JvmStatic external fun FPDFFont_GetFlags(font: Pointer?): Int

    @JvmStatic external fun FPDFFont_GetFontData(font: Pointer?, buffer: ByteArray?, length: SizeT, outLength: SizeTByReference?): Int

    @JvmStatic external fun FPDFFont_GetGlyphWidth(font: Pointer?, glyph: Int, fontSize: Float, width: FloatArray?): Int

    @JvmStatic external fun FPDFFont_GetGlyphPath(font: Pointer?, glyph: Int, fontSize: Float): Pointer?

    @JvmStatic external fun FPDFGlyphPath_CountGlyphSegments(glyphPath: Pointer?): Int

    @JvmStatic external fun FPDFGlyphPath_GetGlyphPathSegment(glyphPath: Pointer?, index: Int): Pointer?

    @JvmStatic external fun FPDFPathSegment_GetPoint(segment: Pointer?, x: FloatArray?, y: FloatArray?): Int

    @JvmStatic external fun FPDFPathSegment_GetType(segment: Pointer?): Int

    @JvmStatic external fun FPDFPathSegment_GetClose(segment: Pointer?): Int

    // fpdf_transformpage.h
    @JvmStatic external fun FPDFPageObj_GetClipPath(pageObject: Pointer?): Pointer?

    @JvmStatic external fun FPDFClipPath_CountPaths(clipPath: Pointer?): Int

    @JvmStatic external fun FPDFClipPath_CountPathSegments(clipPath: Pointer?, pathIndex: Int): Int

    @JvmStatic external fun FPDFClipPath_GetPathSegment(clipPath: Pointer?, pathIndex: Int, segmentIndex: Int): Pointer?

    @JvmStatic external fun FPDFPageObj_TransformClipPath(pageObject: Pointer?, a: Double, b: Double, c: Double, d: Double, e: Double, f: Double)

    // fpdf_text.h
    @JvmStatic external fun FPDFText_LoadPage(page: Pointer?): Pointer?

    @JvmStatic external fun FPDFText_ClosePage(textPage: Pointer?)

    @JvmStatic external fun FPDFText_CountChars(textPage: Pointer?): Int

    /** An `unsigned int`: compare it as unsigned. */
    @JvmStatic external fun FPDFText_GetUnicode(textPage: Pointer?, index: Int): Int

    @JvmStatic external fun FPDFText_IsGenerated(textPage: Pointer?, index: Int): Int

    @JvmStatic external fun FPDFText_GetTextObject(textPage: Pointer?, index: Int): Pointer?

    @JvmStatic external fun FPDFText_GetCharOrigin(textPage: Pointer?, index: Int, x: DoubleArray?, y: DoubleArray?): Int

    @JvmStatic external fun FPDFText_GetCharBox(
        textPage: Pointer?,
        index: Int,
        left: DoubleArray?,
        right: DoubleArray?,
        bottom: DoubleArray?,
        top: DoubleArray?,
    ): Int

    /** [rect] receives an `FS_RECTF` ([FsRectF.SIZE] floats). */
    @JvmStatic external fun FPDFText_GetLooseCharBox(textPage: Pointer?, index: Int, rect: FloatArray?): Int

    // fpdf_ppo.h
    @JvmStatic external fun FPDF_ImportPagesByIndex(destDocument: Pointer?, sourceDocument: Pointer?, pageIndices: IntArray?, length: NativeLong, index: Int): Int

    // fpdf_signature.h
    @JvmStatic external fun FPDF_GetSignatureCount(document: Pointer?): Int
}
