plugins {
    alias(libs.plugins.lisvpn.android.library)
    alias(libs.plugins.lisvpn.android.hilt)
}

android {
    namespace = "com.lisvpn.android.core.datastore"
}

dependencies {
    api(project(":core:common"))
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
