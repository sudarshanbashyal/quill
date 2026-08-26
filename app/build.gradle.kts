plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "mse.quill"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "mse.quill"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // See app/lint.xml — IconDensities is switched off there, with the reasoning.
        lintConfig = file("lint.xml")
    }
}

dependencies {
    // SM-2, the review session and quiz generation, kept in a plain-JVM module so the Wear
    // companion can reuse them and so nothing in there can reach for an Android API.
    implementation(project(":study"))
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.barcode.scanning.common)
    implementation(libs.biometric)
    implementation(libs.work.runtime)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.recyclerview)
    implementation(libs.play.services.nearby)
    // The phone half of the Wear pair: publishes the due-card projection as a DataItem.
    implementation(libs.play.services.wearable)
    implementation(libs.play.services.code.scanner)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}