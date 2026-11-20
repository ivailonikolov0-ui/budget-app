plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.spendings"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.spendings"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    // One fixed key for every build, so a new APK installs over the previous one.
    // It only signs this personal app; keep the repository private.
    signingConfigs {
        create("budget") {
            storeFile = rootProject.file("keystore/budget.jks")
            storePassword = "budgetapp"
            keyAlias = "budget"
            keyPassword = "budgetapp"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("budget")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("budget")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
