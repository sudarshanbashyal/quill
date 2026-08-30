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
    // The Compose compiler, required from Kotlin 2.0 once `buildFeatures.compose` is on. This one
    // *does* belong here, unlike `kotlin.android` above — AGP 9 registers the Kotlin extension but
    // not the Compose compiler, so without this line configuration fails outright.
    alias(libs.plugins.kotlin.compose)
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

    buildFeatures {
        // The review screen. Nothing else in the module is Compose — the tile and the complication
        // are ProtoLayout, which is a description of a layout rendered in another process, not a
        // view hierarchy this app ever holds.
        compose = true
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

    // The review screen. The BOM governs the androidx.compose.* artifacts only; the two
    // wear.compose lines are versioned independently and are deliberately outside it.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.compose.material.icons.core)

    constraints {
        // Nothing in this module uses a Fragment — the two screens are ComponentActivity +
        // Compose. But play-services-base drags androidx.fragment:fragment 1.1.0 onto the
        // classpath, and with no AppCompat here to outrank it (:app resolves 1.6.2 that way)
        // it stays there. That trips lintVital's InvalidFragmentVersionForActivityResult on
        // `registerForActivityResult` and fails assembleRelease outright.
        //
        // A constraint rather than an `implementation` line: the artifact is already being
        // packaged transitively, so this raises the version that ships instead of adding a
        // dependency the module does not otherwise want.
        implementation(libs.fragment) {
            because("play-services-base pins fragment 1.1.0; ActivityResult needs 1.3.0+")
        }
    }
}
