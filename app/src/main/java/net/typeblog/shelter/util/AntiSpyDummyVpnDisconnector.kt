package net.typeblog.shelter.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import net.typeblog.shelter.services.AntiSpyDummyVpnService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Displaces an active VPN using a short pseudo [android.net.VpnService] tunnel.
 * Must run [AntiSpyDummyVpnService] in the same process as {@code :vpnwatch} FGS.
 */
object AntiSpyDummyVpnDisconnector {
    private const val TAG = "AntiSpyDummyVpn"

    const val RESULT_CLEARED = 0
    const val RESULT_VPN_STILL_ACTIVE = 1
    const val RESULT_VPN_PERMISSION_REQUIRED = 2
    const val RESULT_FAILED = 3

    private const val MAX_ESTABLISH_ATTEMPTS = 4
    private const val HOLD_TUNNEL_MS = 500L
    private const val ESTABLISH_TIMEOUT_MS = 5000L
    private const val DISCONNECT_TIMEOUT_MS = 5000L
    private const val SETTLE_AFTER_DISCONNECT_MS = 400L
    private const val VPN_CLEAR_POLL_MS = 100L
    private const val VPN_CLEAR_MAX_WAIT_MS = 2000L
    private const val WHOLE_OP_TIMEOUT_MS = 30000L

    private val suppressReactions = AtomicBoolean(false)

    fun isSuppressingVpnReactions(): Boolean = suppressReactions.get()

    fun interface Callback {
        fun onResult(result: Int)
    }

