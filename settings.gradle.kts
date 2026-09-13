pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.21"
        id("org.jetbrains.kotlin.android") version "2.1.21"
        id("com.android.application") version "8.7.2"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "us-stock-investment-assistant"

// Temporary verification module only — not a production app.
include("android-smoke")
