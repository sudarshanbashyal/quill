// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // :wear only — the review screen's Compose compiler. Declared here so the module can apply it
    // without repeating a version.
    alias(libs.plugins.kotlin.compose) apply false
}