plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
}

version = property("modVersion") as String
group = "io.github.ariuan"
base.archivesName.set("minecraftDiscordConnector-paper")

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation(project(":common"))
}

// The plain jar (without :common) is kept out of the way so it cannot overwrite
// the shadow jar, which uses the empty classifier and is the one to ship.
tasks.jar {
    archiveClassifier.set("thin")
}

tasks.shadowJar {
    archiveClassifier.set("")
    // exclude platform-provided classes
    dependencies {
        exclude(dependency("io.papermc.paper:.*"))
        exclude(dependency("net.kyori:.*"))
        exclude(dependency("org.jetbrains:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}
