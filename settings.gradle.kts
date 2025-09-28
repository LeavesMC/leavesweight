plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "leavesweight"

include("leavesweight-core", "paperweight-lib", "leavesweight-userdev")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
