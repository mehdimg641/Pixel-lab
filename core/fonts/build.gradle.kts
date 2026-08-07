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

tasks.withType<Test>().configureEach {
    // The supplied font library lives in directories with Persian names; without an explicit
    // UTF-8 JVM encoding the test worker silently fails to enumerate them.
    systemProperty("file.encoding", "UTF-8")
    systemProperty("sun.jnu.encoding", "UTF-8")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    environment("LANG", "C.UTF-8")
}
