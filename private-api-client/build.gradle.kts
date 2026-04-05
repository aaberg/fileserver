plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

group = "net.aabergs"
version = "0.0.1"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
