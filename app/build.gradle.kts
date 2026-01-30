import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.drdisagree.pixellauncherenhanced"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.drdisagree.pixellauncherenhanced"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
        base.archivesName = "PLEnhanced v${defaultConfig.versionName}"
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    var releaseSigning = signingConfigs.getByName("debug")

    try {
        val keystoreProperties = Properties()
        FileInputStream(keystorePropertiesFile).use { inputStream ->
            keystoreProperties.load(inputStream)
        }

        releaseSigning = signingConfigs.create("release") {
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
        }
    } catch (_: Exception) {
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = releaseSigning

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseSigning

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
    implementation(libs.konfetti.xml)
}

tasks.register("printVersionName") {
    println(android.defaultConfig.versionName)
}