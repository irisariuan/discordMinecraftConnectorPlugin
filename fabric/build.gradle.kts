plugins {
    id("fabric-loom") version "1.16.1"
}

version = "1.0.0"
group = "io.github.ariuan"

java {
    // Must match the other modules: Minecraft 1.21.11 runs on Java 21, so newer
    // bytecode would fail to load with UnsupportedClassVersionError.
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
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

    // Bundle common + NanoHTTPD using Fabric's Jar-in-Jar; Gson is provided by MC.
    // :common is a plain library, not a mod, so it goes on the normal compile
    // classpath -- modImplementation would make Loom read its jar during
    // configuration, which fails before that jar has been built.
    include(project(":common"))
    implementation(project(":common"))
    include("org.nanohttpd:nanohttpd:2.2.0")
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
