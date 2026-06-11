package net.typeblog.shelter

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import net.typeblog.shelter.services.FileShuttleService
import net.typeblog.shelter.services.ShelterService
import net.typeblog.shelter.util.LocalStorageManager
import net.typeblog.shelter.util.SettingsManager

class ShelterApplication : Application() {
    private var shelterServiceConnection: ServiceConnection? = null
    private var fileShuttleServiceConnection: ServiceConnection? = null

    override fun onCreate() {
        super.onCreate()
        LocalStorageManager.initialize(this)
        SettingsManager.initialize(this)
    }

    fun bindShelterService(conn: ServiceConnection, foreground: Boolean) {
        unbindShelterService()
        val intent = Intent(applicationContext, ShelterService::class.java)
        intent.putExtra("foreground", foreground)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        shelterServiceConnection = conn
    }

    fun bindFileShuttleService(conn: ServiceConnection) {
        unbindFileShuttleService()
        val intent = Intent(applicationContext, FileShuttleService::class.java)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        fileShuttleServiceConnection = conn
    }

    fun unbindShelterService() {
        shelterServiceConnection?.let {
            try {
                unbindService(it)
            } catch (_: Exception) {
                // Service may already be unbound.
            }
        }
        shelterServiceConnection = null
    }

    fun unbindFileShuttleService() {
        fileShuttleServiceConnection?.let {
            try {
                unbindService(it)
            } catch (_: Exception) {
            }
        }
        fileShuttleServiceConnection = null
    }
}
