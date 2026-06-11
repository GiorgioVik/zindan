package net.typeblog.shelter.util

import android.content.Intent
import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.util.Date
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Opening access to actions across the profile boundary poses a security risk
// The risk is that other applications might also be able to start our activities
// through system's IntentForwarderActivity
// That activity runs in the system process, thus normal limitations like "permissions"
// and "exported" will not work.
// This class tries to fix it by appending a timestamp and a signature of the timestamp
// to our own Intents sent through the boundary, ensuring that only Shelter can invoke
// its high-privilege functions across that boundary, assuming that no other application
// would be able to access Shelter's internal storage to gain access to the private key.
// The private key is generated the first time this class is used, and then shared
// across the profile boundary. Shelter will always trust the first key it receives.
object AuthenticationUtility {
    fun signIntent(intent: Intent) {
        var key = LocalStorageManager.getInstance().getString(LocalStorageManager.PREF_AUTH_KEY)
        if (key == null) {
            try {
                val keyGen = KeyGenerator.getInstance("HmacSHA256")
                keyGen.init(256)
                key = bytesToHex(keyGen.generateKey().encoded)
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException("WTF?")
            }

            LocalStorageManager.getInstance().setString(LocalStorageManager.PREF_AUTH_KEY, key)

            intent.putExtra("auth_key", key)
        } else {
            val timestamp = Date().time
            intent.putExtra("timestamp", timestamp)
            intent.putExtra("signature", sign(key, timestamp))
        }
    }

    fun checkIntent(intent: Intent): Boolean {
        val key = LocalStorageManager.getInstance().getString(LocalStorageManager.PREF_AUTH_KEY)
        if (key == null) {
            return if (intent.hasExtra("auth_key")) {
                val authKey = intent.getStringExtra("auth_key") ?: return false
                LocalStorageManager.getInstance().setString(
                    LocalStorageManager.PREF_AUTH_KEY,
                    authKey
                )
                true
            } else {
                false
            }
        } else {
            val timestamp = Date().time
            val intentTimestamp = intent.getLongExtra("timestamp", 0)
            return timestamp - intentTimestamp < 30 * 1000 &&
                    sign(key, intentTimestamp) == intent.getStringExtra("signature")
        }
    }

    fun reset() {
        LocalStorageManager.getInstance().remove(LocalStorageManager.PREF_AUTH_KEY)
    }

    private fun sign(hexKey: String, timestamp: Long): String {
        try {
            val keySpec = SecretKeySpec(hexStringToByteArray(hexKey), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(keySpec)
            return bytesToHex(mac.doFinal(longToBytes(timestamp)))
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("WTF?")
        } catch (e: InvalidKeyException) {
            throw RuntimeException("WTF?")
        }
    }

    private val hexArray = "0123456789ABCDEF".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun hexStringToByteArray(s: String): ByteArray? {
        return try {
            val len = s.length
            if (len > 1) {
                ByteArray(len / 2) { i ->
                    ((Character.digit(s[i * 2], 16) shl 4)
                            + Character.digit(s[i * 2 + 1], 16)).toByte()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private fun longToBytes(x: Long): ByteArray {
        val buffer = ByteBuffer.allocate(Long.SIZE_BYTES)
        buffer.putLong(x)
        return buffer.array()
    }
}
