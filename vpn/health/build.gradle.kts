plugins {
    alias(libs.plugins.lisvpn.android.library)
    alias(libs.plugins.lisvpn.android.hilt)
}

android {
    namespace = "com.lisvpn.android.vpn.health"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:domain"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
}
