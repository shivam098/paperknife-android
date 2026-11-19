# Porting PaperKnife's engine to Kotlin

PaperKnife for Android uses a port of the engine of
[PaperKnife for Windows](https://github.com/shivam098/paperknife-windows) (`src/PaperKnife.Core`):
the same types, algorithms, checks and messages, so every shortcoming fixed there stays fixed here.
The [conformance corpus](../conformance/README.md) checks that the port observes exactly what the
Windows engine observes; `conformance/SOURCE` names the Windows commit the corpus and the port
follow. PaperKnife for Mac ports the same engine to Swift.

## Layout and names

- `:core` mirrors `src/PaperKnife.Core`: package `paperknife.core` with the same sub-packages as
  the C# namespaces (`paperknife.core.syntax`, `.content`, `.fonts`, `.editing`, `.pdfium`) and
  one Kotlin file per C# file, with the same name. `paperknife.core.support` holds the helpers
  below.
- Types keep their C# names (`PdfFile`, `CosDict`, `ContentPage`, `FontCompletion`,
  `DocumentSession`). Members use the C# name in lower camel case (`GetTextRuns` →
  `getTextRuns`, `TryOpen` → `tryOpen`) with the C# parameters in the same order. C# properties
  are Kotlin properties. Port the comments too.
- C# `internal` is Kotlin `internal`, `public` is `public`, `private` is `private`.
- `:core` is a plain JVM library: no Android APIs, so it runs in JVM tests and in the app alike.
  It calls PDFium through JNA (see below).

### Shapes C# has and Kotlin doesn't

The ported layers settled these; follow them so the layers fit together.

- **`out` and `ref` parameters** become a `Ref<T>` holder (`support/DotNetRef.kt`) in the same
  position, for example `PdfFile.tryOpen(data, problem)`. Where C# callers usually pass `out _`,
  the Kotlin parameter is nullable. A C# `bool TryX(…, out T value)` that only returns a value
  may instead return `T?`, null for false (`TextLayout.tryJoin`).
- **Tuples** become small data classes (`PdfPoint`, `ShowSegment`, `PagePoint`, `PixelSize`).
- **Spans** become an array with an offset and a length (`StreamFilters.decode(data, offset,
  length, …)`). `PdfSnapshot.span` returns a copy, because Kotlin has no read-only arrays; the
  engine's own code reads `PdfSnapshot.memory`, the array itself, and never changes it.
- **`IDisposable`** is `AutoCloseable`: `Dispose()` is `close()`.
- **Constants** are `UPPER_SNAKE_CASE` (`MAX_OBJECT_NUMBER`); static singletons keep their role
  (`CosNull.instance`, `PdfMatrix.identity`). A member named after a Kotlin keyword is quoted
  (`` PsToken.`is` ``).
- **Records** compare their `float` and `double` fields as C# does: 0 equals -0, and NaN equals
  NaN (`support/DotNetFloat.kt`, `support/DotNetDouble.kt`). Kotlin data classes compare doubles
  with `equals`, which differs on both counts, so records holding floating point numbers
  implement `equals` and `hashCode` themselves (see `PdfRect`, `PdfMatrix`).

## Semantics that must not change

Kotlin is closer to C# than most languages: strings are UTF-16, `==` on strings and `compareTo`
are ordinal, `Int` is 32 bits and wraps, `toInt()` on floating point saturates and gives 0 for
NaN, `sortedBy` is stable like LINQ's `OrderBy`, and `distinct()` keeps first occurrences. These
are the places where it still differs. Ported code follows these rules everywhere.

1. **Bytes are signed in Kotlin.** C#'s `byte` is 0 to 255; Kotlin's `Byte` is -128 to 127. Read
   a byte as `bytes[i].toInt() and 0xFF` wherever C# uses its value (comparisons, arithmetic,
   indexes, `switch`). Compare to byte literals with that int (`b == 0x80`), never
   `bytes[i] == 0x80.toByte()` mixed with unmasked values. File data is `ByteArray`; where C# uses
   spans, pass the array with offset and length.
