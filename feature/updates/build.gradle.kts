plugins { alias(libs.plugins.lisvpn.android.feature) }
android { namespace = "com.lisvpn.android.feature.updates" }
dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
