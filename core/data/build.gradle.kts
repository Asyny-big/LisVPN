plugins {
    alias(libs.plugins.lisvpn.android.library)
    alias(libs.plugins.lisvpn.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lisvpn.android.core.data"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":vpn:core"))
    implementation(project(":vpn:config"))
    implementation(project(":vpn:health"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
