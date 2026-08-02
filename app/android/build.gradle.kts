import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ir.pixellab.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.pixellab.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("file.encoding", "UTF-8")
                // Real Skia rather than a stub, so a screenshot test measures what the phone would
                // actually draw instead of a grid of no-ops.
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

/**
 * Unit tests run on the debug variant only.
 *
 * The screenshot tests compose into a real activity, and the manifest entry for one comes from
 * `ui-test-manifest` — a debug-only dependency, because shipping test scaffolding in a release
 * binary is not something to do for the sake of a duplicate test run. Running the identical tests
 * twice was buying nothing anyway; it only inflated the count.
 */
androidComponents {
    // The replacement API arrives in AGP 9; this is the only spelling 8.7 understands.
    @Suppress("DEPRECATION")
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enableUnitTest = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":engine:android"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // viewModelScope, for the font scan that must not run on the main thread.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.core.ktx)

    // Previews are a debug-only dependency: the tooling that renders them is not wanted in a
    // release binary, and it is several megabytes.
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotest.assertions)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation("junit:junit:4.13.2")
}
