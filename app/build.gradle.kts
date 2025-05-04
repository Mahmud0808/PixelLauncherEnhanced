plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.drdisagree.pixellauncherenhanced"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drdisagree.pixellauncherenhanced"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
        setProperty("archivesBaseName", "PLEnhanced v${defaultConfig.versionName}")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
        buildConfig = true
        aidl = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.recyclerview.selection)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.su.core)
    implementation(libs.su.service)
    implementation(libs.su.nio)
    compileOnly(libs.xposedbridge)
    implementation(libs.jaredrummler.colorpicker)
    implementation(libs.remotepreferences)
    implementation(libs.circleimageview)
}