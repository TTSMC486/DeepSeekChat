package com.ttsmc.deepseekchat

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
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
                Log.d(TAG, "console: ${m.message()} @${m.lineNumber}")
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

    companion object {
        private const val TAG = "DeepSeekChat"
        private const val BACK_JS =
            "(function(){try{" +
            "var m=document.getElementById('settingsModal');" +
            "if(m&&m.classList.contains('open')){document.getElementById('btnCloseSettings').click();return 'handled';}" +
            "var d=document.getElementById('drawer');" +
            "if(d&&d.classList.contains('open')){document.getElementById('scrim').click();return 'handled';}" +
            "}catch(e){}return 'none';})()"
    }
}
