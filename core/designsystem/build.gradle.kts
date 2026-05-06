plugins {
    alias(libs.plugins.lisvpn.android.library)
    alias(libs.plugins.lisvpn.android.library.compose)
}

android {
    namespace = "com.lisvpn.android.core.designsystem"
}

dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
}
