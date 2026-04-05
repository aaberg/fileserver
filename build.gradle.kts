plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    id("org.graalvm.buildtools.native") version "1.0.0"
}

group = "net.aabergs"
version = "0.0.1"

application {
    mainClass = "net.aabergs.ApplicationKt"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.hikari.cp)
    implementation(libs.sqlite.jdbc)
    implementation(libs.postgresql)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

graalvmNative {
    toolchainDetection.set(false)
    binaries {
        named("main") {
            buildArgs.add("--initialize-at-build-time=org.sqlite.util.ProcessRunner")
        }
    }
}
