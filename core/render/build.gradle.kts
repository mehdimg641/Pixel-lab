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
    // Lets the completeness test enumerate Effect's sealed subclasses, so an unregistered effect
    // fails the build rather than rendering as nothing.
    testImplementation(kotlin("reflect"))
    // Test-only: proves the compositor's camera matrix agrees with the one the gestures use.
    // Two independent implementations of one camera drift, and the symptom is handles that no
    // longer sit on the artwork they belong to.
    testImplementation(project(":core:canvas"))
}

tasks.test {
    useJUnitPlatform()
}
