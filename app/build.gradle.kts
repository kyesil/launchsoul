plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.yesil.launchsoul"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.yesil.launchsoul"
        minSdk = 29
        targetSdk = 37
        versionCode = 16
        versionName = "0.2.11"
    }

    buildTypes {
        release {
            // Signed with the debug key, so anyone can build and install it. A store release needs its own key.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    testImplementation("junit:junit:4.13.2")
}
