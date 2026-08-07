plugins {
    alias(libs.plugins.kotlin.jvm)
    // A saved Look is written to a file and read back, so the module that defines one has to be
    // able to generate its serialiser. Everything else here stays plain data.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:text"))
    api(project(":core:canvas"))
    api(project(":core:render"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
}

tasks.test {
    useJUnitPlatform()
}
