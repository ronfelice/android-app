/*
 * Copyright (c) 2023. Proton AG
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

package com.protonvpn.app.redesign.recents.usecases

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.protonvpn.android.auth.usecase.CurrentUser
import com.protonvpn.android.models.config.VpnProtocol
import com.protonvpn.android.models.vpn.ConnectingDomain
import com.protonvpn.android.models.vpn.ConnectionParams
import com.protonvpn.android.models.vpn.Server
import com.protonvpn.android.models.vpn.ServerEntryInfo
import com.protonvpn.android.models.vpn.usecase.SupportsProtocol
import com.protonvpn.android.redesign.CountryId
import com.protonvpn.android.redesign.countries.Translator
import com.protonvpn.android.redesign.recents.data.RecentConnection
import com.protonvpn.android.redesign.recents.ui.RecentAvailability
import com.protonvpn.android.redesign.recents.usecases.RecentsListViewStateFlow
import com.protonvpn.android.redesign.recents.usecases.RecentsManager
import com.protonvpn.android.redesign.vpn.ConnectIntent
import com.protonvpn.android.redesign.vpn.ServerFeature
import com.protonvpn.android.redesign.vpn.ui.ConnectIntentSecondaryLabel
import com.protonvpn.android.redesign.vpn.ui.ConnectIntentViewState
import com.protonvpn.android.redesign.vpn.ui.GetConnectIntentViewState
import com.protonvpn.android.settings.data.EffectiveCurrentUserSettings
import com.protonvpn.android.settings.data.EffectiveCurrentUserSettingsCached
import com.protonvpn.android.settings.data.LocalUserSettings
import com.protonvpn.android.utils.ServerManager
import com.protonvpn.android.utils.Storage
import com.protonvpn.android.vpn.ProtocolSelection
import com.protonvpn.android.vpn.VpnState
import com.protonvpn.android.vpn.VpnStateMonitor
import com.protonvpn.android.vpn.VpnStatusProviderUI
import com.protonvpn.test.shared.MockSharedPreference
import com.protonvpn.test.shared.TestCurrentUserProvider
import com.protonvpn.test.shared.TestDispatcherProvider
import com.protonvpn.test.shared.TestUser
import com.protonvpn.test.shared.createGetSmartProtocols
import com.protonvpn.test.shared.createInMemoryServersStore
import com.protonvpn.test.shared.createServer
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecentsListViewStateFlowTests {

    @get:Rule
    var rule = InstantTaskExecutorRule()

    @MockK
    private lateinit var mockRecentsManager: RecentsManager

    private lateinit var vpnStateMonitor: VpnStateMonitor

    private lateinit var currentUserProvider: TestCurrentUserProvider
    private lateinit var serverManager: ServerManager
    private lateinit var settingsFlow: MutableStateFlow<LocalUserSettings>
    private lateinit var testScope: TestScope

    private val serverCh: Server = createServer("1", exitCountry = "ch", tier = 2)
    private val serverIs: Server = createServer("2", exitCountry = "is", tier = 2)
    private val serverSe: Server = createServer("3", exitCountry = "se", tier = 2)
    private val serverSecureCore: Server =
        createServer("4", exitCountry = "pl", entryCountry = "ch", isSecureCore = true, tier = 2)

    private lateinit var viewStateFlow: RecentsListViewStateFlow

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Storage.setPreferences(MockSharedPreference())

        currentUserProvider = TestCurrentUserProvider(TestUser.plusUser.vpnUser)
        val testCoroutineScheduler = TestCoroutineScheduler()
        val testDispatcher = UnconfinedTestDispatcher(testCoroutineScheduler)
        testScope = TestScope(testDispatcher)
        val testDispatcherProvider = TestDispatcherProvider(testDispatcher)
        Dispatchers.setMain(testDispatcher) // Remove this when ServerManager no longer uses asLiveData().
        val currentUser = CurrentUser(testScope.backgroundScope, currentUserProvider)
        val clock = { testCoroutineScheduler.currentTime }

        vpnStateMonitor = VpnStateMonitor()
        val vpnStatusProviderUI = VpnStatusProviderUI(testScope.backgroundScope, vpnStateMonitor)

        coEvery { mockRecentsManager.getRecentsList() } returns flowOf(emptyList())
        coEvery { mockRecentsManager.getMostRecentConnection() } returns flowOf(null)

        settingsFlow = MutableStateFlow(LocalUserSettings.Default)
        val effectiveUserSettings = EffectiveCurrentUserSettings(testScope.backgroundScope, settingsFlow)
        val effectiveUserSettingsCached = EffectiveCurrentUserSettingsCached(settingsFlow)
        val supportsProtocol = SupportsProtocol(createGetSmartProtocols())

        serverManager = ServerManager(
            testScope.backgroundScope,
            effectiveUserSettingsCached,
            currentUser,
            clock,
            supportsProtocol,
            createInMemoryServersStore(),
            mockk(),
        )
        runBlocking {
            serverManager.setServers(listOf(serverCh, serverIs, serverSe, serverSecureCore), null)
        }
        val translator = Translator(testScope.backgroundScope, serverManager)
        viewStateFlow = RecentsListViewStateFlow(
            mockRecentsManager,
            GetConnectIntentViewState(serverManager, translator),
            serverManager,
            supportsProtocol,
            effectiveUserSettings,
            vpnStatusProviderUI,
            currentUser
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain() // Remove this when ServerManager no longer uses asLiveData().
    }

    @Test
    fun defaultConnectionIsShownWhenThereAreNoRecents() = testScope.runTest {
        val viewState = viewStateFlow.first()
        val expectedConnectionCard =
            ConnectIntentViewState(CountryId.fastest, null, false, null, emptySet())
        assertEquals(expectedConnectionCard, viewState.connectionCard.connectIntentViewState)
        assertEquals(emptyList(), viewState.recents)
    }

    @Test
    fun whenConnectedTheConnectionIsShownInConnectionCard() = testScope.runTest {
        vpnStateMonitor.updateStatus(
            VpnStateMonitor.Status(
                VpnState.Connected,
                ConnectionParams(ConnectIntentSwitzerland, serverCh, null, null)
            )
        )
        val viewState = viewStateFlow.first()
        assertEquals(ConnectIntentViewSwitzerland, viewState.connectionCard.connectIntentViewState)
        assertEquals(emptyList(), viewState.recents)
    }

    @Test
    fun mostRecentConnectionShownOnlyInConnectionCard() = testScope.runTest {
        coEvery { mockRecentsManager.getRecentsList() } returns flowOf(DefaultRecents)
        coEvery { mockRecentsManager.getMostRecentConnection() } returns flowOf(RecentIceland)

        val viewState = viewStateFlow.first()

        assertEquals(ConnectIntentViewIceland, viewState.connectionCard.connectIntentViewState)
        assertEquals(
            listOf(ConnectIntentViewSecureCore, ConnectIntentViewFastest, ConnectIntentViewSweden),
            viewState.recents.map { it.connectIntent }
        )
    }

    @Test
    fun pinnedItemWithActiveConnectionIsDisplayedInRecents() = testScope.runTest {
        coEvery { mockRecentsManager.getRecentsList() } returns flowOf(DefaultRecents)
        vpnStateMonitor.updateStatus(
            VpnStateMonitor.Status(
                VpnState.Connected,
                ConnectionParams(ConnectIntentSecureCore, serverSecureCore, null, null)
            )
        )
        val viewState = viewStateFlow.first()
        val expectedRecents = listOf(
            ConnectIntentViewSecureCore,
            ConnectIntentViewFastest,
            ConnectIntentViewSweden,
            ConnectIntentViewIceland,
        )
        assertEquals(ConnectIntentViewSecureCore, viewState.connectionCard.connectIntentViewState)
        assertEquals(expectedRecents, viewState.recents.map { it.connectIntent })
        assertTrue(viewState.recents.first().isPinned)
        assertTrue(viewState.recents.first().isConnected)
    }

    @Test
    fun whenMostRecentIsUnavailableTheDefaultIsShownInConnectionCard() = testScope.runTest {
        coEvery { mockRecentsManager.getRecentsList() } returns flowOf(DefaultRecents)
        coEvery { mockRecentsManager.getMostRecentConnection() } returns flowOf(DefaultRecents.first())

        val offlineSecureCore = serverSecureCore.copy(isOnline = false)
        serverManager.setServers(listOf(serverCh, serverIs, serverSe, offlineSecureCore), null)

        val viewState = viewStateFlow.first()

        val expected = createViewStateForFastestInCountry(ConnectIntent.Default)
        assertEquals(expected, viewState.connectionCard.connectIntentViewState)
    }

    @Test
    fun paidServersUnavailableToFreeUser() = testScope.runTest {
        coEvery { mockRecentsManager.getRecentsList() } returns flowOf(DefaultRecents)
        currentUserProvider.vpnUser = TestUser.freeUser.vpnUser
        val servers = listOf(
            serverSecureCore,
            serverCh,
            serverSe.copy(tier = 0),
            serverIs.copy(tier = 0),
        )
        serverManager.setServers(servers, null)
        val viewState = viewStateFlow.first()
        val expected = listOf(
            RecentAvailability.UNAVAILABLE_PLAN,
            RecentAvailability.ONLINE,
            RecentAvailability.ONLINE,
            RecentAvailability.ONLINE,
        )
        assertEquals(expected, viewState.recents.map { it.availability })
    }

    @Test
    fun offlineServersAreMarkedOffline() = testScope.runTest {
        coEvery { mockRecentsManager.getRecentsList() } returns flowOf(DefaultRecents)
        val servers = listOf(
            serverSecureCore,
            serverCh,
            serverSe.copy(isOnline = false),
            serverIs.copy(isOnline = false),
        )
        serverManager.setServers(servers, null)
        val viewState = viewStateFlow.first()

        val expected = listOf(
            RecentAvailability.ONLINE,
            RecentAvailability.ONLINE,
            RecentAvailability.AVAILABLE_OFFLINE,
            RecentAvailability.AVAILABLE_OFFLINE,
        )
        assertEquals(expected, viewState.recents.map { it.availability })
    }

    @Test
    fun serverStatusChangeIsReflectedInRecents() = testScope.runTest(dispatchTimeoutMs = 5_000) {
        coEvery { mockRecentsManager.getRecentsList() } returns flowOf(DefaultRecents)
        val viewStates = viewStateFlow
            .onEach {
                val offlineSecureCoreServer = serverSecureCore.copy(isOnline = false)
                serverManager.setServers(listOf(serverCh, offlineSecureCoreServer), null)
            }
            .take(2)
            .toList()

        val secureCoreItemBefore = viewStates.first().recents.find { it.isPinned }
        val secureCoreItemAfter = viewStates.last().recents.find { it.isPinned }
        assertNotNull(secureCoreItemBefore)
        assertNotNull(secureCoreItemAfter)
        assertEquals(RecentAvailability.ONLINE, secureCoreItemBefore.availability)
        assertEquals(RecentAvailability.AVAILABLE_OFFLINE, secureCoreItemAfter.availability)
    }

    @Test
    fun protocolSettingChangeIsReflectedInRecents() = testScope.runTest(dispatchTimeoutMs = 5_000) {
        coEvery { mockRecentsManager.getRecentsList() } returns flowOf(DefaultRecents)
        val wgEntryProtocols = mapOf(
            ProtocolSelection(VpnProtocol.WireGuard).apiName to ServerEntryInfo("1.2.3.5", listOf(22, 443))
        )
        val wgOnlyDomain =
            ConnectingDomain(entryIp = null, wgEntryProtocols, "domain", null, "id1", publicKeyX25519 = "key")
        val wgOnlyServer = serverSecureCore.copy(connectingDomains = listOf(wgOnlyDomain))
        serverManager.setServers(listOf(serverCh, wgOnlyServer), null)

        val viewStates = viewStateFlow
            .onEach {
                settingsFlow.update { it.copy(protocol = ProtocolSelection(VpnProtocol.OpenVPN)) }
            }
            .take(2)
            .toList()

        val itemBefore = viewStates.first().recents.find { it.isPinned }
        val itemAfter = viewStates.last().recents.find { it.isPinned }
        assertNotNull(itemBefore)
        assertNotNull(itemAfter)
        assertEquals(RecentAvailability.ONLINE, itemBefore.availability)
        assertEquals(RecentAvailability.UNAVAILABLE_PROTOCOL, itemAfter.availability)
    }

    companion object {
        val ConnectIntentSecureCore = ConnectIntent.SecureCore(CountryId("PL"), CountryId.switzerland)
        val ConnectIntentFastest = ConnectIntent.FastestInCountry(CountryId.fastest, setOf(ServerFeature.P2P))
        val ConnectIntentSweden = ConnectIntent.FastestInCountry(CountryId.sweden, emptySet())
        val ConnectIntentIceland = ConnectIntent.FastestInCountry(CountryId.iceland, emptySet())
        val ConnectIntentSwitzerland = ConnectIntent.FastestInCountry(CountryId.switzerland, emptySet())

        // ConnectIntentViewStates for the constants above:
        val ConnectIntentViewSecureCore = ConnectIntentViewState(
            ConnectIntentSecureCore.exitCountry,
            ConnectIntentSecureCore.entryCountry,
            true,
            ConnectIntentSecondaryLabel.SecureCore(null, ConnectIntentSecureCore.entryCountry),
            emptySet()
        )
        val ConnectIntentViewFastest = createViewStateForFastestInCountry(ConnectIntentFastest)
        val ConnectIntentViewSweden = createViewStateForFastestInCountry(ConnectIntentSweden)
        val ConnectIntentViewIceland = createViewStateForFastestInCountry(ConnectIntentIceland)
        val ConnectIntentViewSwitzerland = createViewStateForFastestInCountry(ConnectIntentSwitzerland)

        val RecentSecureCore = RecentConnection(1, true, ConnectIntentSecureCore)
        val RecentFastest = RecentConnection(2, false, ConnectIntentFastest)
        val RecentSweden = RecentConnection(3, false, ConnectIntentSweden)
        val RecentIceland = RecentConnection(4, false, ConnectIntentIceland)

        val DefaultRecents = listOf(RecentSecureCore, RecentFastest, RecentSweden, RecentIceland)

        private fun createViewStateForFastestInCountry(intent: ConnectIntent.FastestInCountry) =
            ConnectIntentViewState(intent.country, null, false, null, intent.features)
    }
}
