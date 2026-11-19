import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// PDFium chromium/8152 from https://github.com/bblanchon/pdfium-binaries, the build without V8 or
// XFA, so JavaScript in PDFs never runs: the version PaperKnife for Windows and Mac use, so all
// apps read, edit and verify documents alike. Every download is checked against its SHA-256.
val pdfiumRelease = "chromium/8152"
val pdfiumSha256 = mapOf(
    "android-arm" to "026644bb11653491e89ea2f9af490b5d765127bdb1c94aadc27bb3652ed34898",
    "android-arm64" to "97085db0665639f2c7d5453d3b982b16c6bbb450f4c5170a3cf7ec0df7575ab9",
    "android-x64" to "25436b6e5a761b92faae592edcf6c355a802860e842bd34c73cf9f519c9c3e43",
    "android-x86" to "1634cdd043de5df3c59b8a919cfb1ae652dbf00d26cf10019adba8991800605d",
    "linux-x64" to "384ab5c51882408a143a21f9c14dcd2caf1f75280ae41c554d0489b46c3dba9f",
    "mac-univ" to "fc22d1d86246f4ec4e706cd5dc2b46737c7e33ad0f080fefe2e44e1011bb88f0",
    "win-arm64" to "8614b10c6a780acc3ae9fdd425d6b61f9d22ae84301b19e1ac82f573930c217c",
    "win-x64" to "cee2b0e9990bc67c0721774932961b9dbf2766046c10eec925acd4cfba6fdfe3",
)

/** The PDFium archive for a platform, downloaded once and checked. */
fun pdfiumArchive(platform: String): File {
    val file = layout.buildDirectory.file("pdfium/downloads/pdfium-$platform.tgz").get().asFile
    val expected = pdfiumSha256.getValue(platform)
    fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    if (!file.isFile || sha256(file) != expected) {
        file.parentFile.mkdirs()
        val url = "https://github.com/bblanchon/pdfium-binaries/releases/download/${pdfiumRelease.replace("/", "%2F")}/pdfium-$platform.tgz"
        URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        val actual = sha256(file)
        check(actual == expected) { "pdfium-$platform.tgz has the SHA-256 $actual, not $expected." }
    }

    return file
}

/** The PDFium build for the machine running Gradle, which the engine's tests load. */
val hostPdfium: String by lazy {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
    when {
        os.startsWith("windows") -> if (arm) "win-arm64" else "win-x64"
        os.startsWith("mac") -> "mac-univ"
        os.startsWith("linux") && !arm -> "linux-x64"
        else -> error("There is no PDFium build for the tests on $os (${System.getProperty("os.arch")}).")
    }
}

tasks.register<Sync>("fetchHostPdfium") {
    description = "Downloads PDFium for this machine, for the engine's tests."
    from({ tarTree(resources.gzip(pdfiumArchive(hostPdfium))) }) {
        include("bin/*.dll", "lib/*.so", "lib/*.dylib")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("pdfium/host"))
}

tasks.register<Sync>("fetchAndroidPdfium") {
    description = "Downloads PDFium for the app's Android ABIs."
    mapOf(
        "arm64-v8a" to "android-arm64",
        "armeabi-v7a" to "android-arm",
        "x86_64" to "android-x64",
        "x86" to "android-x86",
    ).forEach { (abi, platform) ->
        from({ tarTree(resources.gzip(pdfiumArchive(platform))) }) {
            include("lib/libpdfium.so")
            eachFile { path = "$abi/$name" }
            includeEmptyDirs = false
        }
    }
    into(layout.buildDirectory.dir("pdfium/jniLibs"))
}
