package paperknife.core.editing

import paperknife.core.TextRunLayout

/**
 * What an applied run edit did to the page's objects.
 *
 * @param removed How many text objects were removed.
 * @param shiftX How far the rest of the run moved, in page space.
 * @param end Where the new text ends along the run's baseline; [trimmedEnd] leaves out trailing spaces.
 * @param hiddenBefore Objects to hide in the original page to compare everything else.
 * @param hiddenAfter The same objects in the edited page.
 */
internal class RunChange(
    val layout: TextRunLayout,
    val edit: TextRunEdit,
    val removed: Int,
    val shiftX: Double,
    val shiftY: Double,
    val end: Double,
    val trimmedEnd: Double,
    val hiddenBefore: List<Int>,
    val hiddenAfter: List<Int>,
) {
    val movedRest: Boolean get() = shiftX != 0.0 || shiftY != 0.0
}
