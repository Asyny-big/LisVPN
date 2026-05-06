plugins { alias(libs.plugins.lisvpn.android.feature) }
android { namespace = "com.lisvpn.android.feature.servers" }
dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
