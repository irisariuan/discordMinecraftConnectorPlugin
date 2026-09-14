pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.neoforged.net/releases") }
        maven { url = uri("https://libraries.minecraft.net/") }
        mavenCentral()
    }
}

plugins {
    // Lets Gradle download the Java 21 toolchain automatically when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "minecraftDiscordConnector"
include("common", "paper", "neoforge", "fabric")
