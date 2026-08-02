import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ir.pixellab.engine.android"
    compileSdk = 35

    defaultConfig {
        // Typeface.Builder with font variation settings arrived in 26, which the kashida axis needs.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric's native graphics backend runs real Skia, so text shaping and path
                // extraction are exercised for real rather than against stubs.
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("file.encoding", "UTF-8")
                // Points the font and PSD tests at the real samples when they are available;
                // they skip cleanly when they are not, so the suite still runs anywhere.
                System.getenv("PIXELLAB_SAMPLES")?.let { path -> it.systemProperty("pixellab.samples", path) }
            }
        }
    }
}

kotlin {
    compilerOptions {
        // Bytecode target 17 to match compileOptions; the build itself runs on the installed JDK.
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:text"))
    api(project(":core:fonts"))
    api(project(":core:render"))
    api(project(":core:canvas"))
    api(project(":core:editor"))
    api(project(":core:codec"))
    api(project(":core:paint"))
    api(project(":core:imaging"))
    api(project(":core:vector"))
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.robolectric)
    testImplementation("junit:junit:4.13.2")
}
