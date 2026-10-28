package com.mini.audiotx

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Protocolo: [tipo:1][largo:2 big-endian][datos]
 *   tipo 0 = audio PCM 16 bits, 48000 Hz, estéreo
 *   tipo 1 = volumen (1 byte, 0..100)
 */
class AudioService : Service() {
    companion object {
        @Volatile var running = false
        const val PORT = 7000
    }

    private var projection: MediaProjection? = null
    private var server: ServerSocket? = null
    private var obsThread: HandlerThread? = null
    private var observer: ContentObserver? = null
    private val lock = Any()
    private var out: OutputStream? = null
    private var client: Socket? = null
    @Volatile private var lastVol = -1
    @Volatile private var stopping = false
    private lateinit var am: AudioManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running || intent == null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("audio", "Audio", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "audio")
            .setContentTitle("Transmitiendo audio a la PC")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        am = getSystemService(AudioManager::class.java)
        running = true
        startVolumeWatcher()
        Thread { loop() }.start()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf() // al quitar la app de recientes, se detiene todo
        super.onTaskRemoved(rootIntent)
    }

    private fun fail(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
        stopSelf()
    }

    private fun loop() {
        var rec: AudioRecord? = null
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(3840 * 8)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                fail("El teléfono bloquea la captura de audio (AudioRecord no se inició)")
                return
            }
            val buf = ByteArray(3840) // 20 ms
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT)) // solo accesible por adb forward
            server = ss
            while (!stopping) {
                val c = try { ss.accept() } catch (e: Exception) { break }
                c.tcpNoDelay = true
                synchronized(lock) { client = c; out = c.getOutputStream() }
                lastVol = -1
                sendVolume()
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    fail("No se pudo iniciar la captura de audio")
                    return
                }
                while (!stopping) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) { fail("Error de captura de audio (código $n)"); return }
                    if (n == 0) continue
                    if (!send(0, buf, n)) break
                }
                try { rec.stop() } catch (e: Exception) {}
                closeClient()
            }
        } catch (e: Exception) {
            if (!stopping) fail("Error: ${e.message}")
        } finally {
            rec?.release()
        }
    }

    private fun send(type: Int, d: ByteArray, len: Int): Boolean {
        synchronized(lock) {
            val o = out ?: return false
            return try {
                val p = ByteArray(3 + len)
                p[0] = type.toByte(); p[1] = (len shr 8).toByte(); p[2] = len.toByte()
                System.arraycopy(d, 0, p, 3, len)
                o.write(p); o.flush()
                true
            } catch (e: Exception) { false }
        }
    }

    private fun sendVolume() {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        if (v == lastVol) return
        lastVol = v
        send(1, byteArrayOf(v.toByte()), 1)
    }

    private fun startVolumeWatcher() {
        val ht = HandlerThread("vol").also { it.start() }
        obsThread = ht
        val o = object : ContentObserver(Handler(ht.looper)) {
            override fun onChange(selfChange: Boolean) { sendVolume() }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, o)
        observer = o
    }

    private fun closeClient() {
        synchronized(lock) {
            try { client?.close() } catch (e: Exception) {}
            client = null; out = null
        }
    }

    override fun onDestroy() {
        stopping = true
        running = false
        observer?.let { contentResolver.unregisterContentObserver(it) }
        obsThread?.quitSafely()
        try { server?.close() } catch (e: Exception) {}
        closeClient()
        projection?.stop()
        super.onDestroy()
    }
}
