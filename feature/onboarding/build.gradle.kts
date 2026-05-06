plugins { alias(libs.plugins.lisvpn.android.feature) }
android { namespace = "com.lisvpn.android.feature.onboarding" }
dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
