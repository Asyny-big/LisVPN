import com.lisvpn.android.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * Convention plugin for `:feature:*` modules.
 * Adds Compose, Hilt, navigation-compose, and binds to :core:designsystem + :core:ui + :core:domain.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("lisvpn.android.library")
        pluginManager.apply("lisvpn.android.library.compose")
        pluginManager.apply("lisvpn.android.hilt")

        dependencies {
            add("implementation", project(":core:common"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:ui"))
            add("implementation", project(":core:domain"))

            libs.findLibrary("androidx-lifecycle-runtime-compose").ifPresent {
                add("implementation", it.get())
            }
            libs.findLibrary("androidx-lifecycle-viewmodel-compose").ifPresent {
                add("implementation", it.get())
            }
            libs.findLibrary("androidx-navigation-compose").ifPresent {
                add("implementation", it.get())
            }
            libs.findLibrary("hilt-navigation-compose").ifPresent {
                add("implementation", it.get())
            }
            libs.findLibrary("kotlinx-coroutines-android").ifPresent {
                add("implementation", it.get())
            }
        }
    }
}
