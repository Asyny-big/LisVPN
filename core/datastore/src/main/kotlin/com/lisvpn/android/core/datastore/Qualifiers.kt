package com.lisvpn.android.core.datastore

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.BINARY

/** Marks the unencrypted Preferences DataStore (settings, app rules, UI flags). */
@Qualifier @Retention(BINARY) annotation class PreferencesDataStoreQualifier
