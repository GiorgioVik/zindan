package net.typeblog.shelter.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class FinalizeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(applicationContext, DummyActivity::class.java).apply {
            action = DummyActivity.FINALIZE_PROVISION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(i)
        finish()
    }
}
