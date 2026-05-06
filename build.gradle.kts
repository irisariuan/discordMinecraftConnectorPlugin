// Root project -- each subproject configures itself

allprojects {
    repositories {
        maven { url = uri("https://libraries.minecraft.net/") }
        maven { url = uri("https://maven.minecraftforge.net/") }
        maven { url = uri("https://maven.neoforged.net/releases") }
        maven { url = uri("https://maven.fabricmc.net/") }
        mavenCentral()
        mavenLocal()
    }
}
