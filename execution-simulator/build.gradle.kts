plugins {
    java
    checkstyle
    alias(libs.plugins.spring.boot)
}

repositories { mavenCentral() }

// Plays the broker in local demos (ADR 0027): a FIX 4.4 acceptor, no Spring. The Boot plugin only
// packages it as one runnable jar, like the services.
dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(libs.quickfixj.core)
    implementation(libs.quickfixj.fix44)
    runtimeOnly("org.slf4j:slf4j-simple")
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configDirectory = rootProject.file("config/checkstyle")
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

tasks.bootJar {
    archiveFileName = "execution-simulator.jar"
    mainClass = "com.example.simulator.ExecutionSimulator"
}
tasks.jar { enabled = false }
