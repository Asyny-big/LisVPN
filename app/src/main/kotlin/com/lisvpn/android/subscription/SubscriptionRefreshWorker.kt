package com.lisvpn.android.subscription

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.repository.ProfileRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

@HiltWorker
class SubscriptionRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val profileRepository: ProfileRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val profiles = when (val result = profileRepository.listSubscriptionProfiles()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> {
                Timber.w(result.cause, "Subscription auto-refresh could not load profiles")
                return if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
            }
        }
        if (profiles.isEmpty()) return Result.success()

        val failures = coroutineScope {
            profiles.map { profile ->
                async {
                    when (val result = profileRepository.refresh(profile.id)) {
                        is AppResult.Success -> null
                        is AppResult.Failure -> profile.id to result
                    }
                }
            }.awaitAll().filterNotNull()
        }

        return if (failures.isEmpty()) {
            Timber.i("Subscription auto-refresh completed: profiles=%d", profiles.size)
            Result.success()
        } else {
            failures.forEach { (profileId, failure) ->
                Timber.w(failure.cause, "Subscription auto-refresh failed: profile=%s error=%s", profileId, failure.error)
            }
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "subscription-refresh"
        private const val REPEAT_INTERVAL_HOURS = 3L
        private const val FLEX_INTERVAL_MINUTES = 30L
        private const val MAX_RETRY_ATTEMPTS = 2

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
                REPEAT_INTERVAL_HOURS,
                TimeUnit.HOURS,
                FLEX_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
