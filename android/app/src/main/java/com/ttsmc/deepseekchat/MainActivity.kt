package com.ttsmc.deepseekchat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DeepSeek Chat —— 原生壳。
 *
 * 职责：
 *  1. 用 WebView 承载 web/index.html 的全部界面（assets 目录，完全离线）。
 *  2. 用 OkHttp 直连 api.deepseek.com 完成 SSE 流式请求，
 *     通过 @JavascriptInterface 把 token 增量回推给 JS。
 *     —— 这样既绕开了 file:// 源的 CORS 限制，也让 API Key 只存在于原生侧。
 *
 * JS <-> Kotlin 协议（JS 侧见 web/index.html 的 Bridge / window.__bridge）：
 *   JS  -> Kotlin : startStream(reqId, baseUrl, apiKey, payloadJson)
 *                   abort(reqId)
 *                   openUrl(url)
 *                   requestImagePermission() / pickImage(reqId) / listImages(dir)
 *                   loadImage(reqId, absolutePath)
 *   Kotlin -> JS  : window.__bridge.onOpen(reqId)
 *                   window.__bridge.onReason(reqId, text)
 *                   window.__bridge.onDelta(reqId, text)
 *                   window.__bridge.onDone(reqId)
 *                   window.__bridge.onError(reqId, message)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private val calls = ConcurrentHashMap<String, Call>()
    private val streams = ConcurrentHashMap<String, Streamer>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)      // SSE 长连接不设读超时
            .retryOnConnectionFailure(true)
            .build()
    }

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 相册选图：等待结果的 JS 回调 id */
    private var pendingPickReq: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0F1115"))
        setContentView(web, ViewGroup.LayoutParams(-1, -1))

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString DeepSeekChat/1.0"
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "console: ${m.message()} @${m.lineNumber()}")
                return true
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val scheme = u.scheme ?: return true
                if (scheme != "http" && scheme != "https") return true
                val host = u.host ?: return true
                // 站内（本地资源 / DeepSeek 官方域名）放行，其余交给系统浏览器
                if (host.endsWith("deepseek.com") || host == "localhost") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                    true
                } catch (e: Exception) {
                    true
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "DeepSeekBridge")
        web.loadUrl("file:///android_asset/index.html")

        // 返回键：先关弹窗/抽屉，再回退网页历史，最后退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript(BACK_JS) { result ->
                    val handled = result != null && result.contains("handled")
                    if (!handled) {
                        if (web.canGoBack()) web.goBack() else finish()
                    }
                }
            }
        })
    }

    override fun onPause() { web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }

    override fun onDestroy() {
        streams.values.forEach { it.complete("已取消") }
        calls.values.forEach { it.cancel() }
        calls.clear()
        super.onDestroy()
    }

    /* ==================== JS 桥 ==================== */
    inner class Bridge {

        @JavascriptInterface
        fun startStream(reqId: String, baseUrl: String, apiKey: String, payload: String) {
            if (streams.containsKey(reqId)) { emit(reqId, "onError", "重复的请求 ID"); return }
            val st = Streamer(reqId)
            streams[reqId] = st
            st.start()

            val streamMode = try { JSONObject(payload).optBoolean("stream", true) } catch (e: Exception) { true }
            val url = baseUrl.trim().trimEnd('/') + "/chat/completions"

            val reqBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey".replace("\n", "").replace("\r", ""))
                .header("Accept", if (streamMode) "text/event-stream" else "application/json")
                .post(payload.toRequestBody(JSON_TYPE))

            val call = client.newCall(reqBuilder.build())
            calls[reqId] = call
            call.enqueue(object : Callback {
                override fun onFailure(c: Call, e: IOException) {
                    calls.remove(reqId)
                    st.complete(if (c.isCanceled()) "已取消" else (e.message ?: "网络连接失败"))
                }

                override fun onResponse(c: Call, response: Response) {
                    calls.remove(reqId)
                    response.use { r ->
                        if (!r.isSuccessful) {
                            val detail = try { r.body?.string().orEmpty() } catch (e: Exception) { "" }
                            st.complete("HTTP ${r.code}：${detail.take(500)}")
                            return
                        }
                        try {
                            if (streamMode) readSse(r, st) else readOnce(r, st)
                            st.complete(null)
                        } catch (e: Exception) {
                            st.complete(e.message ?: "响应解析失败")
                        }
                    }
                }
            })
        }

        @JavascriptInterface
        fun abort(reqId: String) {
            calls.remove(reqId)?.cancel()
            streams.remove(reqId)?.complete(null)   // 让 pump 自行收尾，不报错
        }

        /* ---------- 图片：权限 / 相册 / 文件夹 / 解码 ---------- */

        @JavascriptInterface
        fun requestImagePermission() {
            if (hasImagePermission()) return
            main.post {
                try { requestPermissions(neededImagePermissions(), REQ_IMG_PERM) }
                catch (e: Exception) { Log.w(TAG, "requestPermissions failed", e) }
            }
        }

        @JavascriptInterface
        fun pickImage(reqId: String) {
            pendingPickReq = reqId
            main.post { launchImagePicker() }
        }

        /** 同步返回是否已拿到读图权限，供 JS 决定要不要先弹授权框 */
        @JavascriptInterface
        fun canReadImages(): Boolean = hasImagePermission()

        /** 同步返回目录里的图片绝对路径（JSON 数组字符串） */
        @JavascriptInterface
        fun listImages(dir: String): String {
            val arr = JSONArray()
            if (!hasImagePermission()) return arr.toString()
            try {
                val d = File(dir)
                if (!d.isDirectory) return arr.toString()
                val ok = arrayOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif", ".jfif")
                d.listFiles()
                    ?.filter { it.isFile && ok.any { e -> it.name.lowercase().endsWith(e) } }
                    ?.sortedBy { it.name.lowercase() }
                    ?.forEach { arr.put(it.absolutePath) }
            } catch (e: Exception) {
                Log.w(TAG, "listImages failed: $dir", e)
            }
            return arr.toString()
        }

        /** 读取指定路径的图片，压到最长边 1440 / JPEG q82，回推 dataURL */
        @JavascriptInterface
        fun loadImage(reqId: String, path: String) {
            Thread {
                val data = try { decodeToDataUrl(path, null) } catch (e: Exception) {
                    Log.w(TAG, "loadImage failed: $path", e); null
                }
                emitImage(reqId, data)
            }.start()
        }

        /** 目录预览用的小缩略图（最长边 320、q70），避免 WebView 下 file:// 缩略图被 CORS 拦。 */
        @JavascriptInterface
        fun thumb(reqId: String, path: String) {
            Thread {
                val data = try { decodeToDataUrl(path, null, MAX_THUMB_EDGE, 70) } catch (e: Exception) {
                    Log.w(TAG, "thumb failed: $path", e); null
                }
                emitImage(reqId, data)
            }.start()
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                web.post {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: Exception) {
                Log.w(TAG, "openUrl failed: $url", e)
            }
        }
    }

    /* ==================== SSE 解析 ==================== */
    private fun readSse(r: Response, st: Streamer) {
        val src = r.body?.source() ?: throw IOException("响应体为空")
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (line.isEmpty() || line[0] == ':') continue
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data == "[DONE]") return

            val o = try { JSONObject(data) } catch (e: Exception) { continue }
            if (o.has("error") && !o.isNull("error")) {
                val msg = o.optJSONObject("error")?.optString("message").orEmpty()
                throw IOException(if (msg.isNotEmpty()) msg else data)
            }
            val c0 = o.optJSONArray("choices")?.optJSONObject(0) ?: continue
            val d = c0.optJSONObject("delta")
            if (d != null) {
                if (d.has("reasoning_content") && !d.isNull("reasoning_content")) {
                    st.appendReason(d.getString("reasoning_content"))
                }
                if (d.has("content") && !d.isNull("content")) {
                    st.appendDelta(d.getString("content"))
                }
            }
            if (c0.has("finish_reason") && !c0.isNull("finish_reason")) return
        }
    }

    private fun readOnce(r: Response, st: Streamer) {
        val body = r.body?.string() ?: return
        val m = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return
        if (m.has("reasoning_content") && !m.isNull("reasoning_content")) st.appendReason(m.getString("reasoning_content"))
        if (m.has("content") && !m.isNull("content")) st.appendDelta(m.getString("content"))
    }

    /* ==================== 增量回推（45ms 合批） ==================== */
    private class Snapshot(val content: String?, val reason: String?, val done: Boolean, val error: String?)

    private inner class Streamer(private val reqId: String) {
        private val lock = Any()
        private val sbContent = StringBuilder()
        private val sbReason = StringBuilder()
        private var sentContent = 0
        private var sentReason = 0
        @Volatile private var done = false
        @Volatile private var error: String? = null
        private var opened = false

        fun appendDelta(t: String) { synchronized(lock) { sbContent.append(t) } }
        fun appendReason(t: String) { synchronized(lock) { sbReason.append(t) } }

        /** err == null 表示正常结束 */
        fun complete(err: String?) {
            synchronized(lock) { error = err; done = true }
        }

        fun start() { main.post(pump) }

        private val pump = object : Runnable {
            override fun run() {
                val snap = synchronized(lock) {
                    val c = if (sbContent.length > sentContent) {
                        val s = sbContent.substring(sentContent); sentContent = sbContent.length; s
                    } else null
                    val r = if (sbReason.length > sentReason) {
                        val s = sbReason.substring(sentReason); sentReason = sbReason.length; s
                    } else null
                    Snapshot(c, r, done, error)
                }
                if (snap.content != null || snap.reason != null) ensureOpen()
                snap.reason?.let { emit(reqId, "onReason", it) }
                snap.content?.let { emit(reqId, "onDelta", it) }

                if (snap.done) {
                    ensureOpen()
                    if (snap.error != null && snap.error != "已取消") emit(reqId, "onError", snap.error)
                    else emit(reqId, "onDone", null)
                    streams.remove(reqId)
                } else {
                    main.postDelayed(this, 45)
                }
            }
        }

        private fun ensureOpen() {
            if (!opened) { opened = true; emit(reqId, "onOpen", null) }
        }
    }

    private fun emit(reqId: String, fn: String, arg: String?) {
        val a = if (arg == null) "" else ", " + JSONObject.quote(arg)
        val code = "window.__bridge&&window.__bridge.$fn(${JSONObject.quote(reqId)}$a);"
        web.post { try { web.evaluateJavascript(code, null) } catch (e: Exception) { Log.w(TAG, "eval failed", e) } }
    }

    /* ==================== 图片处理 ==================== */

    private fun neededImagePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasImagePermission(): Boolean = neededImagePermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun launchImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_IMAGE)
        } catch (e: Exception) {
            Log.w(TAG, "no picker activity", e)
            val id = pendingPickReq; pendingPickReq = null
            emitImage(id, null)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_IMAGE) return
        val id = pendingPickReq
        pendingPickReq = null
        if (id == null) return
        val uri: Uri? = data?.data
        if (resultCode != RESULT_OK || uri == null) { emitImage(id, null); return }
        Thread {
            val d = try { decodeToDataUrl(null, uri) } catch (e: Exception) {
                Log.w(TAG, "decode picked image failed", e); null
            }
            emitImage(id, d)
        }.start()
    }

    /**
     * 解码 -> 按 EXIF 摆正 -> 最长边缩到 1440 -> JPEG(q82) -> base64 dataURL。
     * 结果直接存进 JS 的 localStorage，所以体积要压到几十 KB 量级。
     */
    private fun decodeToDataUrl(
        path: String?,
        uri: Uri?,
        maxEdge: Int = MAX_IMAGE_EDGE,
        quality: Int = 82
    ): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            if (uri != null) contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            else BitmapFactory.decodeFile(path, bounds)
        } catch (e: Exception) {
            Log.w(TAG, "decode bounds failed", e); return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= 1600) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded: Bitmap = try {
            if (uri != null) contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            else BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            null
        } ?: return null

        val uprightBmp = upright(decoded, path, uri)

        val long2 = maxOf(uprightBmp.width, uprightBmp.height)
        val finalBmp: Bitmap
        if (long2 > maxEdge) {
            val sc = maxEdge.toFloat() / long2
            finalBmp = Bitmap.createScaledBitmap(
                uprightBmp,
                (uprightBmp.width * sc).toInt().coerceAtLeast(1),
                (uprightBmp.height * sc).toInt().coerceAtLeast(1),
                true
            )
            if (finalBmp !== uprightBmp) uprightBmp.recycle()
        } else {
            finalBmp = uprightBmp
        }

        val out = ByteArrayOutputStream()
        finalBmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        finalBmp.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 手机拍的竖图 EXIF 里有旋转信息，BitmapFactory 不会自动摆正 */
    private fun upright(src: Bitmap, path: String?, uri: Uri?): Bitmap {
        val deg = try {
            val ei = if (uri != null) {
                contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            } else {
                path?.let { ExifInterface(it) }
            }
            when (ei?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) { 0 }
        if (deg == 0) return src
        return try {
            val m = Matrix().apply { postRotate(deg.toFloat()) }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                .also { if (it !== src) src.recycle() }
        } catch (e: Exception) { src }
    }

    private fun emitImage(reqId: String?, dataUrl: String?) {
        if (reqId.isNullOrEmpty()) return
        val a = if (dataUrl == null) "null" else JSONObject.quote(dataUrl)
        val code = "window.__bridge&&window.__bridge.onImage(${JSONObject.quote(reqId)},$a);"
        web.post { try { web.evaluateJavascript(code, null) } catch (e: Exception) { Log.w(TAG, "emitImage failed", e) } }
    }

    companion object {
        private const val TAG = "DeepSeekChat"
        private const val REQ_IMG_PERM = 1001
        private const val REQ_PICK_IMAGE = 1002
        private const val MAX_IMAGE_EDGE = 1440
        private const val MAX_THUMB_EDGE = 320

        private const val BACK_JS =
            "(function(){try{" +
            "var m=document.getElementById('settingsModal');" +
            "if(m&&m.classList.contains('open')){document.getElementById('btnCloseSettings').click();return 'handled';}" +
            "var p=document.getElementById('pickerModal');" +
            "if(p&&p.classList.contains('open')){document.getElementById('btnClosePicker').click();return 'handled';}" +
            "var d=document.getElementById('drawer');" +
            "if(d&&d.classList.contains('open')){document.getElementById('scrim').click();return 'handled';}" +
            "}catch(e){}return 'none';})()"
    }
}
