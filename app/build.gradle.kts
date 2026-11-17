plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

private val versionCodeValue: Int =
    System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "dev.trustybird.agy"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.trustybird.agy"
        minSdk = 26
        // 28, а не 35: начиная с Android 10 приложения с targetSdk 29+
        // не могут запускать исполняемые файлы из своей папки данных
        // (execve → EACCES). Termux по той же причине держит 28.
        targetSdk = 28
        versionCode = versionCodeValue
        versionName = "0.1.$versionCodeValue"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    signingConfigs {
        // Постоянный ключ, чтобы обновления ставились поверх старой версии
        // без удаления приложения (у CI свой debug-ключ каждый прогон).
        create("stable") {
            storeFile = file("agy-debug.jks")
            storePassword = "agydebug"
            keyAlias = "agy"
            keyPassword = "agydebug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
