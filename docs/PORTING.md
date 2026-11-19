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
   `Normalize(FormKC)` are `normalizedFormC` and `normalizedFormKC`.
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
   `DotNetParse.hexInt32` for `AllowHexSpecifier`, `DotNetParse.int32` for `int.TryParse`, and for
   `double.TryParse` check the same characters the C# lexer allows before calling `toDouble()`.
7. **Text encodings.** `Encoding.Latin1` is `Charsets.ISO_8859_1` (characters past U+00FF encode
   as "?", as in .NET). Check that UTF-16 and UTF-8 decoding replaces invalid input the way .NET
   does (U+FFFD) wherever the C# code decodes text from a PDF, and test the edge cases it handles.
8. **Exceptions.** Where C# throws and catches (for example `PdfException`, or the read failures
   `DocumentSession` turns into refusals), Kotlin throws and catches exceptions with the same
   meaning and messages. Map catch clauses by meaning: C#'s `IndexOutOfRangeException` is
   `IndexOutOfBoundsException`, `ArgumentOutOfRangeException` is `IndexOutOfBoundsException` or
   `IllegalArgumentException` as the throwing code decides, `InvalidOperationException` is
   `IllegalStateException`.
9. **Messages.** Copy every message shown to users exactly, typographic quotes (“ ” ’) included:
   the corpus compares them.
10. **PDFium.** `:core` calls PDFium's C API through JNA (`compileOnly` in `:core`; the tests use
    the desktop JAR and the app JNA's Android archive). All calls go through `PdfiumLibrary`
    under one global lock, as C#'s `lock (PdfiumLibrary.Sync)` does, because PDFium isn't
    thread-safe. Map C types exactly, because the app runs on 32-bit and 64-bit devices: `int` is
    `Int`, `unsigned long` is `NativeLong` (32 bits on Windows and 32-bit Android), `size_t` is a
    `size_t` `IntegerType` (see `Toolchain.SizeT`), handles are `Pointer`. `FPDF_WIDESTRING` is
    UTF-16LE with a two-byte terminator: pass a `ByteArray` or `Memory`, never JNA's `WString`
    (which is 32-bit `wchar_t` on Linux and Android). Keep callback objects (such as the
    `FPDF_FILEWRITE` used for saving) strongly referenced for the whole call.
11. **Fonts.** The fonts the Windows app compiles in or ships in `Fonts\` (AMS Computer Modern,
    Latin Modern, CM-Super) and the Adobe Glyph List ship in the Android app as assets
    (`assets/fonts/{amsfonts,cm-super,lm}/*.pfb` and `assets/fonts/glyphlist.txt`), which aren't
    files. `BundledFonts` therefore reads them through a source the app sets: the files' paths
    relative to the fonts folder, in ordinal order (amsfonts, then cm-super, then lm, which keeps
    the Windows precedence), and a way to read each and the glyph list. Tests set it to the files
    in `third_party/`.

## Tests

- `ConformanceTests` runs every case in `conformance/cases` as `conformance/README.md` describes.
- Unit tests port the Windows tests the corpus doesn't cover (file syntax, Type 1 fonts, PDF
  tools), and `DotNetSupportTest` checks the helpers above against values computed with .NET.
- The tests find the repository through the `paperknife.root` system property and load PDFium
  from `jna.library.path`; Gradle sets both (see `core/build.gradle.kts`).
