package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray

class MainActivity : ComponentActivity(), SensorEventListener {

  private lateinit var webView: WebView
  private lateinit var sensorManager: SensorManager
  private var rotationSensor: Sensor? = null
  private var orientationSensor: Sensor? = null
  private var isCompassRegistered = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    if (rotationSensor == null) {
      orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
    }
    requestCorePermissions()
    setContent {
      MyApplicationTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          AndroidView(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            factory = { context ->
              WebView(context).apply {
                webView = this
                settings.apply {
                  javaScriptEnabled = true
                  domStorageEnabled = true
                  databaseEnabled = true
                  setGeolocationEnabled(true)
                  allowFileAccess = true
                  allowContentAccess = true
                  mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                  mediaPlaybackRequiresUserGesture = false
                }
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                  override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread { request.grant(request.resources) }
                  }
                  override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                    callback.invoke(origin, true, false)
                  }
                }
                addJavascriptInterface(AndroidBridge(), "AndroidInterface")
                loadUrl("file:///android_asset/index.html")
              }
            }
          )
        }
      }
    }
  }

  private fun requestCorePermissions() {
    val permissions = mutableListOf<String>()
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)!= PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)!= PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)!= PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO)
    if (permissions.isNotEmpty()) ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
  }

  private fun registerCompass() {
    if (isCompassRegistered) return
    if (rotationSensor!= null) {
      sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
      isCompassRegistered = true
    } else if (orientationSensor!= null) {
      sensorManager.registerListener(this, orientationSensor, SensorManager.SENSOR_DELAY_UI)
      isCompassRegistered = true
    }
  }

  private fun unregisterCompass() {
    if (!isCompassRegistered) return
    sensorManager.unregisterListener(this)
    isCompassRegistered = false
  }

  override fun onResume() { super.onResume(); registerCompass() }
  override fun onPause() { super.onPause(); unregisterCompass() }

  override fun onSensorChanged(event: SensorEvent) {
    var azimuth = 0f
    if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
      val rotationMatrix = FloatArray(9)
      SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
      val orientationValues = FloatArray(3)
      SensorManager.getOrientation(rotationMatrix, orientationValues)
      azimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
      azimuth = (azimuth + 360) % 360
    } else if (event.sensor.type == Sensor.TYPE_ORIENTATION) {
      azimuth = event.values[0]
    }
    sendAzimuthToWebView(azimuth)
  }
  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
  private fun sendAzimuthToWebView(azimuth: Float) {
    if (::webView.isInitialized) {
      webView.post { webView.evaluateJavascript("if (window.onCompassChanged) { window.onCompassChanged($azimuth); }", null) }
    }
  }

  // === إصلاح المساعد الإسلامي - يخدم حتى بلا API ===
  private fun getOfflineResponse(prompt: String): String {
    val q = prompt.lowercase()
    return when {
      q.contains("صلاة") || q.contains("وضوء") -> "🕌 **الصلاة ركن عظيم:** قال تعالى ﴿ إِنَّ الصَّلَاةَ كَانَتْ عَلَى الْمُؤْمِنِينَ كِتَابًا مَوْقُوتًا ﴾ [النساء: 103]. حافظ على الوضوء الصحيح والخشوع. إذا أردت تفصيل صفة الوضوء أو الصلاة قل لي: علمني الوضوء."
      q.contains("صيام") || q.contains("رمضان") -> "🌙 **الصيام:** هو الإمساك عن المفطرات بنية من الفجر للمغرب. قال ﷺ: «من صام رمضان إيمانا واحتسابا غفر له ما تقدم من ذنبه» [متفق عليه]."
      q.contains("قرآن") || q.contains("قران") -> "📖 **القرآن الكريم** هو كلام الله المنزل على نبينا محمد ﷺ. ابدأ بقراءة صفحة يوميا مع التدبر. هل تريد تفسير آية معينة؟"
      q.contains("دعاء") || q.contains("ذكر") -> "🤲 **من الأذكار النبوية:** «سبحان الله وبحمده، سبحان الله العظيم» [رواه البخاري]. أكثر من الاستغفار والصلاة على النبي ﷺ."
      q.contains("قبلة") || q.contains("قبله") -> "🧭 استعمل بوصلة القبلة في التطبيق، واتجه نحو 137° تقريبا من المغرب. والله أعلم."
      else -> "السلام عليكم ورحمة الله! أنا مساعدك الإسلامي **صراط برو** 🌟. سؤالك: \"$prompt\" \n\nأحاول الاتصال بالخدمة الذكية، لكن أعمل الآن في الوضع دون اتصال. اسألني عن الصلاة، الوضوء، الصيام، القرآن، الأذكار، وسأجيبك من المصادر الموثوقة بإذن الله. وللمسائل الفقهية الكبرى أنصحك بسؤال عالم موثوق في مدينتك."
    }
  }

  private suspend fun callGeminiApi(prompt: String): String = withContext(Dispatchers.IO) {
    try {
      val apiKey = try { BuildConfig.GEMINI_API_KEY } catch(e:Exception){ "" }
      if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
        return@withContext getOfflineResponse(prompt)
      }
      // الموديل الصحيح هو 1.5-flash وليس 3.5
      val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=$apiKey"
      val systemInstructionText = "أنت مساعد إسلامي ذكي وموثوق اسمك (مساعد صراط برو الذكي). تجيب بأدب من القرآن والسنة الصحيحة لأهل السنة والجماعة. اذكر الآيات مع السورة والرقم والأحاديث مع التخريج. أسلوبك دافئ ومبسط."
      val jsonRequest = JSONObject().apply {
        put("contents", JSONArray().apply { put(JSONObject().apply { put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) }) }) })
        put("systemInstruction", JSONObject().apply { put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemInstructionText) }) }) })
      }
      val client = OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
      val body = jsonRequest.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
      val request = Request.Builder().url(url).post(body).build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@withContext getOfflineResponse(prompt) + "\n\n(ملاحظة: تعذر الاتصال بالخادم ${response.code})"
        val responseBody = response.body?.string()?: return@withContext getOfflineResponse(prompt)
        val jsonResponse = JSONObject(responseBody)
        val candidates = jsonResponse.getJSONArray("candidates")
        val content = candidates.getJSONObject(0).getJSONObject("content")
        val parts = content.getJSONArray("parts")
        return@withContext parts.getJSONObject(0).getString("text")
      }
    } catch (e: Exception) {
      return@withContext getOfflineResponse(prompt)
    }
  }

  inner class AndroidBridge {
    @JavascriptInterface fun startCompass() { runOnUiThread { registerCompass() } }
    @JavascriptInterface
    fun generateIslamicResponse(prompt: String, callbackJsFunctionName: String) {
      lifecycleScope.launch {
        val result = callGeminiApi(prompt)
        // استعمال JSONObject.quote باش ما يتقطعش النص
        val safeJson = JSONObject.quote(result)
        runOnUiThread {
          if (::webView.isInitialized) {
            webView.evaluateJavascript("if (window.$callbackJsFunctionName) { window.$callbackJsFunctionName({ success: true, response: $safeJson }); }", null)
          }
        }
      }
    }
  }
}
