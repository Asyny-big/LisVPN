package com.lisvpn.android.core.domain.usecase

import com.lisvpn.android.core.common.result.AppResult
import com.lisvpn.android.core.domain.model.Profile
import com.lisvpn.android.core.domain.model.ProfileSource
import com.lisvpn.android.core.domain.repository.ProfileRepository
import javax.inject.Inject

class ImportProfileUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
) {
    suspend operator fun invoke(source: ProfileSource): AppResult<Profile> = profileRepository.import(source)
}