2. **`Substring(start, length)` versus `substring(start, end)`.** Use
   `substringOfLength(start, length)` for C#'s `Substring` with two arguments. One argument means
   the same in both.
3. **White space and character classes.** Kotlin's `Char.isWhitespace()`, `trim()`, `isBlank()`
   and `lines()` use a different set of white space than C#. Use `DotNetChar.isWhiteSpace`,
   `dotNetTrim`, `dotNetTrimStart`, `dotNetTrimEnd` and `isDotNetWhiteSpace`. Unicode categories
   are `DotNetChar.category`; `char.IsControl` is `DotNetChar.isControl`. `Normalize(FormC)` and
   `Normalize(FormKC)` are `strictNormalizedFormC` and `strictNormalizedFormKC` (or
   `DotNetNormalization.formC`/`formKC`): like .NET they throw `IllegalArgumentException` for an
   unpaired surrogate or U+FFFE, which C# code catches to give up on text it can't read. The
   lenient `normalizedFormC` and `normalizedFormKC` never throw; use them only where the C# code
   can't receive such text.
4. **Ordered collections.** C#'s `Dictionary` and `HashSet` enumerate in insertion order, except
   that an entry added after a removal takes the most recently freed place. `LinkedHashMap` and
   `LinkedHashSet` (Kotlin's `mutableMapOf`, `mutableSetOf`, `associate…`, `groupBy`) keep
   insertion order but append such entries at the end. Use `DotNetDictionary` and
   `DotNetHashSet` wherever the C# code removes entries and later enumerates the collection;
   otherwise the linked collections are exact. Never use `HashMap` or `HashSet` where the order
   is enumerated. A C# `List.Sort` or `Array.Sort` with equal keys is unstable in .NET; flag such
   code rather than guess.
5. **Rounding.** `Math.Round(x)` rounds ties to even: `DotNetMath.round` (or `Math.rint`), never
   `Math.round`, `roundToInt` or `roundToLong`, which round halves up. `Math.Round(x, digits[,
   mode])` scales by a power of 10: `DotNetMath.round(x, digits, awayFromZero)`;
   `MidpointRounding.AwayFromZero` alone is `DotNetMath.roundAwayFromZero`. Keep `float` as
   `Float` and `double` as `Double` exactly where C# uses them; PDFium works in `float`, and
   `TextShowSimulator` sums line positions in `float` on purpose. Mixed `float`/`double`
   arithmetic must convert where C# converts.
6. **Formatting and parsing numbers.** Never use `toString()` or string templates for numbers that
   end up in a PDF or a message: Kotlin writes `1.0E10` where C# writes `1E+10`. `CosWriter.Number`
   is `DotNetMath.formatUpTo10Decimals(DotNetMath.round(value, 10))`, with "-0" written as "0".
   Messages that format numbers use the invariant culture's format, which the corpus records
   (`String.format(Locale.ROOT, …)` where it matches). Parse only the syntax the C# code accepts:
   `DotNetParse.hexInt32` for `AllowHexSpecifier`, `DotNetParse.int32` for `int.TryParse`,
   `DotNetParse.signedInt32` and `DotNetParse.finiteDouble` where the lexer parses numbers.
7. **Text encodings.** `Encoding.Latin1.GetString` is `String(bytes, Charsets.ISO_8859_1)`.
   `Encoding.Latin1.GetBytes` agrees with `Charsets.ISO_8859_1` for characters up to U+00FF,
   which is all the engine encodes (PDF syntax written by `CosWriter`: operators, numbers, and
   names and strings whose characters are bytes). Above U+00FF they differ: .NET maps characters
   to look-alikes (Ł to "L", ’ to "'", “ to `"`) and the rest to "?", one per UTF-16 code unit
   (so an emoji gives "??"), while Java writes one "?". Never let ported code encode such
   characters. `Encoding.UTF8` is `DotNetUtf8` and `Encoding.BigEndianUnicode.GetString` is
   `DotNetEncoding.bigEndianUnicode`: Java's decoders replace invalid input differently from
   .NET (U+FFFD per invalid sequence, and U+FFFE kept).
8. **Exceptions.** Where C# throws and catches (for example `PdfException`, or the read failures
   `DocumentSession` turns into refusals), Kotlin throws and catches exceptions with the same
   meaning and messages. Map catch clauses by meaning: C#'s `IndexOutOfRangeException` is
   `IndexOutOfBoundsException`, `ArgumentOutOfRangeException` is `IndexOutOfBoundsException` or
   `IllegalArgumentException` as the throwing code decides, `InvalidOperationException` is
   `IllegalStateException`.
