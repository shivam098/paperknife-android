// The Android app: Jetpack Compose UI around the engine in :core.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val generatedAssets = layout.buildDirectory.dir("generated/paperknife/assets")

android {
    namespace = "io.github.shivam098.paperknife"
    compileSdk = 35

    defaultConfig {
        // Never change the application ID: Android identifies the app, and its updates, by it.
        applicationId = "io.github.shivam098.paperknife"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.0-dev"

        // The ABIs PDFium is built for; JNA also carries some Android no longer runs.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    buildFeatures {
        compose = true
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
