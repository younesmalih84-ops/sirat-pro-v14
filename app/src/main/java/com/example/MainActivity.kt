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

class MainActivity : ComponentActivity(), SensorEventListener {

  private lateinit var webView: WebView
  private lateinit var sensorManager: SensorManager
  private var rotationSensor: Sensor? = null
  private var orientationSensor: Sensor? = null
  private var isCompassRegistered = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Initialize sensors
    sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    if (rotationSensor == null) {
      orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
    }

    // Request permissions on startup (GPS Location and Microphone Voice Chat)
    requestCorePermissions()

    setContent {
      MyApplicationTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          AndroidView(
            modifier = Modifier
              .fillMaxSize()
              .padding(innerPadding),
            factory = { context ->
              WebView(context).apply {
                webView = this
                
                // Configure high-quality WebView settings for offline and P2P WebRTC support
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
                  // Grant WebRTC permission (Microphone for peer-to-peer voice chat)
                  override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                      request.grant(request.resources)
                    }
                  }

                  // Grant Geolocation permission (for GPS-based Qibla compass)
                  override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback
                  ) {
                    callback.invoke(origin, true, false)
                  }
                }

                // Add Javascript interface bridges
                addJavascriptInterface(AndroidBridge(), "AndroidInterface")

                // Load our local, beautifully styled asset HTML
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
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      permissions.add(Manifest.permission.RECORD_AUDIO)
    }
    if (permissions.isNotEmpty()) {
      ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
    }
  }

  private fun registerCompass() {
    if (isCompassRegistered) return
    if (rotationSensor != null) {
      sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
      isCompassRegistered = true
    } else if (orientationSensor != null) {
      sensorManager.registerListener(this, orientationSensor, SensorManager.SENSOR_DELAY_UI)
      isCompassRegistered = true
    }
  }

  private fun unregisterCompass() {
    if (!isCompassRegistered) return
    sensorManager.unregisterListener(this)
    isCompassRegistered = false
  }

  override fun onResume() {
    super.onResume()
    registerCompass()
  }

  override fun onPause() {
    super.onPause()
    unregisterCompass()
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
      val rotationMatrix = FloatArray(9)
      SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
      val orientationValues = FloatArray(3)
      SensorManager.getOrientation(rotationMatrix, orientationValues)
      var azimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
      azimuth = (azimuth + 360) % 360
      sendAzimuthToWebView(azimuth)
    } else if (event.sensor.type == Sensor.TYPE_ORIENTATION) {
      val azimuth = event.values[0]
      sendAzimuthToWebView(azimuth)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

  private fun sendAzimuthToWebView(azimuth: Float) {
    if (::webView.isInitialized) {
      webView.post {
        webView.evaluateJavascript("if (window.onCompassChanged) { window.onCompassChanged($azimuth); }", null)
      }
    }
  }

  // Coroutine-driven OkHttp call to the secure Gemini REST API
  private suspend fun callGeminiApi(prompt: String): String = withContext(Dispatchers.IO) {
    val apiKey = BuildConfig.GEMINI_API_KEY
    if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
      return@withContext "Error: API Key is missing. Please configure GEMINI_API_KEY in the AI Studio secrets panel."
    }
    
    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
    
    val systemInstructionText = "أنت مساعد إسلامي ذكي وموثوق اسمك (مساعد صراط برو الذكي). تجيب عن الأسئلة الدينية والشرعية والتعليمية بأدب، بالاعتماد على القرآن الكريم والسنة النبوية الصحيحة ومصادر أهل السنة والجماعة الموثوقة. اكتب الآيات بالرسم العثماني إن أمكن مع ذكر السورة ورقم الآية، واذكر الأحاديث مع تخريجها (مثل رواه البخاري أو مسلم). تجنب الفتاوى المعقدة في المسائل الخلافية الكبرى ووجه المستخدم لسؤال أهل الاختصاص برفق. أسلوبك دافئ ومبسط وملهم ومناسب لجميع الأعمار."

    val jsonRequest = JSONObject().apply {
      put("contents", org.json.JSONArray().apply {
        put(JSONObject().apply {
          put("parts", org.json.JSONArray().apply {
            put(JSONObject().apply {
              put("text", prompt)
            })
          })
        })
      })
      put("systemInstruction", JSONObject().apply {
        put("parts", org.json.JSONArray().apply {
          put(JSONObject().apply {
            put("text", systemInstructionText)
          })
        })
      })
    }
    
    val client = OkHttpClient.Builder()
      .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
      .build()
        
    val body = jsonRequest.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder()
      .url(url)
      .post(body)
      .build()
        
    try {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          return@withContext "Error: HTTP ${response.code} ${response.message}"
        }
        val responseBody = response.body?.string() ?: return@withContext "Error: Empty response body"
        val jsonResponse = JSONObject(responseBody)
        val candidates = jsonResponse.getJSONArray("candidates")
        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.getJSONObject("content")
        val parts = content.getJSONArray("parts")
        return@withContext parts.getJSONObject(0).getString("text")
      }
    } catch (e: Exception) {
      return@withContext "Error: ${e.message}"
    }
  }

  // Inner class defining safe and high-performance JS hooks
  inner class AndroidBridge {
    @JavascriptInterface
    fun startCompass() {
      runOnUiThread {
        registerCompass()
      }
    }

    @JavascriptInterface
    fun generateIslamicResponse(prompt: String, callbackJsFunctionName: String) {
      lifecycleScope.launch {
        val result = callGeminiApi(prompt)
        val escapedText = result.replace("\\", "\\\\")
          .replace("'", "\\'")
          .replace("\"", "\\\"")
          .replace("\n", "\\n")
          .replace("\r", "\\r")
        
        runOnUiThread {
          if (::webView.isInitialized) {
            webView.evaluateJavascript(
              "if (window.$callbackJsFunctionName) { window.$callbackJsFunctionName({ success: true, response: \"$escapedText\" }); }",
              null
            )
          }
        }
      }
    }
  }
}
