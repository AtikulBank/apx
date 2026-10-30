package ai.apx.ctrl

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val projReq = 1001
    private lateinit var status: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
        }
        status = TextView(this).apply { textSize = 14f }
        val b1 = Button(this).apply { text = "1 · Accessibility" }
        val b2 = Button(this).apply { text = "2 · Screen capture" }
        val b3 = Button(this).apply { text = "3 · Start service" }
        val b4 = Button(this).apply { text = "4 · Battery unrestricted" }

        b1.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b2.setOnClickListener {
            val m = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(m.createScreenCaptureIntent(), projReq)
        }
        b3.setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, ApxService::class.java).setAction(ApxService.ACT_START))
            Toast.makeText(this, "started", Toast.LENGTH_SHORT).show()
        }
        b4.setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        listOf(b1, b2, b3, b4).forEach { ll.addView(it) }
        ll.addView(status)
        setContentView(ScrollView(this).apply { addView(ll) })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        status.text = "accessibility: ${if (ApxAccessibility.inst != null) "OK" else "OFF"}\n" +
            "server: ${if (ApxService.up) "127.0.0.1:${ApxService.PORT}" else "stopped"}\n" +
            "capture: ${if (ApxService.projAlive) "granted" else "not granted"}\n" +
            "err: ${ApxService.lastErr.ifEmpty { "-" }}"
    }

    @Deprecated("deprecated")
    override fun onActivityResult(rq: Int, rc: Int, data: Intent?) {
        super.onActivityResult(rq, rc, data)
        if (rq == projReq && rc == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(this, Intent(this, ApxService::class.java)
                .setAction(ApxService.ACT_PROJ).putExtra("data", data).putExtra("rc", rc))
            Toast.makeText(this, "sent", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }
}
