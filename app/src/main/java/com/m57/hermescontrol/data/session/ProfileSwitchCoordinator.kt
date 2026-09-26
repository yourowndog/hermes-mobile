package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/*
 * The single flow that performs a profile switch — the mobile equivalent of
 * desktop's re-home (``requestFreshSession`` + socket swap). Every surface
 * that switches profiles goes through here, so the switch is atomic instead
 * of a pile of scattered patches.
 *
 * Order matters:
 *  1. Flip the server's sticky active profile (REST).
 *  2. Persist the LOCAL selection — the REST interceptor (``?profile=``) and
 *     the WS params injector (``params.profile``) now scope everything to the
 *     new profile. The per-server token fallback (phase 1) keeps auth intact:
 *     no re-login, restart-safe.
 *  3. Emit [switched] BEFORE the socket re-dial, so chat wipes its stale
 *     conversation first — when the reconnected socket delivers
 *     ``gateway.ready``, ``handleGatewayReady`` sees no open session and
 *     auto-creates a FRESH session in the new profile (desktop parity).
 *  4. Re-dial the WebSocket so the gateway re-homes chat to the new profile.
 */

/**
 * Canonical-session intent that survives the socket re-dial.
 *
 * Scoped to target profile and switch generation so that [handleGatewayReady]
 * in [ChatViewModel] can verify it belongs to the current connection before
 * consuming it. Prevents cross-profile consumption and stale-intent races
 * from overlapping switches.
 */
data class CanonicalSessionIntent(
    val sessionId: String,
    val profileName: String,
    val generation: Long,
)

/**
 * Immutable payload emitted on every profile switch.
 *
 * Carries the profile name and a monotonically increasing owner token that
 * uniquely identifies this switch call. Together these let consumers (e.g.
 * [ChatViewModel]) match a [WsEvent.GatewayReady] back to the exact switch
 * that triggered the reconnect, without reading a shared mutable field that
 * a concurrent second switch could have overwritten.
 *
 * The [ownerToken] derives from the same generation counter that scopes the
 * canonical-session intent, so a consumer that holds both the payload and
 * the canonical intent generation can verify they belong to the same logical
 * switch without reading a shared mutable field.
 */
data class SwitchedPayload(
    val profileName: String,
    val ownerToken: Long,
)

object ProfileSwitchCoordinator {
    /**
     * Dispatcher for the blocking network hops below.
     *
     * Injectable so tests can drive the whole switch on a TestDispatcher. With the
     * real Dispatchers.IO these paths hop to a live thread pool and the ORDER of
     * the mocked calls becomes load-dependent -- ProfileSwitchCoordinatorTest's
     * Ordering.SEQUENCE checks passed on an idle machine but lost 2 tests while the
     * emulator saturated the CPU, and its setMain-less sibling tests failed
     * outright whenever another class had left Dispatchers.Main broken.
     */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _switched = MutableSharedFlow<SwitchedPayload>(extraBufferCapacity = 1)
    val switched: SharedFlow<SwitchedPayload> = _switched.asSharedFlow()

    private val _connectionSwitched = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val connectionSwitched: SharedFlow<String> = _connectionSwitched.asSharedFlow()

    // ── Canonical-session intent (profile- and generation-scoped) ─────

    /**
     * Atomic reference holding the pending canonical-session intent.
     * Compare-and-swap guarantees one-shot consumption — only one consumer
     * can atomically clear and win the session id.
     */
    private val pendingCanonicalIntent = AtomicReference<CanonicalSessionIntent?>(null)

    /**
     * Monotonic generation counter for switch tokens. Thread-safe via
     * AtomicLong (not read-modify-write race on volatile Long).
     */
    private val nextSwitchGeneration = AtomicLong(0L)

    /**
     * The generation token of the most recent successful [switchProfile] call.
     * Read by [ChatViewModel] during the [switched] event collector, set here
     * BEFORE the event is emitted so the collector captures the correct token.
     * Zero when there is no active switch.
     */
    @Volatile
    internal var activeSwitchGeneration: Long = 0L
        private set

    /**
     * Set a canonical-session intent scoped to [profileName]. Returns the
     * generation token that must match on consumption — older intents are
     * stale and ignored.
     *
     * Call BEFORE [switchProfile] so the intent is armed in time for a fast
     * gateway.ready. Clear the intent with [clearCanonicalIntent] if the
     * switch fails or is cancelled.
     */
    fun setCanonicalIntent(
        sessionId: String,
        profileName: String,
    ): Long {
        val generation = nextSwitchGeneration.incrementAndGet()
        pendingCanonicalIntent.set(CanonicalSessionIntent(sessionId, profileName, generation))
        return generation
    }

