import com.lisvpn.android.buildlogic.libs
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")

        extensions.configure<KspExtension> {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }

        dependencies {
            libs.findLibrary("androidx-room-runtime").ifPresent { add("implementation", it.get()) }
            libs.findLibrary("androidx-room-ktx").ifPresent { add("implementation", it.get()) }
            libs.findLibrary("androidx-room-compiler").ifPresent { add("ksp", it.get()) }
        }
    }
}
