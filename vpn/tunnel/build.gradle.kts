plugins {
    alias(libs.plugins.lisvpn.android.library)
}

android {
    namespace = "com.lisvpn.android.vpn.tunnel"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:domain"))
}
