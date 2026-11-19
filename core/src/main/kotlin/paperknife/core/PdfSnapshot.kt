package paperknife.core

import com.sun.jna.Memory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * An immutable, fully serialized PDF held in memory. A class with reference identity, as in C#:
 * the session tells snapshots apart by reference. The bytes live on the Java heap; PDFium can't
 * read them in place there (C# pins them), so a document loaded from the snapshot reads a native
 * copy that it keeps for as long as it is open (see [toNativeMemory]).
 */
class PdfSnapshot private constructor(private val data: ByteArray) {
    val length: Int get() = data.size

    /** A copy of the bytes: C#'s `Span` is read-only, and Kotlin has no read-only arrays. */
    val span: ByteArray get() = data.copyOf()

    /** The bytes themselves, for PaperKnife's own reader (C#'s `ReadOnlyMemory<byte>`). Never change them. */
    internal val memory: ByteArray get() = data

    fun writeTo(destination: OutputStream) {
        destination.write(data)
    }

    fun contentEquals(other: PdfSnapshot): Boolean = this === other || data.contentEquals(other.data)

    /** A native copy of the bytes, which PDFium reads in place while a document loaded from it is open. */
    internal fun toNativeMemory(): Memory = Memory(data.size.toLong()).also { it.write(0, data, 0, data.size) }

    companion object {
        /** .NET's `Array.MaxLength`. */
        private const val MAX_LENGTH = 0x7FFFFFC7

        fun fromFile(path: String): PdfSnapshot {
            if (path.isEmpty()) {
                throw IllegalArgumentException("The value cannot be an empty string. (Parameter 'path')")
            }

            RandomAccessFile(File(path), "r").use { stream ->
                val length = stream.length()
                if (length == 0L) {
                    throw PdfException(PdfErrorCode.InvalidFormat, "The file is empty.")
                }

                if (length > MAX_LENGTH) {
                    throw PdfException(PdfErrorCode.OperationFailed, "The file is too large to open (over 2 GB).")
                }

                val data = ByteArray(length.toInt())
                stream.readFully(data)
                return PdfSnapshot(data)
            }
        }

        /** Copies [length] bytes of [bytes] from [offset] (C#'s `ReadOnlySpan<byte>`). */
        fun fromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): PdfSnapshot {
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                throw IndexOutOfBoundsException("offset $offset and length $length are outside an array of ${bytes.size} bytes.")
            }

            if (length == 0) {
                throw PdfException(PdfErrorCode.InvalidFormat, "The file is empty.")
            }

            return PdfSnapshot(bytes.copyOfRange(offset, offset + length))
        }

        /** The stream's bytes, which [ByteArrayOutputStream.toByteArray] already copies. */
        internal fun fromStream(stream: ByteArrayOutputStream): PdfSnapshot {
            if (stream.size() == 0) {
                throw PdfException(PdfErrorCode.InvalidFormat, "The file is empty.")
            }

            return PdfSnapshot(stream.toByteArray())
        }
    }
}
