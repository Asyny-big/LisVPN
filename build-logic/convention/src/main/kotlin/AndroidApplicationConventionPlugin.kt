import com.android.build.api.dsl.ApplicationExtension
import com.lisvpn.android.buildlogic.Versions
import com.lisvpn.android.buildlogic.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.android")
        }

        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            defaultConfig {
                applicationId = Versions.APPLICATION_ID
                targetSdk = Versions.TARGET_SDK
                versionCode = Versions.VERSION_CODE
                versionName = Versions.VERSION_NAME
                vectorDrawables.useSupportLibrary = true
            }
            buildFeatures {
                buildConfig = true
            }
        }
    }
}
