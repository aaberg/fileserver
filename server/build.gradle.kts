plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.ktor.plugin")
    id("org.graalvm.buildtools.native")
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
    implementation(libs.ktor.server.status.pages)
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
            imageName.set("fileserver")
            buildArgs.add("--initialize-at-build-time=org.sqlite.util.ProcessRunner")
            buildArgs.add("-H:IncludeResources=logback\\.xml")
        }
    }
}
