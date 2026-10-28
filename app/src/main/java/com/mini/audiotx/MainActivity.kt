package com.mini.audiotx

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var btn: Button
    private lateinit var chk: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val info = TextView(this).apply {
            text = "Transmite el audio del teléfono a la PC.\nCon la casilla activa, el teléfono se silencia mientras está conectado y sus botones de volumen controlan el volumen de la PC. Al desconectar, todo vuelve a la normalidad."
            textSize = 16f
        }
        chk = CheckBox(this).apply { text = "Silenciar el teléfono mientras está conectado a la PC"; isChecked = true }
        chk.setOnCheckedChangeListener { _, on -> AudioService.instance?.setMuteMode(on) }
        btn = Button(this)
        val acc = Button(this).apply {
            text = "Activar botones de volumen (Accesibilidad)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        btn.setOnClickListener {
            if (AudioService.running) {
                stopService(Intent(this, AudioService::class.java))
                btn.postDelayed({ refresh() }, 300)
            } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            } else askProjection()
        }
        box.addView(info); box.addView(chk); box.addView(acc); box.addView(btn)
        setContentView(box)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        btn.text = if (AudioService.running) "Detener transmisión" else "Iniciar transmisión"
        AudioService.instance?.let { chk.isChecked = it.isMuteMode }
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (code == 2 && r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) askProjection()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 1 && res == RESULT_OK && data != null) {
            val i = Intent(this, AudioService::class.java).putExtra("code", res).putExtra("data", data).putExtra("mute", chk.isChecked)
            startForegroundService(i)
            btn.postDelayed({ refresh() }, 500)
        }
    }
}
