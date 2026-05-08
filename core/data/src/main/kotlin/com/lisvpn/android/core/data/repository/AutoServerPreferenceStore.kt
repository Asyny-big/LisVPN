package com.lisvpn.android.core.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.datastore.PreferencesDataStoreQualifier
import com.lisvpn.android.core.domain.model.Server
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

@Singleton
class AutoServerPreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @PreferencesDataStoreQualifier private val dataStore: DataStore<Preferences>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun currentProfile(): AutoNetworkProfile = withContext(ioDispatcher) {
        detectNetworkProfile()
    }

    suspend fun cachedServerIds(profile: AutoNetworkProfile): List<String> {
        val prefs = dataStore.data.first()
        return listOfNotNull(
            prefs[serverKey("fingerprint.${profile.fingerprint}")],
            prefs[serverKey("class.${profile.networkClass.name.lowercase()}")],
        ).distinct()
    }

    suspend fun cachedBestServer(servers: List<Server>, profile: AutoNetworkProfile): Server? {
        val ids = cachedServerIds(profile)
        return ids.firstNotNullOfOrNull { id -> servers.firstOrNull { it.id == id } }
    }

    suspend fun saveBest(profile: AutoNetworkProfile, server: Server, score: Double) {
        dataStore.edit { prefs ->
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val fingerprintPrefix = "fingerprint.${profile.fingerprint}"
            val classPrefix = "class.${profile.networkClass.name.lowercase()}"
            prefs[serverKey(fingerprintPrefix)] = server.id
            prefs[serverKey(classPrefix)] = server.id
            prefs[scoreKey(fingerprintPrefix)] = score.toString()
            prefs[scoreKey(classPrefix)] = score.toString()
            prefs[timestampKey(fingerprintPrefix)] = nowMs
            prefs[timestampKey(classPrefix)] = nowMs
        }
    }

    private fun detectNetworkProfile(): AutoNetworkProfile {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork
        val activeCaps = active?.let(manager::getNetworkCapabilities)
        val caps = if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            activeCaps
        } else {
            manager.allNetworks
                .asSequence()
                .mapNotNull(manager::getNetworkCapabilities)
                .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
                .maxByOrNull { it.profilePriority() }
                ?: activeCaps
        }

        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                val carrier = context.telephonyCarrierName()
                AutoNetworkProfile(
                    networkClass = AutoNetworkClass.Mobile,
                    fingerprint = "mobile:${carrier.ifBlank { "unknown" }}",
                )
            }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> AutoNetworkProfile(
                networkClass = AutoNetworkClass.Wifi,
                fingerprint = "wifi:${if (metered) "metered" else "unmetered"}:${context.wifiFingerprintPart().ifBlank { "unknown" }}",
            )
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> AutoNetworkProfile(
                networkClass = AutoNetworkClass.Ethernet,
                fingerprint = "ethernet",
            )
            metered -> AutoNetworkProfile(
                networkClass = AutoNetworkClass.Metered,
                fingerprint = "metered",
            )
            else -> AutoNetworkProfile(
                networkClass = AutoNetworkClass.Unknown,
                fingerprint = "unknown",
            )
        }
    }

    private fun Context.telephonyCarrierName(): String =
        runCatching {
            val manager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            listOf(manager?.networkOperator.orEmpty(), manager?.networkOperatorName.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("-")
                .normalizeFingerprintPart()
        }.getOrDefault("")

    private fun Context.wifiFingerprintPart(): String =
        runCatching {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            manager?.connectionInfo?.ssid
                .orEmpty()
                .trim('"')
                .normalizeFingerprintPart()
        }.getOrDefault("")

    private fun String.normalizeFingerprintPart(): String =
        lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .take(48)

    private fun NetworkCapabilities.profilePriority(): Int {
        val validation = if (hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 100 else 0
        val internet = if (hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) 50 else 0
        val transport = when {
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 40
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 30
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 20
            else -> 0
        }
        return validation + internet + transport
    }

    private fun serverKey(suffix: String) = stringPreferencesKey("auto.best.server.${suffix.safeKeySuffix()}")
    private fun scoreKey(suffix: String) = stringPreferencesKey("auto.best.score.${suffix.safeKeySuffix()}")
    private fun timestampKey(suffix: String) = longPreferencesKey("auto.best.updated.${suffix.safeKeySuffix()}")

    private fun String.safeKeySuffix(): String =
        MessageDigest.getInstance("SHA-1")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

data class AutoNetworkProfile(
    val networkClass: AutoNetworkClass,
    val fingerprint: String,
) {
    val isMobileLike: Boolean
        get() = networkClass == AutoNetworkClass.Mobile || networkClass == AutoNetworkClass.Metered
}

enum class AutoNetworkClass {
    Wifi,
    Mobile,
    Ethernet,
    Metered,
    Unknown,
}
