package com.mini.audiotx

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Intercepta las teclas de volumen (botones del teléfono o control Bluetooth)
 * solo mientras hay una PC conectada, y las usa para el volumen de la PC.
 */
class KeyService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!AudioService.pcControl) return false
        val c = event.keyCode
        if (c != KeyEvent.KEYCODE_VOLUME_UP && c != KeyEvent.KEYCODE_VOLUME_DOWN && c != KeyEvent.KEYCODE_VOLUME_MUTE) return false
        if (event.action == KeyEvent.ACTION_DOWN) AudioService.instance?.keyVolume(c)
        return true
    }
}
