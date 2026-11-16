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
// Номер версии — в apk/version.json: тот же файл читает установленное приложение, когда
// проверяет обновления.
val versionFile = rootProject.file("../apk/version.json").readText()
fun versionField(name: String) =
    Regex("\"$name\"\\s*:\\s*\"?([^\",}\\s]+)").find(versionFile)?.groupValues?.get(1) ?: error("в apk/version.json нет $name")

fun quoted(key: String) = "\"${local.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "pub.moskwin.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "pub.moskwin.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = versionField("versionCode").toInt()
        versionName = versionField("versionName")
        buildConfigField("String", "CLIENT_ID", quoted("kinopub.clientId"))
        buildConfigField("String", "CLIENT_SECRET", quoted("kinopub.clientSecret"))
    }

    // Постоянный ключ подписи лежит вне репозитория, путь и пароль — в local.properties.
    // Обновление ставится поверх только с тем же ключом, поэтому им подписаны обе сборки:
    // так они ещё и ставятся друг поверх друга без потери входа. Без ключа (сборка из
    // исходников на другом компьютере) подпись отладочная.
    val releaseKey = local.getProperty("signing.store")?.let { rootProject.file(it) }?.takeIf { it.exists() }
    val signing = if (releaseKey == null) signingConfigs.getByName("debug") else signingConfigs.create("release") {
        storeFile = releaseKey
        storePassword = local.getProperty("signing.password")
        keyAlias = local.getProperty("signing.alias")
        keyPassword = local.getProperty("signing.password")
    }

    buildTypes {
        debug { signingConfig = signing }
        // На телевизор ставится эта сборка: отладочная в разы медленнее (Compose без оптимизации).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signing
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    // FileProvider: отдаёт скачанное обновление системному установщику.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.tv:tv-material:1.0.0")
    // Обложки: кэш на диске, загрузка в размере карточки.
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Плеер: ExoPlayer с HLS; из media3-ui берётся только поверхность с субтитрами.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
