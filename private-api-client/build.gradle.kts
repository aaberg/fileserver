plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
}

group = "net.aabergs"
version = (findProperty("version") as String?) ?: "0.0.1"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("gpr") {
            from(components["java"])
            artifactId = "private-api-client"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            val repository = System.getenv("GITHUB_REPOSITORY")
            url = uri("https://maven.pkg.github.com/$repository")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
