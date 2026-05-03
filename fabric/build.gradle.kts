plugins {
    id("fabric-loom") version "1.16.1"
    id("com.gradleup.shadow") version "9.4.1"
}

version = "1.0.0"
group = "io.github.ariuan"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(23))
}

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.2")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.3+1.21.11")

    // Bundle common + NanoHTTPD using Fabric's Jar-in-Jar; Gson is provided by MC
    include(project(":common"))
    implementation(project(":common"))
    include("org.nanohttpd:nanohttpd:2.2.0")
    implementation("org.nanohttpd:nanohttpd:2.2.0")
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
