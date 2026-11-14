import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.focuslens.focuslens"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.focuslens.focuslens"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 31 // jako Meta DAT sample appky
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // Meta Wearables DAT: "0" = Developer Mode. Produkčně hodnoty z Wearables Developer Center do local.properties.
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        manifestPlaceholders["mwdat_application_id"] = localProps.getProperty("mwdat_application_id", "0")
        manifestPlaceholders["mwdat_client_token"] = localProps.getProperty("mwdat_client_token", "0")
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    val mwdat = "1.0.1"
    implementation("com.meta.wearable:mwdat-core:$mwdat")
    implementation("com.meta.wearable:mwdat-camera:$mwdat")
    implementation("com.meta.wearable:mwdat-display:$mwdat")
    implementation("com.meta.wearable:mwdat-inputs:$mwdat")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
