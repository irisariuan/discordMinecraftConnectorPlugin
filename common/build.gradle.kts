plugins {
    java
}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.0.1")
    implementation("org.nanohttpd:nanohttpd:2.2.0")
    implementation("com.google.code.gson:gson:2.12.1")
}
