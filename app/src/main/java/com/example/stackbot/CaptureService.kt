package com.example.stackbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import java.nio.ByteBuffer

class CaptureService : Service() {

    companion object {
        const val ACTION_STOP = "stackbot.STOP"
        const val ACTION_ARM = "stackbot.ARM"
        const val ACTION_SHOT = "stackbot.SHOT"
        private const val CHANNEL = "stackbot"
        private const val NOTIF_ID = 1

        @Volatile var running = false
        @Volatile var armed = false
        @Volatile var leadMs = 70
        @Volatile var info = "idle"
        @Volatile var wantShot = false
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val hist = ArrayList<LongArray>()   // each entry: [time ms, left edge px]
    private var cooldownUntil = 0L
    private var taps = 0
    private var frames = 0
    private var fps = 0
    private var fpsT0 = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL, "StackBot", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_ARM -> { armed = !armed; updateNotification(); return START_NOT_STICKY }
            ACTION_SHOT -> { wantShot = true; return START_NOT_STICKY }
        }
        if (running) return START_NOT_STICKY

        startForeground(
            NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startCapture(code, data)
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, mainHandler)

        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = rd
        display = proj.createVirtualDisplay(
            "stackbot", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null
        )

        val t = HandlerThread("vision")
        t.start()
        thread = t
        rd.setOnImageAvailableListener({ r -> onImage(r) }, Handler(t.looper))

        armed = false
        running = true
        info = "capture started ${w}x$h"
    }

    private fun onImage(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            val plane = image.planes[0]
            val buf = plane.buffer
            val stride = plane.rowStride
            val w = image.width
            val h = image.height
            val blocks = StackVision.detect(buf, stride, w, h)
            if (wantShot) {
                wantShot = false
                saveShot(buf, stride, w, h, blocks)
            }
            step(blocks, now, w, h)
        } finally {
            image.close()
        }
    }

    private fun step(blocks: List<StackVision.Block>, now: Long, w: Int, h: Int) {
        frames++
        if (now - fpsT0 >= 1000) {
            fps = frames
            frames = 0
            fpsT0 = now
            updateNotification()
        }

        if (blocks.size < 2) {
            hist.clear()
            info = "blocks seen: ${blocks.size} (need 2)\nfps=$fps"
            return
        }
        if (now < cooldownUntil) {
            hist.clear()
            return
        }

        val mv = blocks[0]
        val base = blocks[1]

        // a sudden jump means a new block appeared, so start tracking again
        if (hist.isNotEmpty() && Math.abs(mv.l - hist.last()[1].toInt()) > 90) hist.clear()
        hist.add(longArrayOf(now, mv.l.toLong()))
        while (hist.size > 6) hist.removeAt(0)
        if (hist.size < 3) return

        val first = hist.first()
        val last = hist.last()
        val dt = (last[0] - first[0]).toFloat()
        if (dt < 40f) return

        val v = (last[1] - first[1]).toFloat() / dt          // px per ms
        val dx = (base.l - mv.l).toFloat()
        val eta = if (Math.abs(v) < 0.03f) Float.MAX_VALUE else dx / v   // ms until aligned

        val etaTxt = if (eta == Float.MAX_VALUE) "-" else eta.toInt().toString()
        val tapWarn = if (TapService.instance == null) "\nTAP SERVICE OFF" else ""
        info = "moving L=${mv.l}  base L=${base.l}\n" +
            "speed=${"%.2f".format(v)} px/ms  eta=$etaTxt ms\n" +
            "fps=$fps  taps=$taps$tapWarn"

        if (!armed || eta < 0f) return

        val wait = eta - leadMs
        if (wait <= 40f) {
            val delay = Math.max(0f, wait).toLong()
            cooldownUntil = now + delay + 700
            hist.clear()
            mainHandler.postDelayed({
                TapService.instance?.tapAt(w / 2f, h * 0.8f)
                taps++
            }, delay)
        }
    }

    private fun saveShot(
        buf: ByteBuffer, stride: Int, w: Int, h: Int, blocks: List<StackVision.Block>
    ) {
        try {
            val bytes = ByteArray(stride * h)
            buf.position(0)
            buf.get(bytes, 0, Math.min(bytes.size, buf.remaining()))
            val bmp = Bitmap.createBitmap(stride / 4, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))

            val c = Canvas(bmp)
            val p = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f }
            blocks.forEachIndexed { i, b ->
                p.color = if (i == 0) Color.RED else Color.GREEN
                c.drawRect(b.l.toFloat(), b.y0.toFloat(), b.r.toFloat(), b.y1.toFloat(), p)
            }

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "stackbot_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StackBot")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            info = "debug shot saved in Pictures/StackBot"
        } catch (e: Exception) {
            info = "shot failed: ${e.message}"
        }
    }

    private fun action(act: String, label: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code,
            Intent(this, CaptureService::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_play), label, pi
        ).build()
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (armed) "StackBot: ARMED" else "StackBot: dry run")
            .setContentText(info.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(info))
            .setOnlyAlertOnce(true)
            .addAction(action(ACTION_ARM, if (armed) "Disarm" else "Arm", 1))
            .addAction(action(ACTION_SHOT, "Debug shot", 2))
            .addAction(action(ACTION_STOP, "Stop", 3))
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        running = false
        armed = false
        info = "stopped"
        mainHandler.removeCallbacksAndMessages(null)
        try { reader?.setOnImageAvailableListener(null, null) } catch (e: Exception) {}
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        super.onDestroy()
    }
}
