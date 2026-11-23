// Technical building blocks shared by the services (ADR 0022): transactional outbox and relay,
// idempotent consumer, retention, dead-lettering, synchronous command-log sends, wire-value checks.
// No domain rules and no contracts: it imports nothing from com.example.
plugins {
    `java-library`
    checkstyle
}

repositories { mavenCentral() }

dependencies {
    val bom = platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    api(bom)
    testImplementation(bom)

    api("org.springframework.kafka:spring-kafka")
    api("org.springframework:spring-jdbc")
    api("io.micrometer:micrometer-core")
    api(libs.protobuf.java)
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configDirectory = rootProject.file("config/checkstyle")
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}
