plugins {
    alias(libs.plugins.lisvpn.android.library)
}

android {
    namespace = "com.lisvpn.android.vpn.libbox"

    defaultConfig {
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    packaging {
        jniLibs {
            // libbox.so is bundled inside libbox.aar; do not strip.
            keepDebugSymbols += setOf(
                "**/libbox.so",
                "**/libgojni.so",
            )
        }
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:domain"))
    // libbox.aar (sing-box Go bindings) — single source of truth for the package `libbox.*`.
    api(files("libs/libbox.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
