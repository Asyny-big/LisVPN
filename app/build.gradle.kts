plugins {
    alias(libs.plugins.lisvpn.android.application)
    alias(libs.plugins.lisvpn.android.application.compose)
    alias(libs.plugins.lisvpn.android.hilt)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lisvpn.android"

    defaultConfig {
        // applicationId / versionCode / versionName inherited from convention plugin (Versions.kt)
        resourceConfigurations += setOf("en", "ru")
    }

    flavorDimensions += "environment"

    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://govchat.ru\"")
            buildConfigField("String", "DEEP_LINK_HOST", "\"govchat.ru\"")
            buildConfigField("String", "RELEASE_FEED_URL", "\"https://govchat.ru/release/android.json\"")
            buildConfigField("boolean", "STRICT_CERTIFICATE_PINNING", "false")
            buildConfigField("String", "DEFAULT_USER_AGENT", "\"LisVPN-Android/${defaultConfig.versionName}-dev (sing-box)\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://lisvpn.ru\"")
            buildConfigField("String", "DEEP_LINK_HOST", "\"lisvpn.ru\"")
            buildConfigField("String", "RELEASE_FEED_URL", "\"https://lisvpn.ru/release/android.json\"")
            buildConfigField("boolean", "STRICT_CERTIFICATE_PINNING", "true")
            buildConfigField("String", "DEFAULT_USER_AGENT", "\"LisVPN-Android/${defaultConfig.versionName} (sing-box)\"")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug") // TODO: prod signing config in keystore.properties
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))

    implementation(project(":feature:home"))
    implementation(project(":feature:profiles"))
    implementation(project(":feature:servers"))
    implementation(project(":feature:splittunnel"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:updates"))

    implementation(project(":vpn:core"))
    implementation(project(":vpn:libbox"))
    implementation(project(":vpn:config"))
    implementation(project(":vpn:health"))
    implementation(project(":vpn:tunnel"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.work.runtime)

    // hilt-android, hilt-compiler and hilt-androidx-compiler are wired by
    // the `lisvpn.android.hilt` convention plugin.
    implementation(libs.hilt.work)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
}
