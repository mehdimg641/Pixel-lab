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
    api(project(":core:canvas"))
    api(project(":core:render"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
}

tasks.test {
    useJUnitPlatform()
}