    /**
     * Consume the pending canonical-session intent, atomically one-shot.
     *
     * Validates that [profileName] matches the intent AND that the intent's
     * generation matches [expectedGeneration] (when > 0) OR the global
     * [activeSwitchGeneration] (when [expectedGeneration] is 0). This ensures
     * that only the intent for the switch that actually produced the current
     * WebSocket connection can be consumed — a late [gateway.ready] from an
     * older switch cannot consume a newer intent or resume the wrong session.
     *
     * When called from [ChatViewModel.handleGatewayReady], pass the
     * [pendingSwitchGeneration] captured from the [switched] event collector
     * as [expectedGeneration] — this binds the validation to the ACTUAL
     * switch that produced the connection, not the latest global winner.
     *
     * Uses AtomicReference.compareAndSet for atomic one-shot consumption:
     * duplicate concurrent calls race on CAS and only one wins.
     *
     * If the pending intent is stale (generation < expectedGeneration or
     * activeSwitchGeneration), it is atomically cleared so it does not keep
     * matching future consumers.
     *
     * Returns the session id on success, null on mismatch or no intent.
     */
    fun consumeCanonicalIntent(
        profileName: String,
        expectedGeneration: Long = 0L,
    ): String? {
        while (true) {
            val intent = pendingCanonicalIntent.get() ?: return null
            val activeGen =
                expectedGeneration.takeIf { it > 0L } ?: activeSwitchGeneration
            if (activeGen <= 0L) return null

            if (intent.profileName == profileName && intent.generation == activeGen) {
                if (pendingCanonicalIntent.compareAndSet(intent, null)) {
                    activeSwitchGeneration = 0L
                    return intent.sessionId
                }
                // CAS failed — another consumer won; retry
                continue
            }
            // Stale intent (older than active switch) — clear so it can't
            // keep blocking consumption of a future intent.
            if (intent.generation < activeGen) {
                pendingCanonicalIntent.compareAndSet(intent, null)
            }
            return null
        }
    }

    /** Atomically clear any pending canonical intent. */
    fun clearCanonicalIntent() {
        pendingCanonicalIntent.set(null)
    }

    /**
     * Clear the pending canonical intent only when its generation matches
     * [token]. Used by BotsScreen failure/cancellation cleanup to avoid
     * clearing a newer switch's intent.
     */
    fun clearCanonicalIntent(token: Long) {
        if (token > 0L) {
            val intent = pendingCanonicalIntent.get()
            if (intent != null && intent.generation == token) {
                pendingCanonicalIntent.compareAndSet(intent, null)
            }
        } else {
            pendingCanonicalIntent.set(null)
        }
    }

    /** Test-only: set activeSwitchGeneration for deterministic test setup. */
    internal fun testSetActiveSwitchGeneration(generation: Long) {
        activeSwitchGeneration = generation
    }

    /**
     * The generation of the most recent [setCanonicalIntent] call. Zero when
     * no intent has ever been set. Consumers pass this to [consumeCanonicalIntent]
     * so that only the latest intent can be consumed.
     */
    val canonicalIntentGeneration: Long get() = nextSwitchGeneration.get()

    // ── Profile switch operations ────────────────────────────────────

    /**
     * Restore the server-side Hermes profile scope on a cold/fresh app start.
     *
     * A fresh install has no encrypted active_profile_id yet. If chat opens
     * before the user manually visits Profiles, WS session.create/resume omit
     * params.profile and the multiplex gateway falls back to its launch profile
     * (normally default). The server already persists the operator's active
     * Hermes profile, so use that as bootstrap authority only when local scope
     * is missing.
     *
     * A profile selected while the request is in flight wins: re-check local
     * state before writing so startup never clobbers an explicit user switch.
     */
    suspend fun restoreActiveProfileScopeIfMissing(): String? {
        AuthManager.activeProfileId.value
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val result =
            withContext(ioDispatcher) {
                safeApiCall { ApiClient.hermesApi.getActiveProfile() }
            }
        val serverProfile =
            (result as? NetworkResult.Success)
                ?.data
                ?.active
                ?.takeIf { it.isNotBlank() }

        if (serverProfile != null && AuthManager.activeProfileId.value.isNullOrBlank()) {
            AuthManager.setActiveProfileId(serverProfile)
        }
        return AuthManager.activeProfileId.value
    }

