plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

repositories { mavenCentral() }

dependencies {
    api(libs.protobuf.java)
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
}

// Generated code is not ours to lint.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.removeAll { it.startsWith("-Xlint") || it == "-Werror" }
}
