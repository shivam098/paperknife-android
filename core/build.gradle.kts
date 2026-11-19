import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// The engine: a port of PaperKnife for Windows' engine (src/PaperKnife.Core) to Kotlin, which runs
// on the JVM for tests and in the Android app. See docs/PORTING.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`

    // The conformance runner, which the JVM's tests and the app's instrumented test share.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The app adds JNA's Android archive, which carries JNA's native part for Android; the tests
    // use the desktop JAR.
    compileOnly(libs.jna)
    testImplementation(libs.jna)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    dependsOn(rootProject.tasks.named("fetchHostPdfium"))
    systemProperty("jna.library.path", rootProject.layout.buildDirectory.dir("pdfium/host").get().asFile.absolutePath)
    systemProperty("paperknife.root", rootDir.absolutePath)
    maxHeapSize = "2g"
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
