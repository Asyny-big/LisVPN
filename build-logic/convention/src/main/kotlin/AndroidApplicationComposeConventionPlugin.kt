import com.android.build.api.dsl.ApplicationExtension
import com.lisvpn.android.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class AndroidApplicationComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("lisvpn.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val extension = extensions.getByType<ApplicationExtension>()
        extension.apply {
            buildFeatures {
                compose = true
            }
        }

        dependencies {
            val bom = libs.findLibrary("compose-bom").get()
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))
            libs.findBundle("compose-ui-base").ifPresent { bundle ->
                bundle.get().forEach { dependency -> add("implementation", dependency) }
            }
            libs.findLibrary("compose-ui-tooling").ifPresent { add("debugImplementation", it.get()) }
            libs.findLibrary("compose-ui-test-manifest").ifPresent { add("debugImplementation", it.get()) }
        }
    }
}
