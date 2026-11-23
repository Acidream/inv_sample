plugins {
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":events-proto"))
    implementation("org.apache.kafka:kafka-clients:4.4.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass = "com.example.simulator.CurrentAccountSimulator"
}
