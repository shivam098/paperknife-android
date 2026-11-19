package paperknife.core.editing

import paperknife.core.support.dotNetTrimEnd

/**
 * The smallest change to a run's text objects that gives it new text. The objects [first] to
 * [last] (positions in the run) are replaced by one object drawing [newText], which may be empty;
 * objects before them keep their place, and objects after them keep their characters and move
 * along the baseline as a block.
 */
internal data class TextRunEdit(val first: Int, val last: Int, val newText: String) {
    /** Whether this edit removes every object of a run of [count] objects. */
    fun deletesAll(count: Int): Boolean = first == 0 && last == count - 1 && newText.isEmpty()

    companion object {
        /** Removes all [count] objects of a run, spaces drawn at its end included. */
        fun deleteAll(count: Int): TextRunEdit = TextRunEdit(0, count - 1, "")

        /**
         * @param pieces The text of each object in the run, in order.
         * @param separators The text between consecutive objects: a space PDFium found in the gap, or nothing.
         * @param newText The run's new text, without trailing whitespace.
         */
        fun plan(pieces: List<String>, separators: List<String>, newText: String): TextRunEdit {
            if (pieces.isEmpty()) {
                throw IllegalArgumentException("pieces.Count ('0') must be a non-zero value. (Parameter 'pieces.Count')\nActual value was 0.")
            }

            val starts = IntArray(pieces.size)
            val ends = IntArray(pieces.size)
            val builder = StringBuilder()
            for (i in pieces.indices) {
                starts[i] = builder.length
                builder.append(pieces[i])
                ends[i] = builder.length
                if (i < separators.size) {
                    builder.append(separators[i])
                }
            }

            val old = builder.toString()

            // Trailing whitespace isn't part of the editable text; it stays as it is.
            val target = newText + old.substring(old.dotNetTrimEnd().length)

            var prefix = 0
            val limit = minOf(old.length, target.length)
            while (prefix < limit && old[prefix] == target[prefix]) {
                prefix++
            }

            var suffix = 0
            while (suffix < limit - prefix && old[old.length - (suffix + 1)] == target[target.length - (suffix + 1)]) {
                suffix++
            }

            // Never split a surrogate pair between the kept and the changed text.
            if (prefix > 0 && old[prefix - 1].isHighSurrogate()) {
                prefix--
            }

            if (suffix > 0 && old[old.length - suffix].isLowSurrogate()) {
                suffix--
            }

            // The object containing a position, or the one before the gap containing it.
            fun pieceAtOrBefore(position: Int): Int {
                var piece = 0
                while (piece + 1 < starts.size && starts[piece + 1] <= position) {
                    piece++
                }

                return piece
            }

            // The object containing a position, or the one after the gap containing it.
            fun pieceAtOrAfter(position: Int): Int {
                var piece = 0
                while (piece + 1 < ends.size && ends[piece] <= position) {
                    piece++
                }

                return piece
            }

            val first: Int
            val last: Int
            val changedEnd = old.length - suffix
            if (changedEnd > prefix) {
                first = pieceAtOrBefore(prefix)
                last = pieceAtOrAfter(changedEnd - 1)
            } else {
                // Pure insertion: it joins the object it follows, or the next object after a gap.
                first = if (prefix == 0) 0 else pieceAtOrAfter(prefix - 1)
                last = first
            }

            val newEnd = ends[last] + (target.length - old.length)
            return TextRunEdit(first, last, target.substring(starts[first], newEnd))
        }
    }
}
