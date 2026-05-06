plugins { alias(libs.plugins.lisvpn.android.feature) }
android { namespace = "com.lisvpn.android.feature.profiles" }
dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