    fun tryClearVpnAsync(context: Context, callback: Callback) {
        val app = context.applicationContext
        val resultHandler = Handler(context.mainLooper)
        val delivered = AtomicBoolean(false)

        val deliverFailure = Runnable {
            if (delivered.compareAndSet(false, true)) {
                callback.onResult(RESULT_FAILED)
            }
        }
        resultHandler.postDelayed(deliverFailure, WHOLE_OP_TIMEOUT_MS)

        Thread({
            suppressReactions.set(true)
            val result = try {
                tryClearVpnBlocking(app)
            } catch (e: Exception) {
                Log.e(TAG, "tryClearVpnBlocking failed pid=${Process.myPid()}", e)
                RESULT_FAILED
            } finally {
                suppressReactions.set(false)
            }
            resultHandler.post {
                resultHandler.removeCallbacks(deliverFailure)
                if (delivered.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "displacement finished result=$result pid=${Process.myPid()}"
                    )
                    callback.onResult(result)
                }
            }
        }, "anti-spy-vpn-clear").start()
    }

    private fun tryClearVpnBlocking(context: Context): Int {
        if (!VpnTunnelDetector.isVpnActive(context)) {
            return RESULT_CLEARED
        }

        for (attempt in 0 until MAX_ESTABLISH_ATTEMPTS) {
            if (!VpnTunnelDetector.isVpnActive(context)) {
                return RESULT_CLEARED
            }

            when (val cycleResult = runOneDummyCycle(context)) {
                RESULT_CLEARED -> return RESULT_CLEARED
                RESULT_VPN_PERMISSION_REQUIRED -> return RESULT_VPN_PERMISSION_REQUIRED
                RESULT_FAILED -> return RESULT_FAILED
            }
        }

        return if (VpnTunnelDetector.isVpnActive(context)) {
            RESULT_VPN_STILL_ACTIVE
        } else {
            RESULT_CLEARED
        }
    }

    private fun runOneDummyCycle(context: Context): Int {
        val established = AtomicBoolean(false)
        val disconnected = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val permissionRequired = AtomicBoolean(false)
        val lock = Object()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    AntiSpyDummyVpnService.BROADCAST_ESTABLISHED -> established.set(true)
                    AntiSpyDummyVpnService.BROADCAST_DISCONNECTED -> disconnected.set(true)
                    AntiSpyDummyVpnService.BROADCAST_PERMISSION_REQUIRED ->
                        permissionRequired.set(true)
                    AntiSpyDummyVpnService.BROADCAST_FAILED -> failed.set(true)
                }
                synchronized(lock) {
                    lock.notifyAll()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AntiSpyDummyVpnService.BROADCAST_ESTABLISHED)
            addAction(AntiSpyDummyVpnService.BROADCAST_DISCONNECTED)
            addAction(AntiSpyDummyVpnService.BROADCAST_FAILED)
            addAction(AntiSpyDummyVpnService.BROADCAST_PERMISSION_REQUIRED)
        }
        val app = context.applicationContext
        val lbm = LocalBroadcastManager.getInstance(app)
        registerReceiver(context, lbm, receiver, filter)

        try {
            startDummyService(app, AntiSpyDummyVpnService.ACTION_ESTABLISH)

            synchronized(lock) {
                val deadline = System.currentTimeMillis() + ESTABLISH_TIMEOUT_MS
                while (!established.get() && !failed.get() && !permissionRequired.get()) {
                    val wait = deadline - System.currentTimeMillis()
                    if (wait <= 0) {
                        break
                    }
                    try {
                        lock.wait(wait)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return RESULT_FAILED
                    }
                }
            }

            if (permissionRequired.get()) {
                Log.w(TAG, "dummy vpn needs permission pid=${Process.myPid()}")
                return RESULT_VPN_PERMISSION_REQUIRED
            }
            if (failed.get() || !established.get()) {
                Log.w(TAG, "dummy vpn establish failed pid=${Process.myPid()}")
                return RESULT_FAILED
            }

            sleep(HOLD_TUNNEL_MS)

            disconnected.set(false)
            startDummyService(app, AntiSpyDummyVpnService.ACTION_DISCONNECT)

            if (!waitForSignal(disconnected, lock, DISCONNECT_TIMEOUT_MS)) {
                Log.w(TAG, "dummy vpn disconnect timed out pid=${Process.myPid()}")
                return RESULT_FAILED
            }

            return if (isVpnClearedAfterDisconnect(context)) {
                RESULT_CLEARED
            } else {
                Log.w(TAG, "vpn still active after dummy cycle pid=${Process.myPid()}")
                RESULT_VPN_STILL_ACTIVE
            }
        } finally {
            unregisterReceiver(context, lbm, receiver)
        }
    }

    private fun registerReceiver(
        context: Context,
        lbm: LocalBroadcastManager,
        receiver: BroadcastReceiver,
        filter: IntentFilter
    ) {
        val handler = Handler(context.mainLooper)
        val gate = Object()
        handler.post {
            lbm.registerReceiver(receiver, filter)
            registerAppBroadcastReceiver(context.applicationContext, receiver, filter)
            synchronized(gate) {
                gate.notifyAll()
            }
        }
        synchronized(gate) {
            try {
                gate.wait(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun unregisterReceiver(
        context: Context,
        lbm: LocalBroadcastManager,
        receiver: BroadcastReceiver
    ) {
        val handler = Handler(context.mainLooper)
        val gate = Object()
        handler.post {
            try {
                lbm.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            try {
                context.applicationContext.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            synchronized(gate) {
                gate.notifyAll()
            }
        }
        synchronized(gate) {
            try {
                gate.wait(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun registerAppBroadcastReceiver(
        app: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                app,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun waitForSignal(signal: AtomicBoolean, lock: Object, timeoutMs: Long): Boolean {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!signal.get()) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) {
                    return false
                }
                try {
                    lock.wait(wait)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return true
    }

    private fun isVpnClearedAfterDisconnect(context: Context): Boolean {
        sleep(SETTLE_AFTER_DISCONNECT_MS)
        val deadline = System.currentTimeMillis() + VPN_CLEAR_MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!VpnTunnelDetector.isVpnActive(context)) {
                return true
            }
            sleep(VPN_CLEAR_POLL_MS)
        }
        return !VpnTunnelDetector.isVpnActive(context)
    }

    private fun startDummyService(app: Context, action: String) {
        val intent = Intent(app, AntiSpyDummyVpnService::class.java)
        intent.action = action
        app.startService(intent)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
