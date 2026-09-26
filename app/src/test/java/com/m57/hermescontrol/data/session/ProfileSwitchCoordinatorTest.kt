package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.Ordering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The switch coordinator is the single atomic profile-switch flow — every
 * surface (Profiles screen, future quick-switch) routes through it. These
 * tests pin the ORDER of operations, because chat's fresh-session behavior
 * depends on the switch broadcast landing BEFORE the socket re-dial.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSwitchCoordinatorTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        // setMain is REQUIRED, and it is NOT the thing that used to bleed.
        //
        // The leak was mockkStatic(Dispatchers::class) -- absent here, keep it that
        // way. Avoiding setMain as well was the actual bug: with no setMain,
        // Dispatchers.Main is the JVM's "missing" dispatcher, and switchProfile's
        // _switched.tryEmit resumes collectors onto Main. Once ANY earlier class in
        // the same test JVM has installed and then reset a TestMainDispatcher,
        // dispatching to Main throws -- which is exactly why this class passed 5/5
        // in isolation but lost 4 tests in full-suite runs (2x DispatchException
        // "Dispatchers.Main threw an exception", 2x "test body did not run to
        // completion"). Every other test class here already does this.
        Dispatchers.setMain(testDispatcher)
        // Drive the coordinator's network hops on the test dispatcher instead of a
        // live IO thread pool. With real Dispatchers.IO the mocked calls were
        // recorded from a different thread and the Ordering.SEQUENCE assertions
        // became load-dependent -- green on an idle machine, 2 failures while the
        // emulator saturated the CPU.
        ProfileSwitchCoordinator.ioDispatcher = testDispatcher

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi

        mockkObject(AuthManager)
        every { AuthManager.setActiveProfileId(any()) } returns Unit

        mockkObject(HermesWsClient)
        every { HermesWsClient.disconnect() } returns Unit
        every { HermesWsClient.connect() } returns Unit
    }

    @After
    fun tearDown() {
        ProfileSwitchCoordinator.ioDispatcher = Dispatchers.IO
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun coldStartBootstrapsMissingLocalScopeFromServerActiveProfile() =
        runTest {
            val active = MutableStateFlow<String?>(null)
            every { AuthManager.activeProfileId } returns active
            every { AuthManager.setActiveProfileId(any()) } answers {
                active.value = firstArg()
            }
            coEvery { mockApi.getActiveProfile() } returns
                Response.success(ActiveProfileResponse(active = "karellen"))

            val restored = ProfileSwitchCoordinator.restoreActiveProfileScopeIfMissing()

            assertEquals("karellen", restored)
            assertEquals("karellen", active.value)
            verify(exactly = 1) { AuthManager.setActiveProfileId("karellen") }
        }

    @Test
    fun coldStartKeepsExistingExplicitLocalProfileWithoutServerLookup() =
        runTest {
            val active = MutableStateFlow<String?>("work")
            every { AuthManager.activeProfileId } returns active

            val restored = ProfileSwitchCoordinator.restoreActiveProfileScopeIfMissing()

            assertEquals("work", restored)
            coVerify(exactly = 0) { mockApi.getActiveProfile() }
            verify(exactly = 0) { AuthManager.setActiveProfileId(any()) }
        }

    @Test
    fun `success flips server then persists selection then re-dials socket`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            val result = ProfileSwitchCoordinator.switchProfile("meow")

            assertTrue(result is NetworkResult.Success)
            coVerify { mockApi.setActiveProfile(SetActiveProfileRequest("meow")) }
            // Ordering.ORDERED, not SEQUENCE: the point of this test is the relative
            // ORDER of the switch steps. SEQUENCE also demands that NO other call
            // touches the mocked objects in between, which is untestable in a shared
            // test JVM -- real-thread tests (HermesWsClientTest) leave background
            // ticket-mint retries calling AuthManager.getBaseUrl()/getServerStore()
            // for the rest of the process. Under load those 18 foreign calls landed
            // inside the verification window and failed the exact-count check even
            // though all 5 steps fired in the correct order (proven by the MockK
            // call trace: 1) setActiveProfileId 2) rebuild ... 21) sync 22)
            // disconnect 23) connect). ORDER keeps the order-pinning and ignores
            // foreign traffic.
            verify(ordering = Ordering.ORDERED) {
                AuthManager.setActiveProfileId("meow")
                HermesWsClient.disconnect()
                HermesWsClient.connect()
            }
        }

    @Test
    fun `success broadcasts the switch so chat wipes before the re-dial`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)
            // Subscribe BEFORE the switch — exactly how ChatViewModel does it.
            val received = Channel<SwitchedPayload>(Channel.UNLIMITED)
            backgroundScope.launch {
                ProfileSwitchCoordinator.switched.collect { received.send(it) }
            }
            runCurrent()

            ProfileSwitchCoordinator.switchProfile("meow")
            runCurrent()

            // The broadcast is buffered with capacity 1, so chat observes the
            // wipe BEFORE gateway.ready arrives on the re-dialed socket and
            // auto-creates the fresh session (desktop requestFreshSession).
            assertEquals("meow", received.tryReceive().getOrNull()?.profileName)
        }

    @Test
    fun `failure touches nothing`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns errorResponse(500)

            val result = ProfileSwitchCoordinator.switchProfile("meow")

            assertTrue(result is NetworkResult.Failure)
            verify(exactly = 0) { AuthManager.setSelectedProfileId(any()) }
            verify(exactly = 0) { AuthManager.setActiveProfileId(any()) }
            verify(exactly = 0) { HermesWsClient.disconnect() }
            verify(exactly = 0) { HermesWsClient.connect() }
        }

    @Test
    fun `connection switch re-homes selection retrofit and socket in order`() =
        runTest {
            every { AuthManager.setSelectedProfileId(any()) } returns Unit
            every { ApiClient.rebuild() } returns Unit
            coEvery { AuthManager.syncCookieStoreForProfile(any()) } returns Unit

            ProfileSwitchCoordinator.switchConnectionProfile("prof-2")

            // Load-bearing order: selection persists FIRST (token cache +
            // cookie scope follow), Retrofit re-points, the cookie store swap
            // is AWAITED (a racing dial mints the ticket with the previous
            // server's cookie → 401 → dead socket), then the socket re-dials
            // — so chat's wipe (via the broadcast) lands before the new
            // gateway's gateway.ready auto-creates the fresh session.
            //
            // ORDERED, not SEQUENCE -- see the sibling test above: SEQUENCE's
            // exact-count semantics fail under load in the shared test JVM when
            // leaked background retries interleave foreign AuthManager/ApiClient
            // calls into the window. ORDER still pins this 5-step sequence.
            coVerify(ordering = Ordering.ORDERED) {
                AuthManager.setSelectedProfileId("prof-2")
                ApiClient.rebuild()
                AuthManager.syncCookieStoreForProfile("prof-2")
                HermesWsClient.disconnect()
                HermesWsClient.connect()
            }
        }

    @Test
    fun `connection switch broadcasts so chat wipes before the re-dial`() =
        runTest {
            every { AuthManager.setSelectedProfileId(any()) } returns Unit
            every { ApiClient.rebuild() } returns Unit
            coEvery { AuthManager.syncCookieStoreForProfile(any()) } returns Unit
            // Subscribe BEFORE the switch — exactly how ChatViewModel does it.
            val received = Channel<String>(Channel.UNLIMITED)
            backgroundScope.launch {
                ProfileSwitchCoordinator.connectionSwitched.collect { received.send(it) }
            }
            runCurrent()

            ProfileSwitchCoordinator.switchConnectionProfile("prof-2")
            runCurrent()

            assertEquals("prof-2", received.tryReceive().getOrNull())
        }

    // ── Canonical session intent tests ─────────────────────────────

    @Test
    fun `canonical intent happy-path consume returns session id`() =
        runTest {
            val generation = ProfileSwitchCoordinator.setCanonicalIntent("sess-1", "karellen")

            val result = ProfileSwitchCoordinator.consumeCanonicalIntent("karellen", generation)

            assertEquals("sess-1", result)
        }

    @Test
    fun `canonical intent mismatch profile returns null`() =
        runTest {
            ProfileSwitchCoordinator.setCanonicalIntent("sess-1", "karellen")

            val result = ProfileSwitchCoordinator.consumeCanonicalIntent("default", 1L)

            assertEquals(null, result)
        }

    @Test
    fun `canonical intent mismatch generation returns null`() =
        runTest {
            val generation = ProfileSwitchCoordinator.setCanonicalIntent("sess-1", "karellen")

            val result = ProfileSwitchCoordinator.consumeCanonicalIntent("karellen", generation - 1)

            assertEquals(null, result)
        }

    @Test
    fun `canonical intent cleared on switch failure`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns errorResponse(500)
            ProfileSwitchCoordinator.setCanonicalIntent("sess-1", "karellen")

            ProfileSwitchCoordinator.switchProfile("karellen")
            runCurrent()

            // After a failed switch the intent must be cleared so no stale
            // gateway.ready can consume it under the wrong profile.
            val generation = ProfileSwitchCoordinator.canonicalIntentGeneration
            assertTrue(
                ProfileSwitchCoordinator.consumeCanonicalIntent("karellen", generation) == null,
            )
        }

    @Test
    fun `canonical intent cleared explicitly`() =
        runTest {
            ProfileSwitchCoordinator.setCanonicalIntent("sess-1", "karellen")

            ProfileSwitchCoordinator.clearCanonicalIntent()

            val result = ProfileSwitchCoordinator.consumeCanonicalIntent("karellen", 1L)
            assertEquals(null, result)
        }

    @Test
    fun `overlapping switch uses newest intent generation`() =
        runTest {
            // First switch starts setting intent for profile A
            val genA = ProfileSwitchCoordinator.setCanonicalIntent("sess-a", "alpha")
            // Overlapping switch B starts before A's gateway.ready
            val genB = ProfileSwitchCoordinator.setCanonicalIntent("sess-b", "beta")

            // Consume with A's generation — should fail (stale by B overwriting)
            assertEquals(
                null,
                ProfileSwitchCoordinator.consumeCanonicalIntent("alpha", genA),
            )
            // Consume with B's generation — should succeed
            assertEquals(
                "sess-b",
                ProfileSwitchCoordinator.consumeCanonicalIntent("beta", genB),
            )
            // B's intent consumed once — second consume returns null
            assertEquals(
                null,
                ProfileSwitchCoordinator.consumeCanonicalIntent("beta", genB),
            )
        }

    @Test
    fun `no canonical intent returns null`() =
        runTest {
            ProfileSwitchCoordinator.clearCanonicalIntent()

            val result = ProfileSwitchCoordinator.consumeCanonicalIntent("karellen", 0L)

            assertEquals(null, result)
        }

    // ── Behavioral integration tests (real switchProfile path) ───────

    @Test
    fun `bot selection through switchProfile with ownerToken consumes exactly once`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            val token = ProfileSwitchCoordinator.setCanonicalIntent("sess-resume", "karellen")
            val result = ProfileSwitchCoordinator.switchProfile("karellen", token)
            runCurrent()

            assertTrue(result is NetworkResult.Success)
            assertEquals("sess-resume", ProfileSwitchCoordinator.consumeCanonicalIntent("karellen"))
            assertNull(ProfileSwitchCoordinator.consumeCanonicalIntent("karellen"))
        }

    @Test
    fun `late A-ready after B starts does not consume B intent`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            val genA = ProfileSwitchCoordinator.setCanonicalIntent("sess-a", "alpha")
            ProfileSwitchCoordinator.switchProfile("alpha", genA)
            runCurrent()

            val genB = ProfileSwitchCoordinator.setCanonicalIntent("sess-b", "beta")
            ProfileSwitchCoordinator.switchProfile("beta", genB)
            runCurrent()

            // A's late ready with "alpha" -> should not consume B's intent
            assertNull(ProfileSwitchCoordinator.consumeCanonicalIntent("alpha"))
            // B's ready -> consumes B's intent
            assertEquals("sess-b", ProfileSwitchCoordinator.consumeCanonicalIntent("beta"))
        }

    @Test
    fun `A failure after B arm does not clear B intent`() =
        runTest {
            val tokenA = ProfileSwitchCoordinator.setCanonicalIntent("sess-a", "alpha")
            val tokenB = ProfileSwitchCoordinator.setCanonicalIntent("sess-b", "beta")

            ProfileSwitchCoordinator.clearCanonicalIntent(tokenA)

            ProfileSwitchCoordinator.testSetActiveSwitchGeneration(tokenB)
            assertEquals("sess-b", ProfileSwitchCoordinator.consumeCanonicalIntent("beta"))
        }

    @Test
    fun `same-profile overlap uses latest switch`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            val genOld = ProfileSwitchCoordinator.setCanonicalIntent("sess-old", "karellen")
            ProfileSwitchCoordinator.switchProfile("karellen", genOld)
            runCurrent()

            val genNew = ProfileSwitchCoordinator.setCanonicalIntent("sess-new", "karellen")
            ProfileSwitchCoordinator.switchProfile("karellen", genNew)
            runCurrent()

            assertEquals("sess-new", ProfileSwitchCoordinator.consumeCanonicalIntent("karellen"))
        }

    @Test
    fun `exactly one REST switch disconnect connect cycle`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            ProfileSwitchCoordinator.switchProfile("karellen")
            runCurrent()

            coVerify(exactly = 1) { mockApi.setActiveProfile(any()) }
            verify(exactly = 1) { AuthManager.setActiveProfileId("karellen") }
            verify(exactly = 1) { HermesWsClient.disconnect() }
            verify(exactly = 1) { HermesWsClient.connect() }
        }

    @Test
    fun `overlapping switch does not produce extra REST cycle`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            val genA = ProfileSwitchCoordinator.setCanonicalIntent("sess-a", "alpha")
            ProfileSwitchCoordinator.switchProfile("alpha", genA)
            runCurrent()

            val genB = ProfileSwitchCoordinator.setCanonicalIntent("sess-b", "beta")
            ProfileSwitchCoordinator.switchProfile("beta", genB)
            runCurrent()

            coVerify(exactly = 2) { mockApi.setActiveProfile(any()) }
            verify(exactly = 2) { AuthManager.setActiveProfileId(any()) }
            verify(exactly = 2) { HermesWsClient.disconnect() }
            verify(exactly = 2) { HermesWsClient.connect() }
        }

    @Test
    fun `same-profile overlap after consume starts fresh`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            val gen1 = ProfileSwitchCoordinator.setCanonicalIntent("sess-first", "karellen")
            ProfileSwitchCoordinator.switchProfile("karellen", gen1)
            runCurrent()
            assertEquals("sess-first", ProfileSwitchCoordinator.consumeCanonicalIntent("karellen"))

            val gen2 = ProfileSwitchCoordinator.setCanonicalIntent("sess-second", "karellen")
            ProfileSwitchCoordinator.switchProfile("karellen", gen2)
            runCurrent()
            assertEquals("sess-second", ProfileSwitchCoordinator.consumeCanonicalIntent("karellen"))

            coVerify(exactly = 2) { mockApi.setActiveProfile(any()) }
            verify(exactly = 2) { AuthManager.setActiveProfileId("karellen") }
            verify(exactly = 2) { HermesWsClient.disconnect() }
            verify(exactly = 2) { HermesWsClient.connect() }
        }

    @Test
    fun `cancelled intent cleared by token does not survive`() =
        runTest {
            val token = ProfileSwitchCoordinator.setCanonicalIntent("sess-cancel", "karellen")
            // Simulate cancellation cleanup
            ProfileSwitchCoordinator.clearCanonicalIntent(token)

            ProfileSwitchCoordinator.testSetActiveSwitchGeneration(token)
            assertNull(ProfileSwitchCoordinator.consumeCanonicalIntent("karellen"))
        }

    // ── Behavioral integration tests (real overlapped switchProfile) ─

    @Test
    fun `A REST suspended B arms then A fails does not clear B intent`() =
        runTest {
            // First switch A: REST is slow (suspended)
            coEvery { mockApi.setActiveProfile(SetActiveProfileRequest("alpha")) } coAnswers {
                delay(5000)
                errorResponse(500)
            }
            // Second switch B: succeeds immediately
            coEvery { mockApi.setActiveProfile(SetActiveProfileRequest("beta")) } returns Response.success(Unit)

            val tokenA = ProfileSwitchCoordinator.setCanonicalIntent("sess-a", "alpha") // gen 1
            val tokenB = ProfileSwitchCoordinator.setCanonicalIntent("sess-b", "beta") // gen 2

            // Launch A's switch (will suspend on slow REST) with A's owner token
            val resultA = CompletableDeferred<NetworkResult<Unit>>()
            val jobA =
                launch {
                    resultA.complete(ProfileSwitchCoordinator.switchProfile("alpha", tokenA))
                }
            runCurrent() // A starts the REST call, hits delay

            // B's switch completes immediately with B's owner token
            val resultB = ProfileSwitchCoordinator.switchProfile("beta", tokenB)
            runCurrent()
            assertTrue(resultB is NetworkResult.Success)

            // Advance time so A's REST completes with failure
            advanceTimeBy(5000)
            runCurrent()

            // A should have failed
            assertTrue(resultA.await() is NetworkResult.Failure)

            // B's intent should survive A's failure cleanup because A used
            // ownerToken=1, so clearCanonicalIntent(1) does NOT erase gen=2 intent.
            assertEquals(
                "B's intent must survive A's owner-scoped failure cleanup",
                "sess-b",
                ProfileSwitchCoordinator.consumeCanonicalIntent("beta"),
            )
        }

    @Test
    fun `late A ready after B succeeds uses captured token not global profile`() =
        runTest {
            // Simulate the ChatViewModel binding path: the switched event
            // collector captures the profile + generation, then handleGatewayReady
            // uses the captured values instead of AuthManager.activeProfileId.
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            // Switch A: sets intent for "alpha", succeeds
            val genA = ProfileSwitchCoordinator.setCanonicalIntent("sess-a", "alpha")
            ProfileSwitchCoordinator.switchProfile("alpha", genA)
            runCurrent()

            // Capture the pending switch token (as ChatViewModel does in the
            // switched collector) BEFORE B's switch changes global state.
            val capturedAProfile = "alpha"
            val capturedAGen = genA

            // Switch B: sets intent for "beta", succeeds — changes global state
            val genB = ProfileSwitchCoordinator.setCanonicalIntent("sess-b", "beta")
            ProfileSwitchCoordinator.switchProfile("beta", genB)
            runCurrent()

            // A's late handleGatewayReady: uses captured A token AND expected
            // generation, NOT AuthManager.activeProfileId (which is now "beta")
            // and NOT the global activeSwitchGeneration (which is now 2).
            // consumeCanonicalIntent("alpha", expectedGeneration=1) validates:
            // intent.gen=1 == expectedGen=1 BUT intent.profileName="beta" != "alpha"
            val result =
                ProfileSwitchCoordinator.consumeCanonicalIntent(
                    capturedAProfile,
                    expectedGeneration = capturedAGen,
                )
            assertNull("A's late ready must not consume B's intent", result)

            // B's gateway.ready can still consume B's intent
            assertEquals("sess-b", ProfileSwitchCoordinator.consumeCanonicalIntent("beta"))
        }

    @Test
    fun `consume with expectedGeneration validates against captured gen not global latest`() =
        runTest {
            // When consumeCanonicalIntent receives expectedGeneration > 0,
            // it validates against THAT generation instead of the global
            // activeSwitchGeneration. This is the ChatViewModel path where
            // pendingSwitchGeneration is captured from the switched event
            // and passed to consume.

            val genA = ProfileSwitchCoordinator.setCanonicalIntent("sess-a", "alpha")
            ProfileSwitchCoordinator.testSetActiveSwitchGeneration(genA)

            // Global active changed to a higher gen (B succeeded after A captured its token).
            val genB = ProfileSwitchCoordinator.setCanonicalIntent("sess-b", "beta")
            ProfileSwitchCoordinator.testSetActiveSwitchGeneration(genB)

            // Pending intent is now gen=2 (sess-b, beta). Global active=2.

            // Without expectedGeneration: validates against global active=2,
            // intent gen=2 matches → consumes "sess-b"
            assertEquals(
                "sess-b",
                ProfileSwitchCoordinator.consumeCanonicalIntent("beta"),
            )

            // activeSwitchGeneration was reset to 0 by the consume above.
            // Set a new intent at gen=3 while global active=0.
            val genC = ProfileSwitchCoordinator.setCanonicalIntent("sess-c", "gamma")

            // Without expectedGeneration: activeSwitchGeneration=0 → null
            assertNull(
                "No active=0 without expectedGen",
                ProfileSwitchCoordinator.consumeCanonicalIntent("gamma"),
            )

            // With expectedGeneration=genC: validates against genC despite
            // global active being 0. Intent gen=3 matches → consumes "sess-c"
            val result =
                ProfileSwitchCoordinator.consumeCanonicalIntent(
                    "gamma",
                    expectedGeneration = genC,
                )
            assertEquals("sess-c", result)
        }

    @Test
    fun `true simultaneous CAS consumers only one wins`() =
        runTest {
            val token = ProfileSwitchCoordinator.setCanonicalIntent("sess-race", "racer")
            ProfileSwitchCoordinator.testSetActiveSwitchGeneration(token)

            val winner = CompletableDeferred<String?>()
            val loser = CompletableDeferred<String?>()

            // Launch two consumers racing on CAS
            launch {
                winner.complete(ProfileSwitchCoordinator.consumeCanonicalIntent("racer"))
            }
            launch {
                loser.complete(ProfileSwitchCoordinator.consumeCanonicalIntent("racer"))
            }
            advanceUntilIdle()

            assertEquals("sess-race", winner.await())
            assertNull("Second consumer must get null", loser.await())
        }

    @Test
    fun `coroutine cancellation during switchProfile cleans up intent`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } coAnswers {
                delay(5000)
                Response.success(Unit)
            }

            val token = ProfileSwitchCoordinator.setCanonicalIntent("sess-cancel", "karellen")

            // Launch the switch and cancel before REST completes
            val job =
                launch {
                    try {
                        ProfileSwitchCoordinator.switchProfile("karellen", token)
                    } catch (_: CancellationException) {
                        // BotsScreen catch block calls clearCanonicalIntent(token)
                        ProfileSwitchCoordinator.clearCanonicalIntent(token)
                        throw CancellationException("Cancelled")
                    }
                }
            runCurrent() // A starts the REST call
            job.cancel()
            advanceUntilIdle()

            // Intent should be cleared by the catch block
            ProfileSwitchCoordinator.testSetActiveSwitchGeneration(token)
            assertNull(
                "Cancelled switch must clear its intent",
                ProfileSwitchCoordinator.consumeCanonicalIntent("karellen"),
            )
        }

    @Test
    fun `same-profile A late-ready with expectedGeneration does not consume B intent`() =
        runTest {
            // Both A and B switch to the same profile "karellen" with different
            // canonical sessions. A's late ready must not consume B's intent.
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            val genA = ProfileSwitchCoordinator.setCanonicalIntent("sess-a", "karellen")
            ProfileSwitchCoordinator.switchProfile("karellen", genA)
            runCurrent()

            // ChatViewModel captures A's switch generation
            val capturedGenA = genA
            val capturedProfile = "karellen"

            // B switches to the same profile with a different session
            val genB = ProfileSwitchCoordinator.setCanonicalIntent("sess-b", "karellen")
            ProfileSwitchCoordinator.switchProfile("karellen", genB)
            runCurrent()

            // A's late handleGatewayReady uses captured expectedGeneration (genA).
            // The pending intent is now gen=2 (sess-b, karellen).
            // consumeCanonicalIntent("karellen", expectedGeneration=genA):
            //   intent.gen=2 != expectedGen=1 → null
            assertNull(
                "A's late ready with expectedGeneration=genA must not consume B's gen=2 intent",
                ProfileSwitchCoordinator.consumeCanonicalIntent(
                    capturedProfile,
                    expectedGeneration = capturedGenA,
                ),
            )

            // B's ready can still consume B's intent
            assertEquals(
                "sess-b",
                ProfileSwitchCoordinator.consumeCanonicalIntent("karellen"),
            )
        }

    @Test
    fun `consume with expectedGeneration resets activeSwitchGeneration on success`() =
        runTest {
            // After a successful consume with expectedGeneration, the global
            // activeSwitchGeneration must be reset to 0, preventing a second
            // consume (even with a matching intent from a later set).
            val genA = ProfileSwitchCoordinator.setCanonicalIntent("sess-a", "alpha")
            ProfileSwitchCoordinator.testSetActiveSwitchGeneration(genA)

            assertEquals(
                "sess-a",
                ProfileSwitchCoordinator.consumeCanonicalIntent(
                    "alpha",
                    expectedGeneration = genA,
                ),
            )

            // Second attempt with fresh intent but no active generation
            val genB = ProfileSwitchCoordinator.setCanonicalIntent("sess-b", "beta")
            // activeSwitchGeneration was reset to 0 by consume above
            assertNull(
                "No active generation after consume resets global",
                ProfileSwitchCoordinator.consumeCanonicalIntent("beta"),
            )
        }

    private fun <T> errorResponse(code: Int): Response<T> = Response.error(code, "{}".toResponseBody(null))
}
