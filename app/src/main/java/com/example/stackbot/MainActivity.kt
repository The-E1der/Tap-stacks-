package com.example.stackbot

import android.app.Activity
import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 16f }

        val btnAccess = Button(this).apply {
            text = "1. Open accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnTest = Button(this).apply {
            text = "2. Tap test (center of screen in 5 s)"
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Switch to another app now",
                    Toast.LENGTH_LONG
                ).show()
                Handler(Looper.getMainLooper()).postDelayed({
                    val svc = TapService.instance
                    if (svc == null) {
                        Toast.makeText(this@MainActivity, "Tap service is OFF", Toast.LENGTH_LONG).show()
                    } else {
                        val m = resources.displayMetrics
                        svc.tapAt(m.widthPixels / 2f, m.heightPixels / 2f)
                    }
                }, 5000)
            }
        }

        layout.addView(status)
        layout.addView(btnAccess)
        layout.addView(btnTest)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        val m = resources.displayMetrics
        val svcState = if (TapService.instance != null) "ON" else "OFF"
        status.text = "Screen: ${m.widthPixels} x ${m.heightPixels}\nTap service: $svcState"
    }
}
