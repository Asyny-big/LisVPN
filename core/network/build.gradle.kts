plugins {
    alias(libs.plugins.lisvpn.android.library)
    alias(libs.plugins.lisvpn.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lisvpn.android.core.network"
}

dependencies {
    api(project(":core:common"))
    api(libs.bundles.ktor.client)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
