package com.example.mesengerhubtel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * هر پیام‌رسان در یک WebView مستقل (نسخه‌ی وب رسمی خودش) باز می‌شود
 * و نشست ورود بین اجراها حفظ می‌شود.
 * برای افزودن پیام‌رسان جدید یک خط به فهرست messengers اضافه کنید.
 */
class MainActivity : Activity() {

    private data class Messenger(val name: String, val url: String, val color: Int) {
        val baseDomain: String get() = Uri.parse(url).host.orEmpty().removePrefix("web.").removePrefix("www.")
    }

    // آدرس‌ها را پیش از انتشار بررسی کنید؛ ممکن است تغییر کرده باشند.
    // رنگ‌ها فقط برای تشخیص سریع هر تب هستند (یک نقطه‌ی رنگی کوچک)، نه کپی لوگوی هیچ برندی.
    private val messengers = listOf(
        Messenger("بله", "https://web.bale.ai", Color.parseColor("#2AABEE")),
        Messenger("ایتا", "https://web.eitaa.com", Color.parseColor("#12A4DE")),
        Messenger("روبیکا", "https://web.rubika.ir", Color.parseColor("#FF7A00")),
        Messenger("آیگپ", "https://web.igap.net", Color.parseColor("#00B389")),
        Messenger("گپ", "https://web.gap.im", Color.parseColor("#7C5CFC")),
        Messenger("تلگرام", "https://web.telegram.org/k/", Color.parseColor("#229ED9")),
        Messenger("اینستاگرام", "https://www.instagram.com", Color.parseColor("#C8328C")),
        Messenger("واتس‌اپ", "https://web.whatsapp.com", Color.parseColor("#25D366")),
        Messenger("ایکس", "https://x.com", Color.parseColor("#111111")),
    )

    // WebView این قابلیت‌ها را ندارد؛ با این اسکریپت به کد بومی وصلشان می‌کنیم:
    //  - navigator.share  (دکمه‌ی «اشتراک‌گذاری» سایت‌ها)
    private val shimJs = """
        (function () {
          if (window.__hubShimInstalled) return;
          window.__hubShimInstalled = true;
          function toB64(file) {
            return new Promise(function (resolve, reject) {
              var r = new FileReader();
              r.onload = function () { resolve(String(r.result).split(',')[1] || ''); };
              r.onerror = reject;
              r.readAsDataURL(file);
            });
          }
          navigator.canShare = function (d) { return !!d; };
          navigator.share = async function (data) {
            data = data || {};
            var files = [];
            if (data.files) {
              for (var i = 0; i < data.files.length; i++) {
                var f = data.files[i];
                files.push({ name: f.name, type: f.type, data: await toB64(f) });
              }
            }
            AndroidHub.share(JSON.stringify({
              title: data.title || '', text: data.text || '', url: data.url || '', files: files
            }));
          };
        })();
    """.trimIndent()

    // WebView هر پیام‌رسان فقط در اولین باری که تبش انتخاب می‌شود ساخته می‌شود (بارگذاری تنبل)،
    // نه همه‌شان همزمان موقع باز شدن برنامه — همین باعث سریع‌تر بالا آمدن برنامه و سبک‌تر ماندن آن می‌شود.
    private val webViews = arrayOfNulls<WebView>(messengers.size)
    private val tabChips = mutableListOf<TextView>()
    private lateinit var content: FrameLayout
    private var current = 0

    // رنگ‌های هویت بصری اپ؛ همان طیف گرادیانت آیکن (آبی به بنفش).
    private val accentStart = Color.parseColor("#4F6EF7")
    private val accentEnd = Color.parseColor("#8B4FF7")
    private val tabUnselectedBg = Color.parseColor("#F1F2F7")
    private val tabUnselectedText = Color.parseColor("#3A3B46")

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private val fileRequestCode = 1001
    private val permissionRequestCode = 2001

