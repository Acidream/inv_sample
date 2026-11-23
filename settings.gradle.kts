plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "savings-platform"

include("events-proto", "savings-service", "transfer-service", "current-account-service")
