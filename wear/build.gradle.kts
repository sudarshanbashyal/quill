// The Wear OS companion — Quill's only Kotlin module, and the only one that isn't Java.
//
// Why Kotlin here and nowhere else: the Material 3 tile components (`protolayout-material3`) are
// Kotlin-only, with no Java builders at all. The Java-friendly alternative is Material 2.5, which
// would put the watch surface off the design system the rest of the app was migrated onto. The
// full argument is in memory/note.md; the short version is that the language boundary goes at the
// module edge rather than through the middle of a feature.
plugins {
    // Just AGP. There is no `org.jetbrains.kotlin.android` line here and that is not an omission:
    // AGP 9 has built-in Kotlin support, so `com.android.application` already registers the
    // `kotlin` extension. Applying the Kotlin plugin as well fails outright — first "already on
    // the classpath with an unknown version", then "extension with name 'kotlin' already
    // registered" — which is a confusing pair of errors to meet if you expect the AGP 8 setup.
    alias(libs.plugins.android.application)
}

android {
    namespace = "mse.quill.wear"
    compileSdk = 36

    defaultConfig {
        // Deliberately the same applicationId as :app. A Wear app is a separate APK but the same
        // application as far as Play and the Data Layer are concerned, and a mismatch here is the
        // classic reason a DataItem is published successfully and never arrives.
        applicationId = "mse.quill"
        // Wear OS 3 and up. The phone app's 26 is irrelevant here — watches below 30 run an
        // entirely different app model, and supporting them would mean a second UI toolkit.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    // SM-2 and the review session, shared verbatim with the phone rather than reimplemented — the
    // reason the module extraction came first. Java, consumed from Kotlin without ceremony.
    implementation(project(":study"))
    implementation(libs.play.services.wearable)
    implementation(libs.wear.tiles)
    implementation(libs.protolayout)
    implementation(libs.protolayout.expression)
    implementation(libs.protolayout.material3)
    implementation(libs.watchface.complications.data.source.ktx)
    implementation(libs.wear.remote.interactions)
}
