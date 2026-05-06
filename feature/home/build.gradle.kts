plugins {
    alias(libs.plugins.lisvpn.android.feature)
}

android {
    namespace = "com.lisvpn.android.feature.home"
}

dependencies {
    implementation(project(":vpn:core"))
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
