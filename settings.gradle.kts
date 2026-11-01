pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Versions live here so IntelliJ puts kotlin-gradle-plugin on the build-script
// classpath. Without that, build.gradle.kts cannot resolve
// org.jetbrains.kotlin.gradle.dsl.JvmTarget until a Gradle reload, and often
// not even then. Reload the Gradle project after pulling this file.
plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.spring") version "2.4.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

rootProject.name = "telegram-mcp-tdlib"
