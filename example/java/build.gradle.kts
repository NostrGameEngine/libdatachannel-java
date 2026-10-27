plugins {
    application
    id("com.gradleup.shadow") version "9.3.1"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("tel.schich:libdatachannel-java-arch-detect")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

application {
    mainClass = "tel.schich.libdatachannel.example.Main"
}
