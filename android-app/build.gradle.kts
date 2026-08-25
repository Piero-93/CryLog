// AGP 9 include il supporto Kotlin: applicare anche org.jetbrains.kotlin.android
// registrerebbe una seconda estensione "kotlin" e il build fallirebbe.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
}
