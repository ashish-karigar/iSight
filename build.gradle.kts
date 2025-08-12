// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.10.0" apply false
    id("org.jetbrains.kotlin.android") version "1.8.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "1.8.20" apply false
    id("com.google.gms.google-services") version "4.4.0" apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

ext {
    versionCompiler = 34
    versionTarget = 34
    versionBuildTool = "34.0.0"
    minSdkVersion = 21
    versionCode = 1
    versionNameString = "1.0.0"
    androidXVersion = "1.6.1"
    constraintlayoutVersion = "2.1.4"
    materialVersion = "1.9.0"
    javaSourceCompatibility = JavaVersion.VERSION_1_8
    javaTargetCompatibility = JavaVersion.VERSION_1_8
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}