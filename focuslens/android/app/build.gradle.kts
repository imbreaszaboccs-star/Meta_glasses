import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

/** android/local.properties (gitignored): SDK paths + Meta DAT ids + backend URL/token. */
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

/** android/key.properties (gitignored): release signing. Falls back to debug signing when missing. */
val keyProps = Properties().apply {
    rootProject.file("key.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.focuslens.focuslens"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.focuslens.focuslens"
        minSdk = 31 // same as the Meta DAT sample apps
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // Meta Wearables DAT: "0" = Developer Mode. For a release channel put the real values from
        // Wearables Developer Center into local.properties (mwdat_application_id / mwdat_client_token).
        manifestPlaceholders["mwdat_application_id"] = localProps.getProperty("mwdat_application_id", "0")
        manifestPlaceholders["mwdat_client_token"] = localProps.getProperty("mwdat_client_token", "0")

        // Backend (backend/README.md). The token is the shared APP_TOKEN from backend/.env.
        buildConfigField("String", "API_URL", quoted(localProps.getProperty("focuslens.api_url", "https://lens.projectviridis.dev")))
        buildConfigField("String", "API_TOKEN", quoted(localProps.getProperty("focuslens.api_token", "")))
    }

    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // R8 stays off until keep rules for the Meta DAT SDK are verified on a device.
            isMinifyEnabled = false
            isShrinkResources = false
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
    implementation("com.meta.wearable:mwdat-motion:$mwdat")
    implementation("com.meta.wearable:mwdat-speech:$mwdat")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // Todoist/Notion tokens, encrypted on the phone.
    implementation("androidx.security:security-crypto:1.1.0")
    // Wake word "hey lens", offline. Model: tool/get_wake_model.ps1
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub on the JVM; tests of JSON code need the real one.
    testImplementation("org.json:json:20240303")
}