    // وقتی کاربر از یک پیام‌رسان (یا هر اپ دیگر) روی «اشتراک‌گذاری» بزند و این اپ را انتخاب کند،
    // متن/فایل اینجا نگه داشته می‌شود تا کاربر پیام‌رسان مقصد را انتخاب کند.
    private var pendingShareText: String? = null
    private var pendingShareFileUri: Uri? = null
    private var pendingShareFileType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // دوربین/میکروفون برای تماس و ضبط صدا، و دسترسی به گالری/حافظه برای پیوست و ذخیره‌ی فایل.
        val neededPermissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPermissions += Manifest.permission.READ_MEDIA_IMAGES
            neededPermissions += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            neededPermissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            neededPermissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val missing = neededPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), permissionRequestCode)
        }

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Color.WHITE)
        }

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
            addView(tabBar)
        }
        val tabDivider = View(this).apply { setBackgroundColor(Color.parseColor("#E7E8EF")) }
        val tabBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(3).toFloat()
            addView(tabScroll)
            addView(tabDivider, LinearLayout.LayoutParams(match, dp(1)))
        }
        content = FrameLayout(this)

        messengers.forEachIndexed { i, m ->
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(m.color)
                setSize(dp(9), dp(9))
            }
            val chip = TextView(this).apply {
                text = m.name
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                compoundDrawablePadding = dp(7)
                setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
                setOnClickListener { select(i) }
            }
            tabChips += chip
            tabBar.addView(chip, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = dp(8) })
        }

        root.addView(tabBarContainer, LinearLayout.LayoutParams(match, wrap))
        root.addView(content, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)

        select(0)
        prefetchDns()
        handleIncomingShare(intent)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** پس‌زمینه‌ی تب فعال: گرادیانت آبی به بنفش هم‌رنگ آیکن برنامه؛ تب‌های غیرفعال خاکستری روشن. */
    private fun pillBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat()
        if (selected) {
            orientation = GradientDrawable.Orientation.TL_BR
            colors = intArrayOf(accentStart, accentEnd)
        } else {
            setColor(tabUnselectedBg)
        }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = pillBackground(selected)
        chip.setTextColor(if (selected) Color.WHITE else tabUnselectedText)
        chip.typeface = Typeface.create(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    // اتصال به هر پیام‌رسان با یک جست‌وجوی DNS شروع می‌شود؛ این کار را برای همه از قبل و در پس‌زمینه
    // انجام می‌دهیم تا وقتی کاربر تب را باز کرد، نتیجه از حافظه‌ی سیستم خوانده شود، نه از صفر.
    private fun prefetchDns() {
        messengers.forEach { m ->
            Thread {
                try {
                    java.net.InetAddress.getAllByName(m.baseDomain)
                    // یک دست‌دهی واقعی TLS هم انجام می‌دهیم (و بلافاصله می‌بندیم)، چون خود این
                    // فرایند (نه فقط DNS) معمولاً بیشترین تأخیر اولین اتصال را تشکیل می‌دهد.
                    (java.net.URL("https://" + m.baseDomain).openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                        connectTimeout = 4000
                        requestMethod = "HEAD"
                        connect()
                        disconnect()
                    }
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    /** وقتی کاربر از جای دیگری (یا از خود یک پیام‌رسان) چیزی را با این اپ به‌اشتراک بگذارد. */
    private fun handleIncomingShare(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        if (text.isNullOrBlank() && stream == null) return

        pendingShareText = text
        pendingShareFileUri = stream
        pendingShareFileType = intent.type

        val names = messengers.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ارسال به کدام پیام‌رسان؟")
            .setItems(names) { _, which -> forwardPendingShareTo(which) }
            .setNegativeButton("انصراف") { d, _ ->
                pendingShareText = null; pendingShareFileUri = null; d.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    /** تب مقصد را باز می‌کند، متن را در کلیپ‌بورد می‌گذارد و تلاش می‌کند خودکار در کادر پیام بنویسد. */
    private fun forwardPendingShareTo(index: Int) {
        select(index)
        val text = pendingShareText
        val fileUri = pendingShareFileUri

        if (!text.isNullOrBlank()) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("پیام", text))
            val target = webViews[index]
            if (target != null) {
                target.postDelayed({ tryInjectComposerText(target, text) }, 900)
            }
            showToast("متن کپی شد؛ اگر خودکار درج نشد، در کادر پیام Paste کنید")
        }

        if (fileUri != null) {
            showToast("فایل در Downloads/MesengerHubTel ذخیره شد؛ از دکمه‌ی پیوست همین‌جا انتخابش کنید")
            Thread {
                try {
                    val type = pendingShareFileType ?: "application/octet-stream"
                    val bytes = contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                    if (bytes != null) {
                        val name = fixFileName(fileUri.lastPathSegment, type)
                        saveBytes(bytes, type, name)
                    }
                } catch (_: Exception) {
                    showToast("ذخیره‌ی فایل برای ارسال ناموفق بود")
                }
            }.start()
        }

        pendingShareText = null
        pendingShareFileUri = null
        pendingShareFileType = null
    }

    /** تلاش خودکار (best-effort) برای پیدا کردن کادر نوشتن پیام و درج متن در آن؛ اگر نشد، کلیپ‌بورد پشتیبان است. */
    private fun tryInjectComposerText(webView: WebView, text: String) {
        val js = """
            (function () {
              function setNativeValue(el, value) {
                var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                if (desc && desc.set) { desc.set.call(el, value); } else { el.value = value; }
              }
              var els = Array.prototype.slice.call(
                document.querySelectorAll('textarea, [contenteditable="true"], div[role="textbox"]')
              ).filter(function (el) {
                var r = el.getBoundingClientRect();
                return r.width > 50 && r.height > 10 && el.offsetParent !== null;
              });
              if (els.length === 0) return false;
              var el = els[els.length - 1];
              el.focus();
              if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
                setNativeValue(el, ${JSONObject.quote(text)});
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
              } else {
                el.innerText = ${JSONObject.quote(text)};
                el.dispatchEvent(new InputEvent('input', { bubbles: true }));
              }
              return true;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(m: Messenger): WebView {
        val webView = WebView(this)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.visibility = View.GONE
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = false
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        // این دو باعث می‌شود صفحه از همان ابتدا با اندازه‌ی درست رندر شود، نه اینکه اول با زوم اشتباه
        // بارگذاری شود و بعد خودش را اصلاح کند — که در WebView حس کندی ایجاد می‌کند.
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        @Suppress("DEPRECATION")
        webView.settings.setRenderPriority(WebSettings.RenderPriority.HIGH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // بررسی Safe Browsing گوگل روی اولین بارگذاری هر سایت چند ثانیه تأخیر ایجاد می‌کند؛
            // چون آدرس‌های پیام‌رسان‌ها ثابت و شناخته‌شده‌اند، این بررسی را خاموش می‌کنیم.
            webView.settings.safeBrowsingEnabled = false
        }
        val ua = webView.settings.userAgentString
        if (ua != null) webView.settings.userAgentString = ua.replace("; wv", "")

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(HubBridge(), "AndroidHub")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view.evaluateJavascript(shimJs, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(shimJs, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                val internal = host == m.baseDomain || host.endsWith("." + m.baseDomain)
                if (internal) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean {
                val popup = WebView(this@MainActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            } catch (_: ActivityNotFoundException) {
                            }
                            return true
                        }
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback

                val captureIntents = mutableListOf<Intent>()
                try {
                    val photoFile = File.createTempFile(
                        "capture_", ".jpg", getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    )
                    val photoUri = FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.fileprovider", photoFile
                    )
                    cameraPhotoUri = photoUri
                    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    if (cameraIntent.resolveActivity(packageManager) != null) captureIntents += cameraIntent
                } catch (_: Exception) {
                }

                val contentIntent = fileChooserParams.createIntent()
                val chooser = Intent.createChooser(contentIntent, "انتخاب فایل").apply {
                    if (captureIntents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, captureIntents.toTypedArray())
                    }
                }
                return try {
                    startActivityForResult(chooser, fileRequestCode)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }

        // دانلود/ذخیره: سه نوع لینک داریم — blob: (رایج در پیام‌رسان‌ها)، data: و http(s).
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val hintedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            when {
                url.startsWith("blob:") -> {
                    // blob فقط داخل همان صفحه قابل خواندن است؛ پس با جاوااسکریپت می‌خوانیم و به کد بومی می‌دهیم.
                    val js = "(function(){fetch(" + JSONObject.quote(url) + ")" +
                        ".then(function(r){return r.blob();})" +
                        ".then(function(b){var fr=new FileReader();" +
                        "fr.onloadend=function(){AndroidHub.saveBase64(String(fr.result).split(',')[1]||''," +
                        "b.type||" + JSONObject.quote(mimeType ?: "") + "," + JSONObject.quote(hintedName) + ");};" +
                        "fr.readAsDataURL(b);})" +
                        ".catch(function(){AndroidHub.toast('ذخیره‌ی فایل ناموفق بود');});})();"
                    webView.evaluateJavascript(js, null)
                }
                url.startsWith("data:") -> {
                    Thread {
                        try {
                            val header = url.substringBefore(',').removePrefix("data:")
                            val payload = url.substringAfter(',')
                            val mime = header.substringBefore(';').ifBlank { "application/octet-stream" }
                            val bytes = if (header.contains("base64")) {
                                Base64.decode(payload, Base64.DEFAULT)
                            } else {
                                Uri.decode(payload).toByteArray()
                            }
                            val name = fixFileName(hintedName, mime)
                            saveBytes(bytes, mime, name)
                            showToast("ذخیره شد: $name")
                        } catch (_: Exception) {
                            showToast("ذخیره‌ی فایل ناموفق بود")
                        }
                    }.start()
                }
                else -> {
                    try {
                        val request = DownloadManager.Request(Uri.parse(url)).apply {
                            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                            if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                            setTitle(hintedName)
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, hintedName)
                        }
                        (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                        showToast("دانلود شروع شد: $hintedName")
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: ActivityNotFoundException) {
                            showToast("دانلود ناموفق بود")
                        }
                    }
                }
            }
        }
        return webView
    }

    /** پل بین جاوااسکریپت صفحه و کد بومی (ذخیره‌ی فایل و اشتراک‌گذاری). */
    private inner class HubBridge {

        @JavascriptInterface
        fun toast(message: String) = showToast(message)

        @JavascriptInterface
        fun saveBase64(base64: String, mime: String, name: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val finalName = fixFileName(name, mime)
                saveBytes(bytes, mime, finalName)
                showToast("ذخیره شد: $finalName")
            } catch (_: Exception) {
                showToast("ذخیره‌ی فایل ناموفق بود")
            }
        }

        @JavascriptInterface
        fun share(json: String) {
            try {
                val obj = JSONObject(json)
                val title = obj.optString("title")
                val text = obj.optString("text")
                val url = obj.optString("url")
                val files = obj.optJSONArray("files")

                val uris = ArrayList<Uri>()
                var mime: String? = null
                if (files != null && files.length() > 0) {
                    val dir = File(cacheDir, "shared").apply { mkdirs() }
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        val type = f.optString("type").ifBlank { "application/octet-stream" }
                        val out = File(dir, fixFileName(f.optString("name"), type))
                        out.writeBytes(Base64.decode(f.optString("data"), Base64.DEFAULT))
                        uris += FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", out)
                        mime = if (mime == null || mime == type) type else "*/*"
                    }
                }

                val message = listOf(text, url).filter { it.isNotBlank() }.joinToString("\n")
                val intent = if (uris.size > 1) {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = mime ?: "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    }
                } else {
                    Intent(Intent.ACTION_SEND).apply {
                        if (uris.size == 1) {
                            type = mime ?: "*/*"
                            putExtra(Intent.EXTRA_STREAM, uris[0])
                        } else {
                            type = "text/plain"
                        }
                    }
                }
                if (message.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, message)
                if (title.isNotBlank()) intent.putExtra(Intent.EXTRA_SUBJECT, title)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                runOnUiThread { startActivity(Intent.createChooser(intent, "اشتراک‌گذاری")) }
            } catch (_: Exception) {
                showToast("اشتراک‌گذاری ناموفق بود")
            }
        }
    }

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    /** نام فایل امن + پسوند درست بر اساس نوع فایل (اگر نام پسوند نداشته باشد یا .bin باشد). */
    private fun fixFileName(name: String?, mime: String?): String {
        var n = (name ?: "").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (n.isEmpty()) n = "file_" + System.currentTimeMillis()
        val ext = mime?.substringBefore(';')
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val current = n.substringAfterLast('.', "")
        val hasGoodExt = current.isNotEmpty() && current.length <= 5 && current != "bin"
        if (!hasGoodExt) {
            n = n.removeSuffix(".bin")
            if (ext != null) n += ".$ext"
        }
        return n
    }

    /** عکس → Pictures، ویدیو → Movies (تا در گالری دیده شوند)، بقیه → Downloads. */
    private fun saveBytes(bytes: ByteArray, mime: String, name: String) {
        val type = mime.substringBefore(';').ifBlank { "application/octet-stream" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection: Uri
            val folder: String
            when {
                type.startsWith("image/") -> {
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    folder = Environment.DIRECTORY_PICTURES + "/MesengerHubTel"
                }
                type.startsWith("video/") -> {
                    collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    folder = Environment.DIRECTORY_MOVIES + "/MesengerHubTel"
                }
                else -> {
                    collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    folder = Environment.DIRECTORY_DOWNLOADS + "/MesengerHubTel"
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(collection, values)
                ?: throw IllegalStateException("insert failed")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("no output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "MesengerHubTel"
            ).apply { mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { it.write(bytes) }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(type), null)
        }
    }

    private fun select(index: Int) {
        current = index
        if (webViews[index] == null) {
            val match = ViewGroup.LayoutParams.MATCH_PARENT
            val webView = createWebView(messengers[index])
            webViews[index] = webView
            content.addView(webView, FrameLayout.LayoutParams(match, match))
            webView.loadUrl(messengers[index].url)
        }
        webViews.forEachIndexed { i, wv -> wv?.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabChips.forEachIndexed { i, chip -> styleChip(chip, i == index) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == fileRequestCode) {
            var result: Array<Uri>? = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            val photo = cameraPhotoUri
            if (result == null && resultCode == RESULT_OK && photo != null) {
                result = arrayOf(photo)
            }
            fileCallback?.onReceiveValue(result)
            fileCallback = null
            cameraPhotoUri = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webViews[current]
        if (wv != null && wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webViews.forEach { it?.destroy() }
        super.onDestroy()
    }
}
