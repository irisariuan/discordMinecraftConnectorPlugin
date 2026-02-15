plugins {
    java
}
repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.nanohttpd:nanohttpd:2.2.0")
    implementation("com.google.code.gson:gson:2.12.1")
}

java {
    // Using Java 23 as specified by the original project
    toolchain.languageVersion.set(JavaLanguageVersion.of(23))
}