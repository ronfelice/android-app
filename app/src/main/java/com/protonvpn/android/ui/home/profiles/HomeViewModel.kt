/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonVPN.
 *
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.protonvpn.android.ui.home.profiles

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.protonvpn.android.appconfig.AppFeaturesPrefs
import com.protonvpn.android.appconfig.CachedPurchaseEnabled
import com.protonvpn.android.appconfig.RestrictionsConfig
import com.protonvpn.android.auth.usecase.CurrentUser
import com.protonvpn.android.auth.usecase.Logout
import com.protonvpn.android.auth.usecase.OnSessionClosed
import com.protonvpn.android.models.profiles.Profile
import com.protonvpn.android.models.profiles.ServerWrapper
import com.protonvpn.android.settings.data.CurrentUserLocalSettingsManager
import com.protonvpn.android.settings.data.EffectiveCurrentUserSettingsCached
import com.protonvpn.android.tv.main.MainViewModel
import com.protonvpn.android.ui.onboarding.OnboardingPreferences
import com.protonvpn.android.utils.DebugUtils
import com.protonvpn.android.utils.ServerManager
import com.protonvpn.android.utils.Storage
import com.protonvpn.android.utils.UserPlanManager
import com.protonvpn.android.vpn.ConnectTrigger
import com.protonvpn.android.vpn.VpnStatusProviderUI
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mainScope: CoroutineScope,
    private val effectiveUserSettings: EffectiveCurrentUserSettingsCached,
    private val userSettingManager: CurrentUserLocalSettingsManager,
    private val vpnStatusProviderUI: VpnStatusProviderUI,
    private val serverManager: ServerManager,
    userPlanManager: UserPlanManager,
    currentUser: CurrentUser,
    logoutUseCase: Logout,
    onSessionClosed: OnSessionClosed,
    purchaseEnabled: CachedPurchaseEnabled,
    val appFeaturesPrefs: AppFeaturesPrefs,
    private val restrictionsConfig: RestrictionsConfig,
    ) : MainViewModel(
    mainScope,
    userPlanManager,
    logoutUseCase,
    currentUser,
    purchaseEnabled,
) {

    private var startOnboardingJob: Job? = null

    var showIKEv2Migration
        get() = appFeaturesPrefs.showIKEv2Migration
        set(value) { appFeaturesPrefs.showIKEv2Migration = value }

    val secureCore get() = effectiveUserSettings.value.secureCore
    val secureCoreLiveData = effectiveUserSettings.map { it.secureCore }.distinctUntilChanged().asLiveData()
    val userLiveData = currentUser.userFlow.asLiveData()
    val logoutEvent = onSessionClosed.logoutFlow.asLiveData()

    private val connectEventFlow = MutableSharedFlow<Pair<Profile, ConnectTrigger>>()
    val connectEvent = connectEventFlow.asLiveData()

    // Temporary method to help java activity collect a flow
    fun collectPlanChange(activity: AppCompatActivity, onChange: (UserPlanManager.InfoChange.PlanChange) -> Unit) {
        activity.lifecycleScope.launch {
            userPlanChangeEvent.collect {
                onChange(it)
            }
        }
    }

    fun getReconnectProfileOnSecureCoreChange(): Profile {
        DebugUtils.debugAssert("Is connected or connecting") { vpnStatusProviderUI.isEstablishingOrConnected }
        val connectedProfile = vpnStatusProviderUI.connectionParams!!.profile
        return if (connectedProfile.isSecureCore == null && !connectedProfile.isDirectServer) {
            // Connect to the same profile, it doesn't enforce Secure Core.
            connectedProfile
        } else {
            val newProfile = Profile.getTempProfile(ServerWrapper.makeFastestForCountry(connectedProfile.country))
            // Check if there is a server for new profile and fall back if there isn't (e.g. when switching to Secure
            // Core while connected to Switzerland). Otherwise fallback logic will trigger, find a server and show a
            // notification, which we want to avoid.
            newProfile
                .takeIf { serverManager.getServerForProfile(newProfile, currentUser.vpnUserCached()) != null }
                ?: serverManager.defaultFallbackConnection
        }
    }

    fun handleUserOnboarding(block: () -> Unit) {
        startOnboardingJob?.cancel()
        startOnboardingJob = viewModelScope.launch {
            val onboardingId = Storage.getString(OnboardingPreferences.ONBOARDING_USER_ID, null)
            if (serverManager.isDownloadedAtLeastOnce &&
                onboardingId != null &&
                currentUser.user()?.userId?.id == onboardingId
            ) {
                ensureActive()
                Storage.saveString(OnboardingPreferences.ONBOARDING_USER_ID, null)
                block()
            }
        }
    }

    fun toggleSecureCore(newIsEnabled: Boolean) {
        mainScope.launch {
            if (vpnStatusProviderUI.isEstablishingOrConnected &&
                vpnStatusProviderUI.isConnectingToSecureCore == !newIsEnabled
            ) {
                userSettingManager.updateSecureCore(newIsEnabled)

                val newProfile = getReconnectProfileOnSecureCoreChange();
                connectEventFlow.emit(Pair(newProfile, ConnectTrigger.SecureCore))
            } else {
                userSettingManager.updateSecureCore(newIsEnabled)
            }
        }
    }

    fun shouldShowWhatsNew() = restrictionsConfig.restrictQuickConnectSync() && appFeaturesPrefs.showWhatsNew
    fun onWhatsNewShown() {
        appFeaturesPrefs.showWhatsNew = false
    }

    val isQuickConnectRestricted get() = restrictionsConfig.restrictQuickConnectSync()
}
