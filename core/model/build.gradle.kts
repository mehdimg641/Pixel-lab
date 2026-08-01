plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
}

tasks.test {
    useJUnitPlatform()
}
