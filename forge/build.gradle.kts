plugins {
    id("net.minecraftforge.gradle") version "[6.0,6.2)"
    id("com.gradleup.shadow") version "9.2.2"
}

version = "1.0.0"
group = "io.github.ariuan"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

minecraft {
    mappings("official", "1.21.11")
    runs {
        create("server") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            mods {
                create("discordconnector") {
                    source(sourceSets["main"])
                }
            }
        }
    }
}

repositories {
    maven { url = uri("https://maven.minecraftforge.net/") }
    maven { url = uri("https://libraries.minecraft.net/") }
    mavenCentral()
    mavenLocal()
}

dependencies {
    minecraft("net.minecraftforge:forge:1.21.11-61.1.5")
    // common is published to mavenLocal — run :common:publishToMavenLocal from the root first
    implementation("io.github.ariuan:common:1.0.0")
    compileOnly("org.nanohttpd:nanohttpd:2.2.0")
    shadow("io.github.ariuan:common:1.0.0")
}

configurations["shadow"].apply {
    isTransitive = true
}

tasks.shadowJar {
    archiveClassifier.set("")
    isZip64 = true
    configurations = listOf(project.configurations["shadow"])
    dependencies {
        exclude(dependency("com.google.code.gson:.*"))
        exclude(dependency("org.jetbrains:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<ProcessResources> {
    inputs.property("version", version)
    filesMatching("META-INF/mods.toml") {
        expand("version" to version)
    }
}