    /**
     * Switch the active Hermes profile (bot/agent profile).
     *
     * On REST success, binds the active switch generation to [ownerToken]
     * (when > 0) or the pending intent's token (when 0), so that
     * [consumeCanonicalIntent] can validate it after the socket re-dial
     * delivers [gateway.ready]. On REST failure, clears only the intent
     * owned by the captured token.
     *
     * Callers that already have an immutable owner token from
     * [setCanonicalIntent] MUST pass it as [ownerToken] so a failure or
     * success of this switch cannot affect a newer switch's intent.
     * Without [ownerToken] (default 0) the function samples the pending
     * intent at entry, which is vulnerable to overlapping switches.
     */
    suspend fun switchProfile(
        name: String,
        ownerToken: Long = 0L,
    ): NetworkResult<Unit> {
        // Use the caller's immutable owner token when provided—
        // prevents a failure path from clearing a newer switch's
        // intent, or a success from binding the wrong generation.
        val token = if (ownerToken > 0L) ownerToken else pendingCanonicalIntent.get()?.generation ?: 0L

        val result =
            withContext(ioDispatcher) {
                safeApiCall { ApiClient.hermesApi.setActiveProfile(SetActiveProfileRequest(name)) }
            }
        if (result !is NetworkResult.Success) {
            // Clear pending canonical intent on REST failure so a stale
            // gateway.ready cannot consume a session for the wrong profile.
            // Use [ownerToken] so we only clear the intent owned by this
            // switch — a newer switch's intent is preserved.
            clearCanonicalIntent(token)
            return result
        }

        AuthManager.setActiveProfileId(name)
        // Bind the active switch generation to this switch's token BEFORE
        // the switched event is emitted, so ChatViewModel's collector can
        // capture it for gateway.ready validation. consumeCanonicalIntent
        // then validates the pending intent against this generation, which
        // prevents a late gateway.ready from an older switch from consuming
        // a newer intent.
        activeSwitchGeneration = token
        _switched.emit(SwitchedPayload(profileName = name, ownerToken = token))
        // The ticket mint inside connect() does blocking network I/O — it must
        // run off the main thread or the dial crashes with
        // NetworkOnMainThreadException and falls back to the 1s reconnect
        // retry (visible in the 2026-08-06 live logcat).
        withContext(ioDispatcher) {
            HermesWsClient.disconnect()
            HermesWsClient.connect()
        }
        return result
    }

    /**
     * Switch the active server connection profile (not just the bot profile).
     *
     * The server connection profile determines which Hermes server the mobile
     * app talks to (e.g. a Tailscale endpoint vs the default LAN address).
     * Unlike [switchProfile] (which changes the active bot/hermes profile on
     * the same server), this repoints both Retrofit and the WebSocket to a
     * different server.
     *
     * Order matters:
     *  1. Persist the LOCAL selection — the token cache, cookie scope and
     *     [AuthManager.contextFlow] re-home to the new profile.
     *  2. Rebuild Retrofit so REST targets the new server.
     *  3. Emit [connectionSwitched] BEFORE the socket re-dial, so chat wipes
     *     its stale conversation first; the re-dialed socket then delivers
     *     gateway.ready → handleGatewayReady auto-creates a FRESH session on
     *     the new server (desktop requestFreshSession parity).
     *  4. Re-dial the WebSocket off the main thread (the ticket mint does
     *     blocking I/O — NetworkOnMainThreadException otherwise).
     */
    suspend fun switchConnectionProfile(profileId: String?) {
        AuthManager.setSelectedProfileId(profileId)
        ApiClient.rebuild()
        _connectionSwitched.emit(profileId.orEmpty())
        withContext(ioDispatcher) {
            // The WS ticket mint reads the cookie jar's ACTIVE store; the
            // selection change swaps that store asynchronously, so a dial
            // that races it mints with the PREVIOUS server's cookie → 401 →
            // aborted socket with no retry. Await the swap before dialing
            // (idempotent no-op when it already landed).
            AuthManager.syncCookieStoreForProfile(profileId)
            HermesWsClient.disconnect()
            HermesWsClient.connect()
        }
    }
}
