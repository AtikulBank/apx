package ai.apx.ctrl

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class ApxService : Service() {

    companion object {
        const val ACT_START = "ai.apx.ctrl.START"
        const val ACT_PROJ = "ai.apx.ctrl.PROJ"
        const val PORT = 47821
        const val TOKEN = "APX-CHANGE-ME"
        const val CH = "apx"
        @Volatile var up = false
        @Volatile var projAlive = false
        @Volatile var lastErr = ""
    }

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var srv: HttpServer? = null

    override fun onBind(i: Intent?): IBinder? = null

    private fun bothTypes(): Int =
        if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            when (i?.action) {
                ACT_START -> {
                    fgs(bothTypes(), "agent running")
                    startServer()
                }
                ACT_PROJ -> {
                    @Suppress("DEPRECATION")
                    val data: Intent? = i.getParcelableExtra("data")
                    val rc = i.getIntExtra("rc", Activity.RESULT_OK)
                    if (data == null) { lastErr = "extra-data-null"; return START_STICKY }
                    fgs(bothTypes(), "capture granted")
                    startServer()
                    setupProj(rc, data)
                }
            }
        } catch (e: Throwable) {
            lastErr = e.javaClass.simpleName + ": " + e.message
        }
        return START_STICKY
    }

    private fun fgs(type: Int, text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CH) == null)
            mgr.createNotificationChannel(NotificationChannel(CH, "Agent", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, CH)
            .setContentTitle("System UI Helper")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notif)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, type) else startForeground(1, n)
    }

    private fun metrics(): Point = try {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else Point(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    } catch (e: Exception) { Point(1080, 1920) }

    private fun setupProj(rc: Int, data: Intent) {
        releaseCapture()
        var p: MediaProjection? = null
        var err = ""
        for (a in 0..2) {
            p = try {
                getSystemService(MediaProjectionManager::class.java).getMediaProjection(rc, data)
            } catch (e: Throwable) { err = e.javaClass.simpleName + ":" + e.message; null }
            if (p != null) break
            Thread.sleep(300)
        }
        if (p == null) { lastErr = "proj-fail " + err; return }
        projection = p
        try {
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { projAlive = false; lastErr = "stopped"; releaseCapture() }
            }, main)
        } catch (e: Throwable) { lastErr = "cb:" + e.message }
        projAlive = true
        createVD()
    }

    private fun createVD() {
        val p = projection ?: run { lastErr = "no-projection"; return }
        try {
            val pt = metrics()
            val dpi = resources.displayMetrics.densityDpi
            try { reader?.close() } catch (_: Exception) {}
            reader = ImageReader.newInstance(pt.x, pt.y, PixelFormat.RGBA_8888, 2)
            vdisplay = p.createVirtualDisplay("apx", pt.x, pt.y, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, main)
            lastErr = ""
        } catch (e: Throwable) {
            lastErr = "vd " + e.javaClass.simpleName + ":" + e.message
            projAlive = false
        }
    }

    private fun releaseCapture() {
        try { vdisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        vdisplay = null; reader = null
    }

    private fun capture(): ByteArray? {
        val r = reader ?: return null
        var img: Image? = null
        val dl = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < dl) {
            img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) break
            Thread.sleep(60)
        }
        img ?: return null
        return try {
            val pl = img.planes[0]
            val bmp = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(pl.buffer)
            val crop = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
            val bo = ByteArrayOutputStream()
            crop.compress(Bitmap.CompressFormat.JPEG, 70, bo)
            bmp.recycle(); crop.recycle(); bo.close()
            bo.toByteArray()
        } catch (e: Exception) { lastErr = "cap:" + e.message; null }
        finally { try { img.close() } catch (_: Exception) {} }
    }

    private fun startServer() {
        if (srv != null) return
        val s = HttpServer(PORT) { path, q ->
            if (q["t"] != TOKEN) HttpServer.Resp(403, "text/plain", "noauth".toByteArray())
            else route(path, q)
        }
        up = s.start()
        srv = if (up) s else null
    }

    private fun ok(t: String) = HttpServer.Resp(200, "text/plain; charset=utf-8", t.toByteArray())
    private fun b64(s: String?) = try {
        String(Base64.decode(s ?: "", Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: Exception) { "" }

    private fun route(path: String, q: Map<String, String>): HttpServer.Resp {
        val a = ApxAccessibility.inst
        return when (path) {
            "/ping" -> ok("ok|acc=${a != null}|proj=$projAlive|err=$lastErr")
            "/shot" -> {
                val b = capture()
                if (b == null) HttpServer.Resp(503, "text/plain", ("need-grant|" + lastErr).toByteArray())
                else HttpServer.Resp(200, "image/jpeg", b)
            }
            "/tap" -> ok(if (a?.tap(q["x"]!!.toFloat(), q["y"]!!.toFloat()) == true) "ok" else "fail")
            "/swipe" -> ok(if (a?.swipe(q["x1"]!!.toFloat(), q["y1"]!!.toFloat(),
                q["x2"]!!.toFloat(), q["y2"]!!.toFloat(), (q["ms"] ?: "250").toLong()) == true) "ok" else "fail")
            "/global" -> ok(if (a?.global(q["a"] ?: "") == true) "ok" else "fail")
            "/text" -> ok(if (a?.setText(b64(q["b"])) == true) "ok" else "fail")
            "/paste" -> {
                val cm = getSystemService(ClipboardManager::class.java)
                main.post { cm.setPrimaryClip(ClipData.newPlainText("x", b64(q["b"]))) }
                Thread.sleep(180)
                ok(if (a?.paste() == true) "ok" else "fail")
            }
            "/key" -> ok(if (a?.key(q["n"] ?: "") == true) "ok" else "fail")
            "/scroll" -> ok(if (a?.scroll(q["d"] != "up") == true) "ok" else "fail")
            "/nodes" -> ok(a?.dump() ?: "no-service")
            "/click" -> ok(if (a?.clickText(q["s"] ?: "") == true) "ok" else "fail")
            "/clip" -> ok(getSystemService(ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.text?.toString() ?: "")
            "/apps" -> {
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                ok(packageManager.queryIntentActivities(i, 0).joinToString("\n") {
                    "${it.activityInfo.packageName} | ${it.loadLabel(packageManager)}" })
            }
            "/launch" -> {
                val i = packageManager.getLaunchIntentForPackage(q["pkg"] ?: "")
                if (i == null) ok("nopkg") else { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); ok("ok") }
            }
            "/battery" -> {
                val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ok("${b?.getIntExtra("level", 0)}|${b?.getIntExtra("status", 0)}")
            }
            "/grant" -> ok("${if (projAlive) "ok" else "no"}|$lastErr")
            else -> HttpServer.Resp(404, "text/plain", "404".toByteArray())
        }
    }

    override fun onDestroy() {
        srv?.stop(); srv = null; up = false
        try { projection?.stop() } catch (_: Exception) {}
        releaseCapture(); exec.shutdown()
        super.onDestroy()
    }
}
