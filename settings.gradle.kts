plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "savings-platform"

include(
    "events-proto", "platform-messaging", "savings-service", "transfer-service", "current-account-service",
    "investment-service", "fix-gateway", "execution-simulator",
)
