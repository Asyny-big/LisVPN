plugins { alias(libs.plugins.lisvpn.android.feature) }
android { namespace = "com.lisvpn.android.feature.splittunnel" }
dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
