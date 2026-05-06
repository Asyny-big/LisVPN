import com.lisvpn.android.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.google.devtools.ksp")
            apply("com.google.dagger.hilt.android")
        }

        dependencies {
            libs.findLibrary("hilt-android").ifPresent { add("implementation", it.get()) }
            libs.findLibrary("hilt-compiler").ifPresent { add("ksp", it.get()) }
            libs.findLibrary("hilt-androidx-compiler").ifPresent { add("ksp", it.get()) }
        }
    }
}
