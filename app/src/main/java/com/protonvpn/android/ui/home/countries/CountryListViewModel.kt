/*
 * Copyright (c) 2019 Proton Technologies AG
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
package com.protonvpn.android.ui.home.countries

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.protonvpn.android.R
import com.protonvpn.android.appconfig.ApiNotification
import com.protonvpn.android.appconfig.ApiNotificationManager
import com.protonvpn.android.appconfig.ApiNotificationOfferButton
import com.protonvpn.android.appconfig.ApiNotificationTypes
import com.protonvpn.android.appconfig.Restrictions
import com.protonvpn.android.appconfig.RestrictionsConfig
import com.protonvpn.android.auth.data.VpnUser
import com.protonvpn.android.auth.data.haveAccessWith
import com.protonvpn.android.auth.usecase.CurrentUser
import com.protonvpn.android.components.featureIcons
import com.protonvpn.android.logging.ProtonLogger
import com.protonvpn.android.logging.UiConnect
import com.protonvpn.android.logging.UiDisconnect
import com.protonvpn.android.models.vpn.GatewayGroup
import com.protonvpn.android.models.vpn.Partner
import com.protonvpn.android.models.vpn.Server
import com.protonvpn.android.models.vpn.ServerGroup
import com.protonvpn.android.models.vpn.VpnCountry
import com.protonvpn.android.partnerships.PartnershipsRepository
import com.protonvpn.android.redesign.CountryId
import com.protonvpn.android.redesign.vpn.ConnectIntent
import com.protonvpn.android.settings.data.CurrentUserLocalSettingsManager
import com.protonvpn.android.settings.data.EffectiveCurrentUserSettings
import com.protonvpn.android.ui.home.InformationActivity
import com.protonvpn.android.ui.home.ServerListUpdater
import com.protonvpn.android.ui.promooffers.PromoOffersPrefs
import com.protonvpn.android.utils.AndroidUtils.whenNotNullNorEmpty
import com.protonvpn.android.utils.ServerManager
import com.protonvpn.android.utils.withPrevious
import com.protonvpn.android.vpn.ConnectTrigger
import com.protonvpn.android.vpn.DisconnectTrigger
import com.protonvpn.android.vpn.VpnConnectionManager
import com.protonvpn.android.vpn.VpnStatusProviderUI
import com.protonvpn.android.vpn.VpnUiDelegate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val SECTION_REGULAR = "regular"
private const val SECTION_GATEWAYS = "gateways"

// Main state for this screen
data class ServerListState(
    val sections: List<ServerListSectionModel>
)

// Grouping of items (e.g. premium countries) with optional header and info
data class ServerListSectionModel(
    @StringRes private val headerRes: Int?,
    val items: List<ServerListItemModel>,
    val itemCount: Int = items.size, // Might be different than items.size (e.g. we exclude banner)
    val infoType: InfoType? = null
) {
    enum class InfoType { FreeConnections }

    fun getHeader(context: Context) = headerRes?.let { context.getString(it, itemCount) }
}

// Items on the server list
sealed class ServerListItemModel

object FreeUpsellBannerModel : ServerListItemModel()

data class PromoOfferBannerModel(
    val imageUrl: String,
    val alternativeText: String,
    val action: ApiNotificationOfferButton,
    val isDismissible: Boolean,
    val endTimestamp: Long?,
    val notificationId: String,
    val reference: String?,
) : ServerListItemModel()

data class FastestConnectionModel(
    @StringRes val name: Int,
    val connectIntent: ConnectIntent
) : ServerListItemModel()

data class CollapsibleServerGroupModel(
    private val group: ServerGroup,
    val sections: List<ServerTierGroupModel>,
    val accessible: Boolean,
    val sectionId: String,
    val secureCore: Boolean,
    val userTier: Int?,
) : ServerListItemModel() {

    val title get() = group.name()
    val online get() = !group.isUnderMaintenance()
    val id get() = "$sectionId/${group.name()}"
    val countryFlag get() = (group as? VpnCountry)?.flag

    fun haveServer(server: Server?) = if (server == null)
        false
    else
        sections.any { it.servers.any { it.serverId == server.serverId } }

    fun isGatewayGroup() = group is GatewayGroup

    fun featureIcons() = group.featureIcons()
    fun hasAccessToServer(server: Server) =
        server.haveAccessWith(userTier)

    val hasAccessibleOnlineServer get() = group.hasAccessibleServer(userTier)
}

// Servers of the same tier in a given country/group
data class ServerTierGroupModel(
    val groupTitle: CountryListViewModel.ServerGroupTitle?,
    val servers: List<Server>
) {
    constructor(
        titleRes: Int,
        servers: List<Server>,
        infoType: InformationActivity.InfoType? = null
    ) : this(
        CountryListViewModel.ServerGroupTitle(titleRes, infoType), servers
    )
}

@HiltViewModel
class CountryListViewModel @Inject constructor(
    private val mainScope: CoroutineScope,
    private val serverManager: ServerManager,
    private val partnershipsRepository: PartnershipsRepository,
    private val serverListUpdater: ServerListUpdater,
    private val vpnStatusProviderUI: VpnStatusProviderUI,
    private val vpnConnectionManager: VpnConnectionManager,
    private val userSettings: EffectiveCurrentUserSettings,
    private val userSettingManager: CurrentUserLocalSettingsManager,
    private val currentUser: CurrentUser,
    restrictConfig: RestrictionsConfig,
    apiNotificationManager: ApiNotificationManager,
    private val promoOffersPrefs: PromoOffersPrefs,
) : ViewModel() {

    private sealed interface UpsellBanner {
        object FreeDefault : UpsellBanner
        class Promo(val notification: ApiNotification) : UpsellBanner
    }

    val vpnStatus = vpnStatusProviderUI.status.asLiveData()

    private val _navigateToHome = MutableSharedFlow<Unit>()
    val navigateToHomeEvent: SharedFlow<Unit> get() = _navigateToHome

    private val _dismissLoading = MutableSharedFlow<Unit>()
    val dismissLoading: SharedFlow<Unit> get() = _dismissLoading

    val secureCore get() = userSettings.secureCore
    val secureCoreLiveData = userSettings.effectiveSettings.map { it.secureCore }.distinctUntilChanged().asLiveData()
    fun toggleSecureCore(newIsEnabled: Boolean) {
        viewModelScope.launch {
            userSettingManager.updateSecureCore(newIsEnabled)
        }
    }

    fun hasAccessToSecureCore() =
        currentUser.vpnUserCached()?.isUserPlusOrAbove == true

    // After a banner is dismissed any other banners (e.g. the default one) are suppressed until the activity is
    // started again.
    private val suppressBanners = MutableStateFlow(false)

    private val promoBannerFlow = combine(
        apiNotificationManager.activeListFlow,
        promoOffersPrefs.visitedOffersFlow
    ) { notifications, dismissedOffers ->
        notifications.firstOrNull {
            it.type == ApiNotificationTypes.TYPE_COUNTRY_LIST_BANNER && !dismissedOffers.contains(it.id)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val bannerFlow: Flow<UpsellBanner?> = suppressBanners.flatMapLatest { suppressed ->
        if (suppressed) {
            flowOf(null)
        } else {
            combine(currentUser.vpnUserFlow, promoBannerFlow) { user, promoBanner ->
                when {
                    promoBanner != null -> UpsellBanner.Promo(promoBanner)
                    user?.isFreeUser == true -> UpsellBanner.FreeDefault
                    else -> null
                }
            }
        }
    }.distinctUntilChanged()
    private val userTierFlow = currentUser.vpnUserFlow.map { it?.userTier }.distinctUntilChanged()

    // Scroll to top on downgrade
    val scrollToTop = userTierFlow.withPrevious().mapNotNull { (previous, current) ->
        if (previous != null && current != null && current < previous)
            Unit else null
    }

    val state: Flow<ServerListState> = combine(
        userSettings.effectiveSettings.map { it.secureCore }.distinctUntilChanged(),
        serverManager.serverListVersion,
        restrictConfig.restrictionFlow,
        userTierFlow,
        bannerFlow,
    ) { secureCore, _, restrictions, userTier, banner ->
        ServerListState(sections = getItemsFor(secureCore, restrictions, userTier, banner))
    }

    private fun getItemsFor(
        secureCore: Boolean,
        restrictions: Restrictions,
        userTier: Int?,
        banner: UpsellBanner?,
    ): List<ServerListSectionModel> =
        if (userTier == VpnUser.FREE_TIER)
            getItemsForFreeUser(secureCore, restrictions, banner)
        else
            getItemsForPremiumUser(userTier, secureCore, restrictions, banner)

    private fun List<ServerGroup>.asListItems(
        userTier: Int?,
        secureCore: Boolean,
        restrictions: Restrictions,
        sectionId: String = SECTION_REGULAR
    ) = map { group ->
        CollapsibleServerGroupModel(
            group,
            createServerSections(userTier, secureCore, group),
            accessible = group.hasAccessibleServer(userTier) && !restrictions.serverList,
            sectionId = sectionId,
            secureCore = secureCore,
            userTier = userTier
        )
    }

    private fun getItemsForFreeUser(
        secureCore: Boolean,
        restrictions: Restrictions,
        banner: UpsellBanner?
    ): List<ServerListSectionModel> = buildList {
        val upsellBanner = createBannerList(banner)
        add(
            ServerListSectionModel(
                R.string.listFreeCountries,
                getFastestConnections(secureCore),
                infoType = ServerListSectionModel.InfoType.FreeConnections
            )
        )
        val allCountries = getCountriesForList(secureCore)
        val plusCountries = allCountries.asListItems(VpnUser.FREE_TIER, secureCore, restrictions)
        val items = upsellBanner + plusCountries
        add(
            ServerListSectionModel(
                R.string.listPremiumCountries_new_plans,
                items,
                itemCount = plusCountries.size
            )
        )
    }

    private fun getItemsForPremiumUser(
        userTier: Int?,
        secureCore: Boolean,
        restrictions: Restrictions,
        banner: UpsellBanner?
    ) = buildList {
        val gateways = getGatewayGroupsForList(secureCore)
            .asListItems(userTier, secureCore, restrictions, sectionId = SECTION_GATEWAYS)
        if (gateways.isNotEmpty())
            add(ServerListSectionModel(R.string.listGateways, gateways))
        val promoOfferBanner = createBannerList(banner)
        add(
            ServerListSectionModel(
                R.string.listAllCountries,
                promoOfferBanner
                        + getFastestConnections(secureCore)
                        + getCountriesForList(secureCore).asListItems(userTier, secureCore, restrictions)
            )
        )
    }

    fun refreshServerList() {
        mainScope.launch {
            serverListUpdater.updateServerList()
            _dismissLoading.emit(Unit)
        }
    }

    fun isConnectedToServer(server: Server): Boolean = vpnStatusProviderUI.isConnectedTo(server)

    fun isConnectedTo(connectIntent: ConnectIntent): Boolean =
        vpnStatusProviderUI.isConnectedTo(connectIntent)

    fun getServerPartnerships(server: Server): List<Partner> =
        partnershipsRepository.getServerPartnerships(server)

    fun onUpsellBannerDismissed(notificationId: String) {
        promoOffersPrefs.addVisitedOffer(notificationId)
        suppressBanners.value = true
    }

    data class ServerGroupTitle(val titleRes: Int, val infoType: InformationActivity.InfoType?)

    private fun getFastestConnections(secureCore: Boolean): List<FastestConnectionModel> =
        listOf(
            FastestConnectionModel(R.string.profileFastest,
                if (secureCore)
                    ConnectIntent.SecureCore(CountryId.fastest, CountryId.fastest)
                else
                    ConnectIntent.Fastest
            )
        )

    private fun createServerSections(
        userTier: Int?,
        secureCore: Boolean,
        group: ServerGroup
    ): List<ServerTierGroupModel> {
        return if (secureCore) {
            listOf(ServerTierGroupModel(null, group.serverList))
        } else {
            createRegularServerSections(userTier, group)
        }
    }

    private fun createRegularServerSections(
        userTier: Int?,
        group: ServerGroup
    ): List<ServerTierGroupModel> {
        val countryServers = group.serverList.sortedForUi()
        val freeServers = countryServers.filter { it.isFreeServer }
        val basicServers = countryServers.filter { it.isBasicServer }
        val plusServers = countryServers.filter { it.isPlusServer }
        val internalServers = countryServers.filter { it.isPMTeamServer }
        val fastestServer = serverManager.getBestScoreServer(countryServers)?.copy()

        val groups: MutableList<ServerTierGroupModel> = mutableListOf()
        if (internalServers.isNotEmpty()) {
            groups.add(ServerTierGroupModel(R.string.listInternalServers, internalServers))
        }
        fastestServer?.let {
            groups.add(ServerTierGroupModel(R.string.listFastestServer, listOf(fastestServer)))
        }

        val freeServersInfo =
            if (group is VpnCountry && partnershipsRepository.hasAnyPartnership(group))
                InformationActivity.InfoType.Partners.Country(group.flag, secureCore = false)
            else
                null

        val plusServersInfo =
            if (group is VpnCountry && serverManager.streamingServicesModel?.getForAllTiers(group.flag)?.isNotEmpty() == true)
                InformationActivity.InfoType.Streaming(group.flag)
            else
                null

        when (userTier) {
            VpnUser.FREE_TIER -> {
                freeServers.whenNotNullNorEmpty { groups.add(ServerTierGroupModel(R.string.listFreeServers, freeServers, freeServersInfo)) }
                plusServers.whenNotNullNorEmpty { groups.add(ServerTierGroupModel(R.string.listPlusServers, plusServers, plusServersInfo)) }
                basicServers.whenNotNullNorEmpty { groups.add(ServerTierGroupModel(R.string.listBasicServers, basicServers)) }
            }
            VpnUser.BASIC_TIER -> {
                basicServers.whenNotNullNorEmpty { groups.add(ServerTierGroupModel(R.string.listBasicServers, basicServers)) }
                freeServers.whenNotNullNorEmpty { groups.add(ServerTierGroupModel(R.string.listFreeServers, freeServers, freeServersInfo)) }
                plusServers.whenNotNullNorEmpty { groups.add(ServerTierGroupModel(R.string.listPlusServers, plusServers, plusServersInfo)) }
            }
            else -> {
                plusServers.whenNotNullNorEmpty { groups.add(ServerTierGroupModel(R.string.listPlusServers, plusServers, plusServersInfo)) }
                basicServers.whenNotNullNorEmpty { groups.add(ServerTierGroupModel(R.string.listBasicServers, basicServers)) }
                freeServers.whenNotNullNorEmpty { groups.add(ServerTierGroupModel(R.string.listFreeServers, freeServers, freeServersInfo)) }
            }
        }
        return groups
    }

    private fun getCountriesForList(secureCore: Boolean): List<VpnCountry> =
        if (secureCore)
            serverManager.getSecureCoreExitCountries()
        else
            serverManager.getVpnCountries()

    private fun getGatewayGroupsForList(secureCore: Boolean): List<GatewayGroup> =
        if (secureCore)
            emptyList()
        else
            serverManager.getGateways()

    fun connectOrDisconnect(vpnUiDelegate: VpnUiDelegate, connectIntent: ConnectIntent, triggerDescription: String) {
        if (!isConnectedTo(connectIntent)) {
            // Navigate to home screen after connection, compose is bridged through SharedFlow
            viewModelScope.launch {
                _navigateToHome.emit(Unit)
            }
            ProtonLogger.log(UiConnect, triggerDescription)
            val trigger = when {
                connectIntent is ConnectIntent.Server -> ConnectTrigger.Server(triggerDescription)
                else -> ConnectTrigger.Country(triggerDescription)
            }
            vpnConnectionManager.connect(vpnUiDelegate, connectIntent, trigger)
        } else {
            ProtonLogger.log(UiDisconnect, triggerDescription)
            val trigger = when {
                connectIntent is ConnectIntent.Server -> DisconnectTrigger.Server(triggerDescription)
                else -> DisconnectTrigger.Country(triggerDescription)
            }
            vpnConnectionManager.disconnect(trigger)
        }
    }

    private fun createBannerList(banner: UpsellBanner?): List<ServerListItemModel> {
        val bannerModel = when(banner) {
            is UpsellBanner.FreeDefault -> FreeUpsellBannerModel
            is UpsellBanner.Promo -> createPromoOfferBanner(banner.notification)
            null -> null
        }
        return bannerModel?.let { listOf(it) } ?: emptyList()
    }

    private fun createPromoOfferBanner(notification: ApiNotification): ServerListItemModel? =
        if (notification.offer?.panel?.button?.url?.isNotEmpty() == true &&
            notification.offer.panel.fullScreenImage?.source?.isNotEmpty() == true
        ) {
            val fullScreenImage = notification.offer.panel.fullScreenImage
            val imageSource = fullScreenImage.source.first()
            PromoOfferBannerModel(
                imageSource.url,
                fullScreenImage.alternativeText,
                notification.offer.panel.button,
                notification.offer.panel.isDismissible,
                TimeUnit.SECONDS.toMillis(notification.endTime).takeIf { notification.offer.panel.showCountdown },
                notification.id,
                notification.reference,
            )
        } else {
            null
        }

    private fun List<Server>.sortedForUi() =
        this.sortedBy { it.displayCity }
            .sortedBy { it.displayCity == null } // null cities go to the end of the list
            .sortedBy { it.isPartneshipServer } // partnership servers go to the end of the list
}
