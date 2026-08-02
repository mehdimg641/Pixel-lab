plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
}

tasks.test {
    useJUnitPlatform()
    // Reference files are large and PSD parsing allocates whole channels at a time.
    maxHeapSize = "2g"
    systemProperty("file.encoding", "UTF-8")
    // Points the tests at the real PSDs when they are available; they skip cleanly when not.
    System.getenv("PIXELLAB_SAMPLES")?.let { systemProperty("pixellab.samples", it) }
}
