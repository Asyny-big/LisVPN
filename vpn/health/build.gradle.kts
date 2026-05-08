plugins {
    alias(libs.plugins.lisvpn.android.library)
    alias(libs.plugins.lisvpn.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lisvpn.android.vpn.health"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:domain"))
    implementation(project(":vpn:libbox"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}