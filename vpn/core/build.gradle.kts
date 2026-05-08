plugins {
    alias(libs.plugins.lisvpn.android.library)
    alias(libs.plugins.lisvpn.android.hilt)
}

android {
    namespace = "com.lisvpn.android.vpn.core"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:domain"))
    api(project(":vpn:libbox"))
    implementation(project(":vpn:config"))
    implementation(project(":vpn:health"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
}
