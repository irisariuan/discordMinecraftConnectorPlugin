plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

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
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation(project(":common"))
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
