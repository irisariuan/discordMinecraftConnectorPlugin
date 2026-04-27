plugins {
    id("fabric-loom") version "1.9-SNAPSHOT"
    id("com.gradleup.shadow") version "8.3.5"
}

version = "1.0.0"
group = "io.github.ariuan"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.4")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.16.9")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.114.1+1.21.4")

    // Bundle common + NanoHTTPD using Fabric's Jar-in-Jar; Gson is provided by MC
    include(project(":common"))
    modImplementation(project(":common"))
    include("org.nanohttpd:nanohttpd:2.2.0")
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
