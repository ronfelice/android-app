/*
 * Copyright (c) 2023 Proton Technologies AG
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
package com.protonvpn.android.redesign.settings.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.protonvpn.android.R
import com.protonvpn.android.appconfig.AppConfig
import com.protonvpn.android.auth.usecase.CurrentUser
import com.protonvpn.android.auth.usecase.uiName
import com.protonvpn.android.netshield.NetShieldAvailability
import com.protonvpn.android.netshield.NetShieldProtocol
import com.protonvpn.android.netshield.getNetShieldAvailability
import com.protonvpn.android.settings.data.CurrentUserLocalSettingsManager
import com.protonvpn.android.settings.data.EffectiveCurrentUserSettings
import com.protonvpn.android.settings.data.SplitTunnelingSettings
import com.protonvpn.android.ui.settings.BuildConfigInfo
import com.protonvpn.android.userstorage.ProfileManager
import com.protonvpn.android.utils.BuildConfigUtils
import com.protonvpn.android.vpn.ProtocolSelection
import com.protonvpn.android.vpn.VpnConnectionManager
import com.protonvpn.android.vpn.VpnStatusProviderUI
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class NatType(val labelRes: Int, val descriptionRes: Int) {
   Strict(
       labelRes = R.string.settings_advanced_nat_type_strict,
       descriptionRes = R.string.settings_advanced_nat_type_strict_description
   ),
   Moderate(
       labelRes = R.string.settings_advanced_nat_type_moderate,
       descriptionRes = R.string.settings_advanced_nat_type_moderate_description
   )
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val currentUser: CurrentUser,
    private val userSettingsManager: CurrentUserLocalSettingsManager,
    private val effectiveUserSettings: EffectiveCurrentUserSettings,
    private val vpnConnectionManager: VpnConnectionManager,
    private val vpnStatusProviderUI: VpnStatusProviderUI,
    private val appConfig: AppConfig,
    private val profileManager: ProfileManager,
    buildConfigInfo: BuildConfigInfo,
) : ViewModel() {

    sealed class SettingViewState<T>(
        val value: T,
        val isRestricted: Boolean,
        @StringRes val titleRes: Int,
        @StringRes val subtitleRes: Int,
        @StringRes val descriptionRes: Int,
        @StringRes val annotationRes: Int? = null,
        @DrawableRes open val iconRes: Int? = null,
        @DrawableRes val upgradeIconRes: Int? = if (isRestricted) R.drawable.vpn_plus_badge else null
    ) {
        class NetShield(
            netShieldEnabled: Boolean,
            netShieldAvailability: NetShieldAvailability,
            override val iconRes: Int = if (netShieldEnabled) R.drawable.feature_netshield_on else R.drawable.feature_netshield_off
        ) : SettingViewState<Boolean>(
            value = netShieldEnabled,
            isRestricted = netShieldAvailability != NetShieldAvailability.AVAILABLE,
            upgradeIconRes = if (netShieldAvailability == NetShieldAvailability.UPGRADE_VPN_PLUS) R.drawable.vpn_plus_badge else null,
            titleRes = R.string.netshield_feature_name,
            subtitleRes = if (netShieldEnabled) R.string.feature_on else R.string.feature_off,
            descriptionRes = R.string.netshield_settings_description_not_html,
            annotationRes = R.string.learn_more
        )

        class SplitTunneling(
            val splitTunnelingSettings: SplitTunnelingSettings,
            isFreeUser: Boolean,
            override val iconRes: Int = if (splitTunnelingSettings.isEnabled) R.drawable.feature_splittunneling_on else R.drawable.feature_splittunneling_off
        ) : SettingViewState<Boolean>(
            value = splitTunnelingSettings.isEnabled,
            isRestricted = isFreeUser,
            titleRes = R.string.settings_split_tunneling_title,
            subtitleRes = if (splitTunnelingSettings.isEnabled) R.string.feature_on else R.string.feature_off,
            descriptionRes = R.string.settings_split_tunneling_description,
            annotationRes = R.string.learn_more
        )

        class VpnAccelerator(
            vpnAcceleratorSettingValue: Boolean,
            isFreeUser: Boolean,
            override val iconRes: Int = me.proton.core.auth.R.drawable.ic_proton_rocket
        ) : SettingViewState<Boolean>(
            value = vpnAcceleratorSettingValue && !isFreeUser,
            isRestricted = isFreeUser,
            iconRes = me.proton.core.auth.R.drawable.ic_proton_rocket,
            titleRes = R.string.settings_vpn_accelerator_title,
            subtitleRes = if (vpnAcceleratorSettingValue && !isFreeUser) R.string.feature_on else R.string.feature_off,
            descriptionRes = R.string.settings_vpn_accelerator_description,
            annotationRes = R.string.learn_more
        )

        class Protocol(
            protocol: ProtocolSelection,
        ) : SettingViewState<ProtocolSelection>(
            value = protocol,
            isRestricted = false,
            titleRes = R.string.settings_protocol_title,
            subtitleRes = protocol.displayName,
            iconRes = me.proton.core.auth.R.drawable.ic_proton_servers,
            descriptionRes = R.string.settings_protocol_description,
            annotationRes = R.string.learn_more
        )

        class AltRouting(enabled: Boolean) : SettingViewState<Boolean>(
            value = enabled,
            isRestricted = false,
            titleRes = R.string.settings_advanced_alternative_routing_title,
            subtitleRes = if (enabled) R.string.feature_on else R.string.feature_off,
            descriptionRes = R.string.settings_advanced_alternative_routing_description,
        )

        class LanConnections(enabled: Boolean, isFreeUser: Boolean) : SettingViewState<Boolean>(
            value = enabled,
            isRestricted = isFreeUser,
            titleRes = R.string.settings_advanced_allow_lan_title,
            subtitleRes = if (enabled) R.string.feature_on else R.string.feature_off,
            descriptionRes = R.string.settings_advanced_allow_lan_description,
        )

        class Nat(natType: NatType, isFreeUser: Boolean) : SettingViewState<NatType>(
            value = natType,
            isRestricted = isFreeUser,
            titleRes = R.string.settings_advanced_nat_type_title,
            subtitleRes = natType.labelRes,
            descriptionRes = R.string.settings_advanced_nat_type_description,
            annotationRes = R.string.learn_more
        )
    }

    data class SettingsViewState(
        val netShield: SettingViewState.NetShield,
        val splitTunneling: SettingViewState.SplitTunneling,
        val vpnAccelerator: SettingViewState.VpnAccelerator,
        val protocol: SettingViewState.Protocol,
        val altRouting: SettingViewState.AltRouting,
        val lanConnections: SettingViewState.LanConnections,
        val natType: SettingViewState.Nat,
        val userInfo: UserViewState,
        val buildInfo: String?,
    )
    data class UserViewState(
        val isFreeUser: Boolean,
        val shortenedName: String,
        val displayName: String,
        val email: String
    )

    // The configuration doesn't change during runtime.
    private val buildConfigText = if (BuildConfigUtils.displayInfo()) buildConfigInfo() else null

    val viewState =
        combine(
            currentUser.vpnUserFlow,
            currentUser.userFlow.filterNotNull(),
            // Keep in mind UI for some settings can't rely directly on effective settings.
            effectiveUserSettings.effectiveSettings,
        ) { vpnUser, user, settings ->
            val isFree = vpnUser?.isFreeUser == true
            val netShieldAvailability = vpnUser.getNetShieldAvailability()

            val userViewState = UserViewState(
                shortenedName = getInitials(user.uiName()) ?: "",
                displayName = user.uiName() ?: "",
                email = user.email ?: "",
                isFreeUser = isFree
            )

            SettingsViewState(
                netShield = SettingViewState.NetShield(
                    settings.netShield != NetShieldProtocol.DISABLED,
                    netShieldAvailability
                ),
                vpnAccelerator = SettingViewState.VpnAccelerator(settings.vpnAccelerator, isFree),
                splitTunneling = SettingViewState.SplitTunneling(settings.splitTunneling, isFree),
                protocol = SettingViewState.Protocol(settings.protocol),
                altRouting = SettingViewState.AltRouting(settings.apiUseDoh),
                lanConnections = SettingViewState.LanConnections(settings.lanConnections, isFree),
                natType = SettingViewState.Nat(if (settings.randomizedNat) NatType.Strict else NatType.Moderate, isFree),
                userInfo = userViewState,
                buildInfo = buildConfigText
            )
        }

    private fun getInitials(name: String?): String? {
        return name?.split(" ")
            ?.take(2) // UI does not support 3 chars initials
            ?.mapNotNull { it.firstOrNull()?.uppercase() }
            ?.joinToString("")
    }

    val vpnAccelerator = viewState.map { it.vpnAccelerator }.distinctUntilChanged()
    val netShield = viewState.map { it.netShield }.distinctUntilChanged()
    private val altRouting = viewState.map { it.altRouting }.distinctUntilChanged()
    private val lanConnections = viewState.map { it.lanConnections }.distinctUntilChanged()
    val natType = viewState.map { it.natType }.distinctUntilChanged()
    val splitTunneling = viewState.map { it.splitTunneling }.distinctUntilChanged()

    data class AdvancedSettingsViewState(
        val altRouting: SettingViewState.AltRouting,
        val lanConnections: SettingViewState.LanConnections,
        val natType: SettingViewState.Nat
    )
    val advancedSettings = combine(
        altRouting,
        lanConnections,
        natType
    ) { altRouting, lanConnections, natType ->
        AdvancedSettingsViewState(
            altRouting = altRouting,
            lanConnections = lanConnections,
            natType = natType
        )
    }.distinctUntilChanged()

    fun toggleNetShield() {
        viewModelScope.launch {
            userSettingsManager.toggleNetShield()
        }
    }

    fun updateProtocol(protocol: ProtocolSelection) {
        viewModelScope.launch {
            userSettingsManager.updateProtocol(protocol)
        }
    }

    fun setNatType(type: NatType) {
        viewModelScope.launch {
            userSettingsManager.setRandomizedNat(type == NatType.Strict)
        }
    }

    fun toggleVpnAccelerator() {
        viewModelScope.launch {
            userSettingsManager.toggleVpnAccelerator()
        }
    }

    fun toggleAltRouting() {
        viewModelScope.launch {
            userSettingsManager.toggleAltRouting()
        }
    }

    fun toggleLanConnections() {
        viewModelScope.launch {
            userSettingsManager.toggleLanConnections()
        }
    }

    fun toggleSplitTunneling() {
        viewModelScope.launch {
            userSettingsManager.toggleSplitTunneling()
        }
    }
}
