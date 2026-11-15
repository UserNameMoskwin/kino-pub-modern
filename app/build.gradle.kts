import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

// Ключ клиента kino.pub лежит в local.properties и в репозиторий не попадает.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun quoted(key: String) = "\"${local.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "pub.moskwin.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "pub.moskwin.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "CLIENT_ID", quoted("kinopub.clientId"))
        buildConfigField("String", "CLIENT_SECRET", quoted("kinopub.clientSecret"))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.tv:tv-material:1.0.0")
    // Обложки: кэш на диске, загрузка в размере карточки.
    implementation("io.coil-kt:coil-compose:2.7.0")
}
