plugins {
    alias(libs.plugins.lisvpn.android.library)
    alias(libs.plugins.lisvpn.android.hilt)
}

android {
    namespace = "com.lisvpn.android.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.datetime)
    api(libs.timber)
    implementation(libs.androidx.core.ktx)
    implementation(libs.tink.android)
}