9. **Messages.** Copy every message shown to users exactly, typographic quotes (“ ” ’) included:
   the corpus compares them.
10. **PDFium.** `:core` calls PDFium's C API through JNA's direct mapping
    (`pdfium/PdfiumNative.kt`, `Native.register`), which is much faster than interface mapping
    for the many calls per character `TextLayout` makes (`compileOnly` in `:core`; the tests use
    the desktop JAR and the app JNA's Android archive). All calls go through `PdfiumLibrary`
    under one global lock, `PdfiumLibrary.sync`, as C#'s `lock (PdfiumLibrary.Sync)` does,
    because PDFium isn't thread-safe. Map C types exactly, because the app runs on 32-bit and
    64-bit devices: `int` is `Int`, `unsigned long` is `NativeLong` (32 bits on Windows and
    32-bit Android), `size_t` is `SizeT`, handles are `Pointer`. `FPDF_WIDESTRING` is UTF-16LE
    with a two-byte terminator (`PdfiumNative.wideString`), never JNA's `WString` (which is
    32-bit `wchar_t` on Linux and Android). PDFium reads a document's bytes until it is closed,
    so they live in native memory (`Memory`) for that long: never pass a Java array to
    `FPDF_LoadMemDocument64`, because JNA frees its copy when the call returns. Keep callback
    objects (such as the `FPDF_FILEWRITE` used for saving) strongly referenced for the whole
    call. PDFium's last error is read with `PdfiumNative.lastError()`: on Windows the JVM
    clears the thread's error between native calls.
11. **Fonts.** The fonts the Windows app compiles in or ships in `Fonts\` (AMS Computer Modern,
    Latin Modern, CM-Super) and the Adobe Glyph List ship in the Android app as assets
    (`assets/fonts/{amsfonts,cm-super,lm}/*.pfb` and `assets/fonts/glyphlist.txt`), which aren't
    files. `BundledFonts.source` is therefore a `FontSource` the app sets: the fonts' paths
    relative to the fonts folder (read in ordinal order: amsfonts, then cm-super, then lm, which
    keeps the Windows precedence), a way to open each, and a way to open the glyph list. Tests set
    it to the files in `third_party/`.

## Additions

The Android app saves through content URIs, where `DocumentSession.saveAs`'s temporary file and
rename aren't possible, so `DocumentSession.saveTo(OutputStream)` writes the current snapshot to
a stream and, once that succeeded, records it as saved. It is the only member PaperKnife for
Windows doesn't have.

## Tests

- `ConformanceTests` runs every case in `conformance/cases` as `conformance/README.md` describes,
  observing the session as the Windows engine's `CorpusRecorder` does.
- Unit tests port the Windows tests the corpus doesn't cover (file syntax, Type 1 fonts, PDF
  tools, saving, undo restoring the very snapshots, and the memory viewing pages keeps), and
  `DotNetSupportTest` and its siblings check the helpers above against values computed with .NET.
- The tests find the repository through the `paperknife.root` system property and load PDFium
  from `jna.library.path`; Gradle sets both (see `core/build.gradle.kts`). Files they write go
  in `core/build/test-folders`.
