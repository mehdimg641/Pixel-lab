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
}

tasks.test {
    useJUnitPlatform()
}
