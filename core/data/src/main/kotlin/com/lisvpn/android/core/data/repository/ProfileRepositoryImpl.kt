package com.lisvpn.android.core.data.repository

import android.net.Uri
import android.util.Base64
import com.lisvpn.android.core.common.dispatchers.IoDispatcher
import com.lisvpn.android.core.common.result.AppError
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.database.dao.ProfileDao
import com.lisvpn.android.core.database.entity.ProfileEntity
import com.lisvpn.android.core.domain.model.Outbound
import com.lisvpn.android.core.domain.model.Profile
import com.lisvpn.android.core.domain.model.ProfileSource
import com.lisvpn.android.core.domain.model.Server
import com.lisvpn.android.core.domain.repository.ProfileRepository
import com.lisvpn.android.core.network.LisHttpClient
import com.lisvpn.android.vpn.config.parser.ParseResult
import com.lisvpn.android.vpn.config.parser.SubscriptionDecoder
import com.lisvpn.android.vpn.config.parser.UriParserRegistry
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import timber.log.Timber

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val profileDao: ProfileDao,
    @LisHttpClient private val httpClient: HttpClient,
    private val subscriptionDecoder: SubscriptionDecoder,
    private val uriParserRegistry: UriParserRegistry,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ProfileRepository {

    override fun observeProfiles(): Flow<List<Profile>> =
        profileDao.observeProfiles().map { list -> list.map { it.toDomain() } }

    override fun observeServers(profileId: String): Flow<List<Server>> =
        profileDao.observeServers(profileId).map { list -> list.map { it.toDomain() } }

    override fun observeAllServers(): Flow<List<Server>> =
        profileDao.observeAllServers().map { list -> list.map { it.toDomain() } }

    override fun observePrimaryProfile(): Flow<Profile?> =
        profileDao.observePrimaryProfile().map { it?.toDomain() }

    override suspend fun get(profileId: String): AppResult<Profile> = withContext(ioDispatcher) {
        profileDao.getProfile(profileId)?.toDomain()?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.NotFound)
    }

    override suspend fun import(source: ProfileSource): AppResult<Profile> = withContext(ioDispatcher) {
        when (source) {
            is ProfileSource.SubscriptionUrl -> importSubscription(source)
            is ProfileSource.SingleUri -> importSingleUri(source)
            is ProfileSource.JsonConfig -> AppResult.Failure(AppError.Parse("Raw sing-box JSON import is not supported by this build"))
            is ProfileSource.LisVpnAccount -> AppResult.Failure(AppError.Parse("LisVPN account import is not available for token subscriptions"))
        }
    }

    override suspend fun refresh(profileId: String): AppResult<Profile> = withContext(ioDispatcher) {
        val profile = profileDao.getProfile(profileId)?.toDomain() ?: return@withContext AppResult.Failure(AppError.NotFound)
        when (val source = profile.source) {
            is ProfileSource.SubscriptionUrl -> importSubscription(source)
            is ProfileSource.SingleUri -> importSingleUri(source)
            else -> AppResult.Failure(AppError.Parse("This profile source cannot be refreshed"))
        }
    }

    override suspend fun setPrimary(profileId: String): AppResult<Unit> = withContext(ioDispatcher) {
        if (profileDao.setPrimary(profileId)) AppResult.Success(Unit) else AppResult.Failure(AppError.NotFound)
    }

    override suspend fun delete(profileId: String): AppResult<Unit> = withContext(ioDispatcher) {
        if (profileDao.getProfile(profileId) == null) return@withContext AppResult.Failure(AppError.NotFound)
        profileDao.deleteProfile(profileId)
        AppResult.Success(Unit)
    }

    private suspend fun importSubscription(source: ProfileSource.SubscriptionUrl): AppResult<Profile> {
        val url = normalizeHttpUrl(source.url) ?: return AppResult.Failure(AppError.Parse("Введите корректный HTTPS URL подписки"))
        Timber.i("Subscription import requested")
        val response = fetchSubscription(url)
        if (response is AppResult.Failure) return response
        val payload = (response as AppResult.Success).value
        val lines = subscriptionDecoder.decode(payload.body)
        Timber.i("Subscription fetched: lines=%d title=%s expires=%s", lines.size, payload.title, payload.expiresAt)
        if (lines.isEmpty()) return emptySubscriptionFailure(payload)
        val sourcePair = ProfileSourceDb.from(ProfileSource.SubscriptionUrl(url))
        val existing = profileDao.getProfileBySource(sourcePair.first, sourcePair.second)
        val now = Clock.System.now()
        val profileId = existing?.id ?: "profile-${sha256(url).take(24)}"
        val parsed = parseServers(profileId, lines, now)
        if (parsed.servers.isEmpty()) return AppResult.Failure(AppError.Parse(parsed.errorText ?: "Подписка не содержит поддерживаемых VLESS серверов"))
        val shouldBePrimary = existing?.isPrimary == true || profileDao.countProfiles() == 0
        val entity = ProfileEntity(
            id = profileId,
            name = payload.title ?: existing?.name ?: "LisVPN",
            sourceType = sourcePair.first,
            sourceValue = sourcePair.second,
            expiresAtMs = payload.expiresAt?.toEpochMilliseconds(),
            updateIntervalHours = payload.updateIntervalHours,
            announceMessage = payload.announce,
            createdAtMs = existing?.createdAtMs ?: now.toEpochMilliseconds(),
            lastRefreshedAtMs = now.toEpochMilliseconds(),
            isPrimary = existing?.isPrimary == true,
        )
        profileDao.importProfile(entity, parsed.servers.map { it.toEntity() }, shouldBePrimary)
        Timber.i("Subscription imported: profile=%s servers=%d", profileId, parsed.servers.size)
        return AppResult.Success(profileDao.getProfile(profileId)?.toDomain() ?: entity.copy(isPrimary = shouldBePrimary).toDomain())
    }

    private suspend fun importSingleUri(source: ProfileSource.SingleUri): AppResult<Profile> {
        val rawUri = source.uri.trim()
        if (rawUri.isBlank()) return AppResult.Failure(AppError.Parse("URI сервера пустой"))
        Timber.i("Single URI import requested")
        val result = uriParserRegistry.parse(rawUri)
        if (result !is ParseResult.Ok) {
            val reason = (result as? ParseResult.Failed)?.reason ?: "URI не поддерживается"
            return AppResult.Failure(AppError.Parse(reason))
        }
        val sourcePair = ProfileSourceDb.from(ProfileSource.SingleUri(rawUri))
        val existing = profileDao.getProfileBySource(sourcePair.first, sourcePair.second)
        val now = Clock.System.now()
        val profileId = existing?.id ?: "profile-${sha256(rawUri).take(24)}"
        val server = result.toServer(profileId, now, isFirst = true)
        val shouldBePrimary = existing?.isPrimary == true || profileDao.countProfiles() == 0
        val entity = ProfileEntity(
            id = profileId,
            name = server.displayName,
            sourceType = sourcePair.first,
            sourceValue = sourcePair.second,
            expiresAtMs = null,
            updateIntervalHours = null,
            announceMessage = null,
            createdAtMs = existing?.createdAtMs ?: now.toEpochMilliseconds(),
            lastRefreshedAtMs = now.toEpochMilliseconds(),
            isPrimary = existing?.isPrimary == true,
        )
        profileDao.importProfile(entity, listOf(server.toEntity()), shouldBePrimary)
        return AppResult.Success(profileDao.getProfile(profileId)?.toDomain() ?: entity.copy(isPrimary = shouldBePrimary).toDomain())
    }

    private suspend fun fetchSubscription(url: String): AppResult<SubscriptionPayload> {
        return try {
            val response = httpClient.get(url)
            val body = response.bodyAsText()
            val status = response.status
            if (!status.isSuccess()) {
                AppResult.Failure(if (status == HttpStatusCode.Unauthorized) AppError.Unauthorized else AppError.Server(status.value))
            } else {
                AppResult.Success(
                    SubscriptionPayload(
                        body = body,
                        title = decodeHeader(response.headers["Profile-Title"]),
                        expiresAt = parseExpire(response.headers["Subscription-Userinfo"]),
                        updateIntervalHours = response.headers["Profile-Update-Interval"]?.toIntOrNull(),
                        announce = decodeHeader(response.headers["announce"]),
                    ),
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.Failure(AppError.Timeout, e)
        } catch (e: java.net.UnknownHostException) {
            AppResult.Failure(AppError.Network, e)
        } catch (e: java.net.ConnectException) {
            AppResult.Failure(AppError.Network, e)
        } catch (e: Throwable) {
            AppResult.Failure(AppError.Unknown(e.message), e)
        }
    }

    private fun parseServers(profileId: String, lines: List<String>, now: Instant): ParsedServers {
        val servers = mutableListOf<Server>()
        val errors = mutableListOf<String>()
        lines.forEachIndexed { index, raw ->
            when (val result = uriParserRegistry.parse(raw)) {
                is ParseResult.Ok -> servers += result.toServer(profileId, now, isFirst = servers.isEmpty())
                is ParseResult.Failed -> errors += "line ${index + 1}: ${result.reason}"
            }
        }
        return ParsedServers(servers = servers, errorText = errors.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    private fun ParseResult.Ok.toServer(profileId: String, now: Instant, isFirst: Boolean): Server {
        val label = displayName?.takeIf { it.isNotBlank() } ?: outbound.defaultDisplayName()
        return Server(
            id = "$profileId:${sha256(rawUri).take(24)}",
            profileId = profileId,
            displayName = label,
            countryCode = null,
            outbound = outbound,
            rawUri = rawUri,
            tags = if (isFirst) setOf(Server.Tag.Primary) else emptySet(),
            createdAt = now,
        )
    }

    private fun emptySubscriptionFailure(payload: SubscriptionPayload): AppResult.Failure {
        val announce = payload.announce.orEmpty()
        val now = Clock.System.now()
        return when {
            announce.contains("лимит", ignoreCase = true) || announce.contains("limit", ignoreCase = true) ->
                AppResult.Failure(AppError.Vpn(announce.ifBlank { "Превышен лимит устройств" }))
            payload.expiresAt != null && payload.expiresAt <= now ->
                AppResult.Failure(AppError.Vpn(announce.ifBlank { "Подписка истекла" }))
            announce.isNotBlank() ->
                AppResult.Failure(AppError.Parse(announce))
            else ->
                AppResult.Failure(AppError.Parse("Подписка пуста или сервер временно недоступен"))
        }
    }

    private fun normalizeHttpUrl(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https") return null
        if (parsed.host.isNullOrBlank()) return null
        return parsed.buildUpon().fragment(null).build().toString()
    }

    private fun decodeHeader(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!raw.startsWith("base64:", ignoreCase = true)) return raw
        val encoded = raw.substringAfter(':')
        return decodeBase64(encoded)
            ?.takeIf { it.isNotBlank() }
    }

    private fun decodeBase64(value: String): String? {
        val flags = intArrayOf(Base64.DEFAULT, Base64.NO_WRAP, Base64.URL_SAFE, Base64.URL_SAFE or Base64.NO_WRAP)
        for (flag in flags) {
            val decoded = runCatching { String(Base64.decode(value, flag), Charsets.UTF_8) }.getOrNull()
            if (decoded != null) return decoded
        }
        return null
    }

    private fun parseExpire(value: String?): Instant? {
        val expire = value?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("expire=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.toLongOrNull()
            ?: return null
        if (expire <= 0) return null
        return Instant.fromEpochSeconds(expire)
    }

    private fun Outbound.defaultDisplayName(): String = when (this) {
        is Outbound.Vless -> host
        is Outbound.Vmess -> host
        is Outbound.Trojan -> host
        is Outbound.Shadowsocks -> host
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class SubscriptionPayload(
        val body: String,
        val title: String?,
        val expiresAt: Instant?,
        val updateIntervalHours: Int?,
        val announce: String?,
    )

    private data class ParsedServers(
        val servers: List<Server>,
        val errorText: String?,
    )
}
