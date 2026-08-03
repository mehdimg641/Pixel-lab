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
                // The layer-style compositor works in float RGBA — sixteen bytes a pixel — and a
                // style holds several intermediates at once, so a cover-sized render is measured in
                // hundreds of megabytes. The default heap is not enough to render one at the size
                // the app actually exports at, and rendering it at preview size instead would be a
                // test that never asks the question.
                it.maxHeapSize = "4g"
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
    api(project(":core:mesh"))
    implementation(libs.androidx.annotation)
    api(project(":core:ai"))
    // The inference runtime. Heavy — several megabytes of native library per ABI — and worth it:
    // it is what makes a professional segmentation network run on the device at all.
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.robolectric)
    testImplementation("junit:junit:4.13.2")
}
