rootProject.name = "fileserver"
include(":server")
include(":private-api-client")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
