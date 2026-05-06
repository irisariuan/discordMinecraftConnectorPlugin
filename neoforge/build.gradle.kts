plugins {
    id("net.neoforged.gradle.userdev") version "7.1.25"
    id("com.gradleup.shadow") version "9.4.1"
}

version = "1.0.0"
group = "io.github.ariuan"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    maven { url = uri("https://libraries.minecraft.net/") }
    maven { url = uri("https://maven.neoforged.net/releases") }
    mavenCentral()
}

dependencies {
    implementation("net.neoforged:neoforge:21.11.42")
    // :common must be on compile classpath so NanoHTTPD (transitive) is visible to the IDE
    implementation(project(":common"))
    // Direct compileOnly for NanoHTTPD ensures stop() and other inherited methods resolve
    compileOnly("org.nanohttpd:nanohttpd:2.2.0")
    // Bundle common into the final jar via shadow
    shadow(project(":common"))
}

configurations["shadow"].apply {
    isTransitive = true
}

tasks.shadowJar {
    archiveClassifier.set("")
    configurations = listOf(project.configurations["shadow"])
    // Exclude Gson and other libs already on NeoForge/MC classpath
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
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to version)
    }
}
