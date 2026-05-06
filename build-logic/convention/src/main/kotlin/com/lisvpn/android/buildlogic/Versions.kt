package com.lisvpn.android.buildlogic

/**
 * Single source of truth for SDK levels and compile targets.
 * Keep in sync with [versions] section of `gradle/libs.versions.toml`.
 */
internal object Versions {
    const val MIN_SDK = 26
    const val TARGET_SDK = 35
    const val COMPILE_SDK = 35
    const val VERSION_CODE = 1
    const val VERSION_NAME = "0.1.0"
    const val APPLICATION_ID = "com.lisvpn.android"
}
