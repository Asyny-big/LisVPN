@file:Suppress("UnstableApiUsage")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Local AAR catalog (libbox.aar etc.)
        flatDir {
            dirs("vpn/libbox/libs")
        }
    }
}

rootProject.name = "LisVPN"

// :app
include(":app")

// :core:*
include(":core:common")
include(":core:designsystem")
include(":core:ui")
include(":core:domain")
include(":core:data")
include(":core:database")
include(":core:datastore")
include(":core:network")

// :feature:*
include(":feature:home")
include(":feature:profiles")
include(":feature:servers")
include(":feature:splittunnel")
include(":feature:settings")
include(":feature:onboarding")
include(":feature:updates")

// :vpn:*
include(":vpn:core")
include(":vpn:libbox")
include(":vpn:config")
include(":vpn:health")
include(":vpn:tunnel")
