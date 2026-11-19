package paperknife.app

import android.content.res.AssetManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import paperknife.core.conformance.CaseOutcome
import paperknife.core.conformance.ConformanceRunner
import paperknife.core.conformance.CorpusFiles
import paperknife.core.fonts.BundledFonts
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * The conformance corpus (conformance/README.md) on Android: every case, in the app's process, with
 * Android's PDFium, ART and JNA, through the [ConformanceRunner] the engine's ConformanceTests run
 * the corpus through on the JVM, so both compare by the same rules. Android isn't Windows, so the
 * README's rules for other systems apply. The corpus is this test's assets (app/build.gradle.kts),
 * and what the runner logs, with each case's time, goes to logcat under [TAG].
 */
@RunWith(AndroidJUnit4::class)
class DeviceConformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun theAppReadsTheFontsItShips() {
        checkBundledFonts()
    }

    @Test
    fun storedCasesGiveTheStoredObservations() {
        checkBundledFonts()
        val runner = ConformanceRunner(corpus(instrumentation.context.assets), log = { text -> log(Log.INFO, text) })
        val ids = runner.caseIds()
        assertFalse("The test's assets have no cases.", ids.isEmpty())
        assertEquals("The cases INDEX.md lists", runner.indexedCaseIds().sorted(), ids)

        val started = System.nanoTime()
        val outcomes = ids.map { id ->
            runner.run(id).also { outcome ->
                if (outcome.passed) {
                    log(Log.INFO, "$id passed in ${outcome.milliseconds} ms.")
                } else {
                    log(Log.ERROR, "$id failed in ${outcome.milliseconds} ms.\n${outcome.message}")
                }
            }
        }

        val milliseconds = (System.nanoTime() - started) / 1_000_000
        val failed = outcomes.filter { outcome -> !outcome.passed }
        val slowest = outcomes.sortedByDescending { outcome -> outcome.milliseconds }.take(5)
        log(
            Log.INFO,
            "${outcomes.size} cases ran in $milliseconds ms: ${outcomes.size - failed.size} passed, ${failed.size} failed. " +
                "The slowest: ${slowest.joinToString(", ") { outcome -> "${outcome.id} (${outcome.milliseconds} ms)" }}.",
        )
        if (failed.isNotEmpty()) {
            fail("${failed.size} of ${outcomes.size} cases failed on Android:\n\n" + failed.joinToString("\n\n", transform = CaseOutcome::message))
        }
    }

    /**
     * Checks that the engine reads the fonts and the Adobe Glyph List from the app's assets, which
     * the cases with TeX fonts need. The test runs in the app's process, whose PaperKnifeApplication
     * set [BundledFonts.source] when it started; this checks it lists the fonts the way
     * PaperKnifeApplication does and reads the same bytes.
     */
    private fun checkBundledFonts() {
        val application = instrumentation.targetContext.applicationContext
        assertTrue("The application is ${application.javaClass.name}, not PaperKnifeApplication.", application is PaperKnifeApplication)
        val assets = application.assets
        val fonts = listOf("amsfonts", "cm-super", "lm").flatMap { family ->
            assets.list("fonts/$family").orEmpty().filter { name -> name.endsWith(".pfb") }.map { name -> "$family/$name" }
        }.sorted()
        assertTrue("The app ships no fonts.", fonts.isNotEmpty())

        val source = BundledFonts.source
        assertEquals("The fonts BundledFonts reads", fonts, source.fonts)
        for (path in listOf(fonts.first(), fonts.last(), "lm/lmr10.pfb")) {
            assertArrayEquals(path, read(assets.open("fonts/$path")), read(source.openFont(path)))
        }

        assertArrayEquals("glyphlist.txt", read(assets.open("fonts/glyphlist.txt")), read(source.openGlyphList()))
    }

    private fun read(stream: InputStream?): ByteArray {
        assertNotNull("The stream doesn't open.", stream)
        return stream!!.use { opened -> opened.readBytes() }
    }

    /** The corpus in the test's assets, where app/build.gradle.kts puts conformance/. */
    private fun corpus(assets: AssetManager): CorpusFiles = object : CorpusFiles {
        override fun read(path: String): ByteArray? = try {
            assets.open(path).use { stream -> stream.readBytes() }
        } catch (e: FileNotFoundException) {
            null
        }

        override fun list(folder: String): List<String> = assets.list(folder).orEmpty().toList()
    }

    /** Logs [text] line by line, as logcat cuts long entries. */
    private fun log(priority: Int, text: String) {
        for (line in text.lines()) {
            Log.println(priority, TAG, line)
        }
    }

    companion object {
        /** The logcat tag of what the corpus run logs. */
        const val TAG = "PaperKnifeCorpus"
    }
}
