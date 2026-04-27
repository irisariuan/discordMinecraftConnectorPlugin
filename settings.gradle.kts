pluginManagement {
    repositories {
        maven { url = uri("https://maven.neoforged.net/releases") }
        maven { url = uri("https://maven.fabricmc.net/") }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "minecraftDiscordConnector"
include("common", "paper", "forge", "fabric")

