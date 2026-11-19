package paperknife.core

enum class EditStatus {
    Applied,
    NoChange,
    Rejected,
}

enum class EditRejection {
    None,
    InvalidTarget,
    UnsupportedCharacters,
    EditingRestricted,
    PageNotEditable,
    CollateralChange,
    EngineError,
}

/** The outcome of an edit. A rejected edit leaves the document exactly as it was. */
class EditResult private constructor(
    val status: EditStatus,
    val rejection: EditRejection,
    /** A user-facing explanation of what happened. */
    val message: String,
    val warnings: List<String>,
    /** For [EditRejection.UnsupportedCharacters]: the characters that can't be used. */
    val unsupportedCharacters: List<String>,
    /** For applied text edits: the edited run as it exists in the committed document; null when the text was deleted. */
    val textRun: PdfTextRun?,
    details: List<String>? = null,
) {
    /** For applied edits: more about what changed that isn't a warning, such as a font PaperKnife completed. */
    val details: List<String> = details ?: emptyList()

    companion object {
        internal fun applied(message: String, warnings: List<String>? = null, textRun: PdfTextRun? = null, details: List<String>? = null): EditResult =
            EditResult(EditStatus.Applied, EditRejection.None, message, warnings ?: emptyList(), emptyList(), textRun, details)

        internal fun noChange(message: String): EditResult =
            EditResult(EditStatus.NoChange, EditRejection.None, message, emptyList(), emptyList(), null)

        internal fun rejected(rejection: EditRejection, message: String, unsupportedCharacters: List<String>? = null): EditResult =
            EditResult(EditStatus.Rejected, rejection, message, emptyList(), unsupportedCharacters ?: emptyList(), null)
    }
}
