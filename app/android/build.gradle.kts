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

    /**
     * How a release build gets signed.
     *
     * Until now there was none, which meant `assembleRelease` produced an APK Android refuses to
     * install — so the only build anyone could actually run was the debug one, at three times the
     * size. That is backwards: the build you hand someone to try should be the small, optimised one.
     *
     * A real key comes from Gradle properties (`pixellab.keystore` and friends), set on whatever
     * machine publishes. When they are absent the debug keystore is used instead, so a release build
     * is installable for testing on any checkout. It is not a *publishable* build — Play requires a
     * key only you hold — and nothing here pretends otherwise.
     *
     * And when *neither* exists the config is not created at all, so the build produces an unsigned
     * release APK rather than failing. That case is not hypothetical: a fresh CI runner has never run
     * a debug build, so `~/.android/debug.keystore` has never been generated, and naming a keystore
     * that is not there fails `validateSigningRelease` before a line of the app is even packaged.
     * A build server assembling a release it will not install should not need a key to succeed.
     */
    val distributionKeystore: File? = run {
        val declared = (project.findProperty("pixellab.keystore") as String?)?.let(::file)
        if (declared != null && declared.exists()) return@run declared
        File(System.getProperty("user.home"), ".android/debug.keystore").takeIf { it.exists() }
    }

    signingConfigs {
        if (distributionKeystore != null) {
            create("distribution") {
                storeFile = distributionKeystore
                if (project.findProperty("pixellab.keystore") != null) {
                    storePassword = project.findProperty("pixellab.storePassword") as String?
                    keyAlias = project.findProperty("pixellab.keyAlias") as String?
                    keyPassword = project.findProperty("pixellab.keyPassword") as String?
                } else {
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        release {
            // R8 on. The debug APK is 179 MB and 62 MB of that is un-minified dex from Compose,
            // MediaPipe and the Kotlin standard library — none of which a user should be asked to
            // download to try the app.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("distribution")
        }
    }

    /**
     * One APK per CPU architecture, plus a universal one.
     *
     * ONNX Runtime and MediaPipe both ship native libraries for four ABIs, and a single APK carrying
     * all four came to 179 MB — of which any given phone uses about a quarter. A split build gives
     * arm64-v8a, which is what every Android phone made in the last several years runs, at a size
     * someone can actually download over a phone connection.
     *
     * The universal APK stays because it is the one to hand to somebody whose device is unknown, and
     * because an emulator on an x86 desktop needs it.
     */
    /**
     * What is left out of the package, and why.
     *
     * ONNX Runtime is 28 MB of native code per architecture and it does **nothing** until a user
     * supplies their own `.onnx` segmentation model — which the app deliberately does not ship,
     * because the classical subject-selection path is what makes cut-outs work on the first launch.
     * Shipping an idle inference engine to everyone so that a handful of people can enable a feature
     * later is the wrong default: it triples the download for no first-run benefit.
     *
     * `OnnxSegmentation.bestIn` degrades to the classical path when the runtime is absent, so a user
     * who does install a model gets a working app rather than a crash — and a build that includes
     * the runtime is one property away:
     *
     * ```
     * ./gradlew assembleRelease -Ppixellab.onnx=true
     * ```
     */
    packaging {
        if (project.findProperty("pixellab.onnx") != "true") {
            jniLibs.excludes += setOf("**/libonnxruntime.so", "**/libonnxruntime4j_jni.so")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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
