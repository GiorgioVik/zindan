package net.typeblog.shelter.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Single in-flight VPN batch-freeze job in the work profile with bounded retries.
 * Prevents duplicate work when receiver, [BatchFreezeService], and :vpnwatch fire together.
 */
object WorkProfileVpnFreezeCoordinator {
    private const val TAG = "VpnFreezeCoord"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 2000L
    private const val IN_FLIGHT_COOLDOWN_MS = 1500L

    private val handler = Handler(Looper.getMainLooper())
    private var sessionComplete = false
    private var inFlight = false
    private var lastAttemptElapsedMs = 0L
    private var activeSessionId = 0L

    fun resetSession() {
        sessionComplete = false
        inFlight = false
        lastAttemptElapsedMs = 0L
        activeSessionId = SystemClock.elapsedRealtime()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "VPN freeze session reset id=$activeSessionId")
    }

    fun markSessionComplete() {
        sessionComplete = true
        inFlight = false
    }

    /**
     * @return true if a freeze job was accepted (not deduplicated).
     */
    fun requestFreeze(context: Context, list: Array<String>, source: String): Boolean {
        val app = context.applicationContext
        if (!AntiSpyManager.isWorkProfile(app)) {
            return false
        }
        val normalized = Utility.normalizeStringList(list)
        if (normalized.isEmpty()) {
            Log.w(TAG, "empty list from $source")
            return false
        }
        if (sessionComplete) {
            Log.d(TAG, "skip: session complete ($source)")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (inFlight && now - lastAttemptElapsedMs < IN_FLIGHT_COOLDOWN_MS) {
            Log.d(TAG, "skip: in flight ($source)")
            return false
        }
        inFlight = true
        lastAttemptElapsedMs = now
        val sessionId = activeSessionId
        Log.i(TAG, "accept freeze from $source session=$sessionId size=${normalized.size}")
        runAttempt(app, normalized, source, sessionId, attempt = 0)
        return true
    }

    private fun runAttempt(
        context: Context,
        list: Array<String>,
        source: String,
        sessionId: Long,
        attempt: Int,
    ) {
        handler.post {
            if (sessionId != activeSessionId || sessionComplete) {
                inFlight = false
                return@post
            }
            if (!VpnTunnelDetector.isVpnActive(context)) {
                Log.d(TAG, "cancel: vpn no longer active ($source)")
                inFlight = false
                return@post
            }
            val result = WorkProfileBatchFreeze.freezeListWithResult(context, list)
            WorkProfileBatchFreeze.persistLastBatchResult(context, result)
            if (result.allHidden) {
                sessionComplete = true
                inFlight = false
                Utility.notifyVpnBatchFreezeSessionComplete(context, result.newlyFrozenCount > 0)
                Log.i(TAG, "complete from $source attempt=$attempt")
                return@post
            }
            if (attempt + 1 < MAX_RETRIES && VpnTunnelDetector.isVpnActive(context)) {
                Log.i(
                    TAG,
                    "retry ${attempt + 1}/${MAX_RETRIES} stillVisible=" +
                        "${result.stillVisiblePackages.size} from $source",
                )
                handler.postDelayed({
                    runAttempt(context, list, source, sessionId, attempt + 1)
                }, RETRY_DELAY_MS)
                return@post
            }
            inFlight = false
            Utility.notifyBatchFreezeComplete(context, result.newlyFrozenCount > 0)
            Log.w(
                TAG,
                "partial from $source stillVisible=${result.stillVisiblePackages.joinToString()}",
            )
        }
    }
}
