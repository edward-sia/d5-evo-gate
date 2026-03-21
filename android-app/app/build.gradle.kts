import java.util.Properties

plugins {
    id("com.android.application")
}

val gateLocalProperties = Properties().apply {
    val file = rootProject.file("gate.local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun String.escapeForBuildConfig(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.newhaven.gate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.newhaven.gate"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String",
            "GATE_AUTH_PIN",
            "\"${(gateLocalProperties.getProperty("gate.auth.pin") ?: "").escapeForBuildConfig()}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
