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
    implementation(project(":core:text"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
}

tasks.test {
    useJUnitPlatform()
}
