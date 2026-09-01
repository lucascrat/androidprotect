import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")         // Kotlin 2.0+ compose compiler
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Lê credenciais de local.properties (não versionado) com fallback para variáveis de ambiente
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "com.androidprotect"
    compileSdk = 36                                    // Android 16 (API 36)

    defaultConfig {
        applicationId = "com.androidprotect"
        minSdk = 21                                    // Android 5.0 — cobre ~99% dos dispositivos
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // Desugaring: permite APIs modernas (java.time etc.) em Android <8.0
        multiDexEnabled = true
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                localProps.getProperty("signing.storeFile")
                    ?: System.getenv("SIGNING_STORE_FILE")
                    ?: "protect.keystore"
            )
            storePassword = localProps.getProperty("signing.storePassword")
                ?: System.getenv("SIGNING_STORE_PASSWORD") ?: ""
            keyAlias = localProps.getProperty("signing.keyAlias")
                ?: System.getenv("SIGNING_KEY_ALIAS") ?: ""
            keyPassword = localProps.getProperty("signing.keyPassword")
                ?: System.getenv("SIGNING_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true                     // ativa ProGuard no release
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // Java 17 é o mínimo exigido pelo AGP 8.9 / Android 16
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=all"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // composeOptions removido: Kotlin 2.0+ gerencia via plugin compose

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf(
            "InvalidFragmentVersionForActivityResult",
            "ObsoleteSdkInt"
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ── AndroidX Core ────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // ── Jetpack Compose (BOM gerencia todas as versões) ──────────────────────
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // ── Google Play Services ─────────────────────────────────────────────────
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // ── CameraX (captura headless + gravação de vídeo) ───────────────────────
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")

    // ── Rede ─────────────────────────────────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── Serialização JSON ─────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // ── WorkManager (watchdog do serviço) ─────────────────────────────────────
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // ── Testes ────────────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
