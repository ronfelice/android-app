/*
 * Copyright (c) 2018 Proton Technologies AG
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
package com.protonvpn.android.di

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.google.gson.Gson
import com.protonvpn.android.BuildConfig
import com.protonvpn.android.ProtonApplication
import com.protonvpn.android.api.GuestHole
import com.protonvpn.android.api.ProtonApiRetroFit
import com.protonvpn.android.api.ProtonVPNRetrofit
import com.protonvpn.android.api.VpnApiClient
import com.protonvpn.android.api.VpnApiManager
import com.protonvpn.android.appconfig.ApiNotificationManager
import com.protonvpn.android.appconfig.AppConfig
import com.protonvpn.android.auth.data.VpnUserDao
import com.protonvpn.android.auth.usecase.CurrentUser
import com.protonvpn.android.auth.usecase.OnSessionClosed
import com.protonvpn.android.components.NotificationHelper
import com.protonvpn.android.concurrency.DefaultDispatcherProvider
import com.protonvpn.android.models.config.UserData
import com.protonvpn.android.tv.login.TvLoginPollDelayMs
import com.protonvpn.android.tv.login.TvLoginViewModel
import com.protonvpn.android.ui.home.ServerListUpdater
import com.protonvpn.android.ui.snackbar.DelegatedSnackManager
import com.protonvpn.android.utils.Constants.PRIMARY_VPN_API_URL
import com.protonvpn.android.utils.ServerManager
import com.protonvpn.android.utils.TrafficMonitor
import com.protonvpn.android.utils.UserPlanManager
import com.protonvpn.android.vpn.CertificateRepository
import com.protonvpn.android.vpn.ConnectivityMonitor
import com.protonvpn.android.vpn.MaintenanceTracker
import com.protonvpn.android.vpn.ProtonVpnBackendProvider
import com.protonvpn.android.vpn.RecentsManager
import com.protonvpn.android.vpn.VpnBackendProvider
import com.protonvpn.android.vpn.VpnConnectionErrorHandler
import com.protonvpn.android.vpn.VpnConnectionManager
import com.protonvpn.android.vpn.VpnErrorUIManager
import com.protonvpn.android.vpn.VpnLogCapture
import com.protonvpn.android.vpn.VpnStateMonitor
import com.protonvpn.android.vpn.ikev2.StrongSwanBackend
import com.protonvpn.android.vpn.openvpn.OpenVpnBackend
import com.protonvpn.android.vpn.wireguard.WireguardBackend
import com.protonvpn.android.vpn.wireguard.WireguardContextWrapper
import com.wireguard.android.backend.GoBackend
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.proton.core.account.domain.entity.AccountType
import me.proton.core.domain.entity.Product
import me.proton.core.network.data.ApiManagerFactory
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.NetworkManager
import me.proton.core.network.data.NetworkPrefs
import me.proton.core.network.data.ProtonCookieStore
import me.proton.core.network.data.client.ClientIdProviderImpl
import me.proton.core.network.data.client.ExtraHeaderProviderImpl
import me.proton.core.network.domain.ApiManager
import me.proton.core.network.domain.NetworkManager
import me.proton.core.network.domain.client.ClientIdProvider
import me.proton.core.network.domain.client.ExtraHeaderProvider
import me.proton.core.network.domain.humanverification.HumanVerificationListener
import me.proton.core.network.domain.humanverification.HumanVerificationProvider
import me.proton.core.network.domain.server.ServerTimeListener
import me.proton.core.network.domain.session.SessionListener
import me.proton.core.network.domain.session.SessionProvider
import me.proton.core.util.kotlin.DispatcherProvider
import me.proton.core.util.kotlin.takeIfNotBlank
import java.util.Random
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModuleProd {

    @Singleton
    @Provides
    fun provideNetworkManager(): NetworkManager =
        NetworkManager(ProtonApplication.getAppContext())

    @Singleton
    @Provides
    fun provideApiFactory(
        networkManager: NetworkManager,
        apiClient: VpnApiClient,
        clientIdProvider: ClientIdProvider,
        cookieStore: ProtonCookieStore,
        scope: CoroutineScope,
        sessionProvider: SessionProvider,
        sessionListener: SessionListener,
        humanVerificationProvider: HumanVerificationProvider,
        humanVerificationListener: HumanVerificationListener,
        extraHeaderProvider: ExtraHeaderProvider
    ): ApiManagerFactory {
        val appContext = ProtonApplication.getAppContext()
        val serverTimeListener = object : ServerTimeListener {
            // We'd need to implement that when we start using core's crypto module.
            override fun onServerTimeUpdated(epochSeconds: Long) {}
        }
        return if (BuildConfig.DEBUG) {
            ApiManagerFactory(
                PRIMARY_VPN_API_URL,
                apiClient,
                clientIdProvider,
                serverTimeListener,
                networkManager,
                NetworkPrefs(appContext),
                sessionProvider,
                sessionListener,
                humanVerificationProvider,
                humanVerificationListener,
                cookieStore,
                scope,
                certificatePins = emptyArray(),
                alternativeApiPins = emptyList(),
                extraHeaderProvider = extraHeaderProvider,
                apiConnectionListener = null
            )
        } else {
            ApiManagerFactory(
                PRIMARY_VPN_API_URL,
                apiClient,
                clientIdProvider,
                serverTimeListener,
                networkManager,
                NetworkPrefs(appContext),
                sessionProvider,
                sessionListener,
                humanVerificationProvider,
                humanVerificationListener,
                cookieStore,
                scope,
                apiConnectionListener = null
            )
        }
    }

    @Singleton
    @Provides
    fun provideAPI(
        scope: CoroutineScope,
        apiManager: VpnApiManager,
    ) = ProtonApiRetroFit(scope, apiManager)

    @Singleton
    @Provides
    fun provideUserPrefs(): UserData = UserData.load()

    @Singleton
    @Provides
    fun provideVpnConnectionManager(
        scope: CoroutineScope,
        userData: UserData,
        backendManager: VpnBackendProvider,
        networkManager: NetworkManager,
        vpnConnectionErrorHandler: VpnConnectionErrorHandler,
        vpnStateMonitor: VpnStateMonitor,
        notificationHelper: NotificationHelper,
        serverManager: ServerManager,
        certificateRepository: CertificateRepository, // Make sure that CertificateRepository instance is created
        maintenanceTracker: MaintenanceTracker, // Make sure that MaintenanceTracker instance is created
    ) = VpnConnectionManager(
        ProtonApplication.getAppContext(),
        userData,
        backendManager,
        networkManager,
        vpnConnectionErrorHandler,
        vpnStateMonitor,
        notificationHelper,
        serverManager,
        scope,
        System::currentTimeMillis
    )

    @Singleton
    @Provides
    fun provideVpnBackendManager(
        appConfig: AppConfig,
        serverManager: ServerManager,
        wireguardBackend: WireguardBackend,
        openVpnBackend: OpenVpnBackend,
        strongSwanBackend: StrongSwanBackend
    ): VpnBackendProvider =
        ProtonVpnBackendProvider(
            appConfig,
            strongSwanBackend,
            openVpnBackend,
            wireguardBackend,
            serverManager
        )

    @Singleton
    @Provides
    fun provideRecentManager(
        scope: CoroutineScope,
        vpnStateMonitor: VpnStateMonitor,
        serverManager: ServerManager,
        onSessionClosed: OnSessionClosed
    ) = RecentsManager(scope, vpnStateMonitor, serverManager, onSessionClosed)

    @Singleton
    @Provides
    @TvLoginPollDelayMs
    fun provideTvLoginPollDelayMs() = TvLoginViewModel.POLL_DELAY_MS
}

@Suppress("TooManyFunctions")
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val random = Random()

    @Provides
    @Singleton
    fun provideProduct(): Product =
        Product.Vpn

    @Provides
    @Singleton
    fun provideRequiredAccountType(): AccountType =
        AccountType.External

    @Provides
    @Singleton
    fun provideRandom(): Random = random

    @Provides
    @Singleton
    fun provideMainScope(): CoroutineScope = scope

    @Provides
    @Singleton
    fun provideExtraHeaderProvider(): ExtraHeaderProvider = ExtraHeaderProviderImpl().apply {
        BuildConfig.BLACK_TOKEN?.takeIfNotBlank()?.let {
            addHeaders("X-atlas-secret" to it)
        }
    }

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    fun providePackageManager(): PackageManager = ProtonApplication.getAppContext().packageManager

    @Provides
    fun provideActivityManager(): ActivityManager =
        ProtonApplication.getAppContext()
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    @Singleton
    @Provides
    fun provideServerManager(userData: UserData, currentUser: CurrentUser) =
        ServerManager(ProtonApplication.getAppContext(), userData, currentUser)

    @Singleton
    @Provides
    fun provideServerListUpdater(
        api: ProtonApiRetroFit,
        serverManager: ServerManager,
        currentUser: CurrentUser,
        vpnStateMonitor: VpnStateMonitor,
        userPlanManager: UserPlanManager,
    ) = ServerListUpdater(scope, api, serverManager, currentUser, vpnStateMonitor, userPlanManager)

    @Singleton
    @Provides
    fun provideAppConfig(
        api: ProtonApiRetroFit,
        currentUser: CurrentUser,
        userPlanManager: UserPlanManager
    ): AppConfig =
        AppConfig(scope, api, currentUser, userPlanManager)

    @Singleton
    @Provides
    fun provideApiNotificationManager(appConfig: AppConfig): ApiNotificationManager =
        ApiNotificationManager(System::currentTimeMillis, appConfig)

    @Singleton
    @Provides
    fun provideVpnApiManager(apiProvider: ApiProvider, currentUser: CurrentUser) =
        VpnApiManager(apiProvider, currentUser)

    @Provides
    @Singleton
    fun provideProtonCookieStore(): ProtonCookieStore =
        ProtonCookieStore(ProtonApplication.getAppContext())

    @Provides
    @Singleton
    fun provideClientIdProvider(protonCookieStore: ProtonCookieStore): ClientIdProvider =
        ClientIdProviderImpl(PRIMARY_VPN_API_URL, protonCookieStore)

    @Singleton
    @Provides
    fun provideApiProvider(apiFactory: ApiManagerFactory, sessionProvider: SessionProvider): ApiProvider =
        ApiProvider(apiFactory, sessionProvider)

    @Singleton
    @Provides
    fun provideApiManager(
        vpnApiManager: VpnApiManager
    ): ApiManager<ProtonVPNRetrofit> = vpnApiManager

    @Singleton
    @Provides
    fun provideApiClient(userData: UserData, vpnStateMonitor: VpnStateMonitor): VpnApiClient =
        VpnApiClient(scope, userData, vpnStateMonitor)

    @Singleton
    @Provides
    fun provideGson() = Gson()

    @Singleton
    @Provides
    fun provideUserPlanManager(
        api: ProtonApiRetroFit,
        vpnUserDao: VpnUserDao,
        currentUser: CurrentUser,
        vpnStateMonitor: VpnStateMonitor,
    ): UserPlanManager = UserPlanManager(api, vpnStateMonitor, currentUser, vpnUserDao, System::currentTimeMillis)

    @Singleton
    @Provides
    fun provideVpnConnectionErrorHandler(
        api: ProtonApiRetroFit,
        appConfig: AppConfig,
        userData: UserData,
        userPlanManager: UserPlanManager,
        serverManager: ServerManager,
        vpnStateMonitor: VpnStateMonitor,
        serverListUpdater: ServerListUpdater,
        networkManager: NetworkManager,
        vpnBackendProvider: VpnBackendProvider,
        currentUser: CurrentUser,
        vpnErrorUiManager: VpnErrorUIManager
    ) = VpnConnectionErrorHandler(
        scope,
        api,
        appConfig,
        userData,
        userPlanManager,
        serverManager,
        vpnStateMonitor,
        serverListUpdater,
        networkManager,
        vpnBackendProvider,
        currentUser,
        vpnErrorUiManager
    )

    @Singleton
    @Provides
    fun provideVpnErrorUIManager(
        appConfig: AppConfig,
        userData: UserData,
        userPlanManager: UserPlanManager,
        vpnStateMonitor: VpnStateMonitor,
        notificationHelper: NotificationHelper,
        currentUser: CurrentUser,
    ) = VpnErrorUIManager(scope, ProtonApplication.getAppContext(), appConfig, userData, currentUser,
            userPlanManager, vpnStateMonitor, notificationHelper)

    @Singleton
    @Provides
    fun provideCertificateRepository(
        api: ProtonApiRetroFit,
        userPlanManager: UserPlanManager,
        dispatcherProvider: DispatcherProvider,
        currentUser: CurrentUser
    ): CertificateRepository = CertificateRepository(
        scope,
        dispatcherProvider,
        ProtonApplication.getAppContext(),
        api,
        System::currentTimeMillis,
        userPlanManager,
        currentUser)

    @Singleton
    @Provides
    fun provideVpnStateMonitor() = VpnStateMonitor()

    @Singleton
    @Provides
    fun provideConnectivityMonitor() = ConnectivityMonitor(scope, ProtonApplication.getAppContext())

    @Singleton
    @Provides
    fun provideNotificationHelper(
        vpnStateMonitor: VpnStateMonitor,
        trafficMonitor: TrafficMonitor,
    ) = NotificationHelper(
        ProtonApplication.getAppContext(),
        scope,
        vpnStateMonitor,
        trafficMonitor
    )

    @Singleton
    @Provides
    fun provideWireguardBackend(
        userData: UserData,
        networkManager: NetworkManager,
        appConfig: AppConfig,
        certificateRepository: CertificateRepository,
        dispatcherProvider: DispatcherProvider,
        currentUser: CurrentUser
    ) = WireguardBackend(
        ProtonApplication.getAppContext(),
        GoBackend(WireguardContextWrapper(ProtonApplication.getAppContext())),
        networkManager,
        userData,
        appConfig,
        certificateRepository,
        dispatcherProvider,
        scope,
        currentUser
    )

    @Singleton
    @Provides
    fun provideStrongSwanBackend(
        userData: UserData,
        networkManager: NetworkManager,
        appConfig: AppConfig,
        certificateRepository: CertificateRepository,
        dispatcherProvider: DispatcherProvider,
        currentUser: CurrentUser
    ) = StrongSwanBackend(
        random,
        networkManager,
        scope,
        userData,
        appConfig,
        certificateRepository,
        dispatcherProvider,
        currentUser
    )

    @Singleton
    @Provides
    fun provideOpenVpnBackend(
        userData: UserData,
        networkManager: NetworkManager,
        appConfig: AppConfig,
        certificateRepository: CertificateRepository,
        dispatcherProvider: DispatcherProvider,
        currentUser: CurrentUser
    ) = OpenVpnBackend(
        random,
        networkManager,
        userData,
        appConfig,
        System::currentTimeMillis,
        certificateRepository,
        scope,
        dispatcherProvider,
        currentUser
    )

    @Singleton
    @Provides
    fun provideMaintenanceTracker(
        appConfig: AppConfig,
        vpnStateMonitor: VpnStateMonitor,
        vpnErrorHandler: VpnConnectionErrorHandler
    ) = MaintenanceTracker(
        scope,
        ProtonApplication.getAppContext(),
        appConfig,
        vpnStateMonitor,
        vpnErrorHandler
    )

    @Singleton
    @Provides
    fun provideTrafficMonitor(
        vpnStateMonitor: VpnStateMonitor,
        connectivityMonitor: ConnectivityMonitor
    ) = TrafficMonitor(
        ProtonApplication.getAppContext(),
        scope,
        SystemClock::elapsedRealtime,
        vpnStateMonitor,
        connectivityMonitor
    )

    @Singleton
    @Provides
    fun provideGuestHole(
        serverManager: ServerManager,
        vpnMonitor: VpnStateMonitor,
        connectionManager: VpnConnectionManager
    ) = GuestHole(scope, serverManager, vpnMonitor, connectionManager)

    @Provides
    @Singleton
    fun provideLogCapture(dispatcherProvider: DispatcherProvider) =
        VpnLogCapture(scope, dispatcherProvider, SystemClock::elapsedRealtime)

    @Provides
    @Singleton
    fun provideDelegatedSnackManager() = DelegatedSnackManager(SystemClock::elapsedRealtime)
}
