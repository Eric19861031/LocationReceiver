package com.location.receiver

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.location.receiver.databinding.ActivityMainBinding
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mqttClient: MqttClient? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var mapReady = false
    @Volatile private var isConnecting = false
    @Volatile private var waitingForResponse = false

    private val timeoutRunnable = Runnable {
        if (waitingForResponse) {
            waitingForResponse = false
            mainHandler.post { setLoadingState(false, "请求超时，发送器无响应") }
        }
    }

    companion object {
        private const val TAG = "LocationReceiver"
        const val MQTT_BROKER    = "tcp://broker.emqx.io:1883"
        const val TOPIC_REQUEST  = "loc/tracker/fixed_channel_A7B3C9D2E1F5/req"
        const val TOPIC_RESPONSE = "loc/tracker/fixed_channel_A7B3C9D2E1F5/res"
        private const val RESPONSE_TIMEOUT_MS = 15_000L
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWebView()
        setupButton()
        connectMqtt()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.mapView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        binding.mapView.webChromeClient = WebChromeClient()
        binding.mapView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { mapReady = true }
        }
        val ak = getString(R.string.baidu_ak)
        val html = assets.open("map.html").bufferedReader().use { it.readText() }
            .replace("BAIDU_AK_PLACEHOLDER", ak)
        binding.mapView.loadDataWithBaseURL(
            "https://api.map.baidu.com", html, "text/html", "UTF-8", null
        )
    }

    private fun setupButton() {
        binding.btnGetLocation.setOnClickListener {
            if (waitingForResponse) return@setOnClickListener
            if (mqttClient?.isConnected != true) {
                setLoadingState(false, "未连接，请稍候重试")
                return@setOnClickListener
            }
            sendLocationRequest()
        }
    }

    private fun sendLocationRequest() {
        waitingForResponse = true
        setLoadingState(true, "正在请求发送器位置...")
        executor.execute {
            try {
                mqttClient?.publish(
                    TOPIC_REQUEST,
                    MqttMessage("getLocation".toByteArray()).apply { qos = 1 }
                )
                Log.d(TAG, "已发送位置请求")
            } catch (e: Exception) {
                Log.e(TAG, "发送请求失败: ${e.message}")
                waitingForResponse = false
                mainHandler.post { setLoadingState(false, "请求发送失败，请重试") }
                return@execute
            }
        }
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, RESPONSE_TIMEOUT_MS)
    }

    private fun connectMqtt() {
        if (isConnecting) return
        isConnecting = true
        setConnStatus(ConnState.CONNECTING)
        executor.execute {
            try {
                try { mqttClient?.disconnect() } catch (e: Exception) { }
                val clientId = "receiver_${System.currentTimeMillis()}"
                mqttClient = MqttClient(MQTT_BROKER, clientId, MemoryPersistence())
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "连接断开: ${cause?.message}")
                        mainHandler.post { setConnStatus(ConnState.DISCONNECTED) }
                        mainHandler.postDelayed({ connectMqtt() }, 4000)
                    }
                    override fun messageArrived(topic: String, message: MqttMessage) {
                        if (topic == TOPIC_RESPONSE) handleLocationResponse(message.toString())
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                val options = MqttConnectOptions().apply {
                    isCleanSession = false
                    connectionTimeout = 30
                    keepAliveInterval = 60
                    isAutomaticReconnect = true
                    maxReconnectDelay = 10000
                }
                mqttClient?.connect(options)
                mqttClient?.subscribe(TOPIC_RESPONSE, 1)
                isConnecting = false
                mainHandler.post { setConnStatus(ConnState.CONNECTED) }
                Log.d(TAG, "MQTT 连接并订阅响应频道成功")
            } catch (e: Exception) {
                isConnecting = false
                Log.e(TAG, "MQTT 错误: ${e.message}")
                mainHandler.post { setConnStatus(ConnState.DISCONNECTED) }
                mainHandler.postDelayed({ connectMqtt() }, 5000)
            }
        }
    }

    private fun handleLocationResponse(payload: String) {
        try {
            val json = JSONObject(payload)
            val lat = json.getDouble("lat")
            val lon = json.getDouble("lon")
            val accuracy = json.getDouble("accuracy")
            val timestamp = json.getLong("timestamp")
            val timeStr = TIME_FORMAT.format(Date(timestamp))
            mainHandler.post {
                mainHandler.removeCallbacks(timeoutRunnable)
                waitingForResponse = false
                setLoadingState(false, null)
                updateMapAndUI(lat, lon, accuracy, timeStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析响应失败: ${e.message}")
        }
    }

    private fun updateMapAndUI(lat: Double, lon: Double, accuracy: Double, timeStr: String) {
        binding.tvCoords.text = "纬度: %.6f    经度: %.6f".format(lat, lon)
        binding.tvAccuracy.text = "精度: %.1fm".format(accuracy)
        binding.tvLastUpdate.text = "更新: $timeStr"
        setConnStatus(ConnState.RECEIVED)
        if (mapReady) {
            binding.mapView.evaluateJavascript(
                "javascript:updateLocation($lat, $lon, $accuracy, '$timeStr')", null
            )
        }
    }

    private fun setLoadingState(loading: Boolean, hint: String?) {
        if (loading) {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvLocatingHint.visibility = View.VISIBLE
            binding.tvLocatingHint.text = hint ?: ""
            binding.btnGetLocation.isEnabled = false
        } else {
            binding.progressBar.visibility = View.GONE
            if (hint != null) {
                binding.tvLocatingHint.visibility = View.VISIBLE
                binding.tvLocatingHint.text = hint
            } else {
                binding.tvLocatingHint.visibility = View.GONE
            }
            binding.btnGetLocation.isEnabled = true
        }
    }

    private enum class ConnState { CONNECTING, CONNECTED, RECEIVED, DISCONNECTED }

    private fun setConnStatus(state: ConnState) {
        when (state) {
            ConnState.CONNECTING   -> { binding.tvConnStatus.text = "● 连接中..."; binding.tvConnStatus.setTextColor(0xFFFFC107.toInt()) }
            ConnState.CONNECTED    -> { binding.tvConnStatus.text = "● 已连接";   binding.tvConnStatus.setTextColor(0xFF4CAF50.toInt()) }
            ConnState.RECEIVED     -> { binding.tvConnStatus.text = "● 已获取";   binding.tvConnStatus.setTextColor(0xFF4CAF50.toInt()) }
            ConnState.DISCONNECTED -> { binding.tvConnStatus.text = "● 已断开";   binding.tvConnStatus.setTextColor(0xFFE53935.toInt()) }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(timeoutRunnable)
        executor.execute { try { mqttClient?.disconnect() } catch (e: Exception) { } }
        executor.shutdown()
    }
}
