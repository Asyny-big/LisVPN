plugins {
    alias(libs.plugins.lisvpn.android.library)
    alias(libs.plugins.lisvpn.android.library.compose)
}

android {
    namespace = "com.lisvpn.android.core.ui"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
