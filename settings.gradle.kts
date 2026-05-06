pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = uri("https://maven.minecraftforge.net/") }
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.neoforged.net/releases") }
        maven { url = uri("https://libraries.minecraft.net/") }
        mavenCentral()
    }
}

rootProject.name = "minecraftDiscordConnector"
include("common", "paper", "neoforge", "forge", "fabric")
