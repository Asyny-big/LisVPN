plugins {
    alias(libs.plugins.lisvpn.android.library)
    alias(libs.plugins.lisvpn.android.hilt)
    alias(libs.plugins.lisvpn.android.room)
}

android {
    namespace = "com.lisvpn.android.core.database"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:domain"))
    implementation(libs.kotlinx.serialization.json)
}
