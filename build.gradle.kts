plugins {
    // AGP 8.9+ is required for compileSdk 36 (Android 16)
    id("com.android.application") version "8.9.0" apply false
    id("com.android.library") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
    // Kotlin 2.0+ compose compiler plugin (replaces kotlinCompilerExtensionVersion)
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
