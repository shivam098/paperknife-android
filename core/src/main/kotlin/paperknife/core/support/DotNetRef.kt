package paperknife.core.support

/**
 * C#'s `ref` and `out` parameters, which Kotlin lacks. Ported methods keep such a parameter where
 * C# has it: the caller passes a [Ref] and reads [value] after the call, and the method sets
 * [value] wherever C# assigns the parameter (for an `out` parameter, on every path). Where C#
 * callers usually discard an `out` parameter (`out _`), the Kotlin parameter is nullable.
 */
class Ref<T>(var value: T)
