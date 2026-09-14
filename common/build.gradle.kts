plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.ariuan"
version = property("modVersion") as String

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.0.1")
    implementation("com.google.code.gson:gson:2.12.1")
}
