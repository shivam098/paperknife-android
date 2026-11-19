// The Android app: Jetpack Compose UI around the engine in :core.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val generatedAssets = layout.buildDirectory.dir("generated/paperknife/assets")

// The version: the Release workflow passes -PpaperknifeVersion=0.5.1 (the tag without its "v");
// other builds are 0.0.0-dev.
val paperknifeVersion = (findProperty("paperknifeVersion") as String?)?.removePrefix("v") ?: "0.0.0-dev"

/**
 * The version code Android compares updates by: 0.5.1 is 50199, and its pre-releases, such as
 * 0.5.1-beta.2, come before it (50102). Development builds are 1.
 */
fun versionCodeOf(version: String): Int {
    if (version == "0.0.0-dev") {
        return 1
    }

    val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-[A-Za-z]+\.?(\d+))?""").matchEntire(version)
        ?: throw GradleException("The version $version isn't like 0.5.1 or 0.5.1-beta.2.")
    val (major, minor, patch, pre) = match.destructured
    require(minor.toInt() < 100 && patch.toInt() < 100 && (pre.isEmpty() || pre.toInt() in 1..98)) { "The version $version is out of range." }
    return (major.toInt() * 1_000_000) + (minor.toInt() * 10_000) + (patch.toInt() * 100) + (if (pre.isEmpty()) 99 else pre.toInt())
}

android {
    namespace = "io.github.shivam098.paperknife"
    compileSdk = 35

    defaultConfig {
        // Never change the application ID: Android identifies the app, and its updates, by it.
        applicationId = "io.github.shivam098.paperknife"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOf(paperknifeVersion)
        versionName = paperknifeVersion

        // The ABIs PDFium is built for; JNA also carries some Android no longer runs.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    // The release key, which the Release workflow gives in environment variables from the
    // repository's secrets. Without it, release builds come out unsigned.
    val keystore = System.getenv("PAPERKNIFE_KEYSTORE")
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("PAPERKNIFE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PAPERKNIFE_KEY_ALIAS")
            keyPassword = System.getenv("PAPERKNIFE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // JNA finds PDFium's functions by name, which shrinking would rename.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // PDFium and JNA come stripped; without the NDK, Gradle can't strip them again.
            keepDebugSymbols += "**/*.so"
        }
    }

    sourceSets["main"].apply {
        // PDFium for each ABI, downloaded and checked by the root project's fetchAndroidPdfium.
        jniLibs.srcDir(rootProject.layout.buildDirectory.dir("pdfium/jniLibs"))
        assets.srcDir(generatedAssets)
    }

    // The conformance corpus (cases/, documents/, contents/ and INDEX.md), which the instrumented
    // test DeviceConformanceTest runs on the device; it reads them from the test APK's assets.
    sourceSets["androidTest"].assets.srcDir(rootProject.layout.projectDirectory.dir("conformance"))
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // The conformance runner the engine's tests share (core's test fixtures), for DeviceConformanceTest.
    androidTestImplementation(testFixtures(project(":core")))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// The fonts PaperKnife draws new letters with, the Adobe Glyph List, and the license texts,
// copied from third_party/ into the app's assets.
val bundleAssets = tasks.register<Sync>("bundleAssets") {
    val thirdParty = rootProject.layout.projectDirectory.dir("third_party")
    listOf("amsfonts", "cm-super", "lm").forEach { family ->
        from(thirdParty.dir("$family/fonts")) {
            include("*.pfb")
            into("fonts/$family")
        }
    }

    from(thirdParty.file("agl/glyphlist.txt")) {
        into("fonts")
    }

    from(rootProject.files("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")) {
        into("licenses")
    }

    listOf("pdfium", "jna", "amsfonts", "lm", "cm-super", "agl").forEach { component ->
        from(thirdParty.dir("$component/licenses")) {
            into("licenses/$component")
        }
    }

    into(generatedAssets)
}

tasks.named("preBuild") {
    dependsOn(rootProject.tasks.named("fetchAndroidPdfium"), bundleAssets)
}
