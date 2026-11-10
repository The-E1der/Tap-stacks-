package com.example.stackbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 15f }
        layout.addView(status)

        layout.addView(btn("1. Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        layout.addView(btn("2. Tap test (center, in 5 s)") {
            Toast.makeText(this, "Switch to another app now", Toast.LENGTH_LONG).show()
            ui.postDelayed({
                val svc = TapService.instance
                if (svc == null) {
                    Toast.makeText(this, "Tap service is OFF", Toast.LENGTH_LONG).show()
                } else {
                    val m = resources.displayMetrics
                    svc.tapAt(m.widthPixels / 2f, m.heightPixels / 2f)
                }
            }, 5000)
        })

        layout.addView(btn("3. Start capture (dry run)") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), 1)
        })

        val leadRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        leadRow.addView(btn("Lead -10 ms") {
            CaptureService.leadMs = Math.max(0, CaptureService.leadMs - 10)
            refresh()
        })
        leadRow.addView(btn("Lead +10 ms") {
            CaptureService.leadMs = CaptureService.leadMs + 10
            refresh()
        })
        layout.addView(leadRow)

        layout.addView(btn("Stop capture") {
            stopService(Intent(this, CaptureService::class.java))
        })

        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
        }
    }

    private fun refresh() {
        val m = resources.displayMetrics
        val tap = if (TapService.instance != null) "ON" else "OFF"
        val cap = if (CaptureService.running) "running" else "stopped"
        val mode = if (CaptureService.armed) "ARMED" else "dry run"
        status.text = "Screen: ${m.widthPixels} x ${m.heightPixels}\n" +
            "Tap service: $tap\nCapture: $cap ($mode)\n" +
            "Lead: ${CaptureService.leadMs} ms\n\n${CaptureService.info}"
    }

    override fun onResume() {
        super.onResume()
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }
}
