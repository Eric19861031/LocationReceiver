package com.location.receiver

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.location.receiver.databinding.ActivityMainBinding
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mqttClient: MqttClient? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var senderMarker: Marker? = null
    private var hasFirstFix = false

    @Volatile
    private var isConnecting = false

    companion object {
        private const val TAG = "LocationReceiver"
        const val MQTT_BROKER = "tcp://broker.emqx.io:1883"
        const val MQTT_TOPIC = "loc/tracker/fixed_channel_A7B3C9D2E1F5"
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OSMDroid 使用应用私有目录，无需存储权限
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = packageName
        osmConfig.osmdroidBasePath = File(cacheDir, "osmdroid")
        osmConfig.osmdroidTileCache = File(osmConfig.osmdroidBasePath, "tiles")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        connectMqtt()
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(5.0)
            controller.setCenter(GeoPoint(35.8617, 104.1954)) // 中国中心点
        }

        // 显示我的位置（可选）
        val myLocationOverlay = MyLocationNewOverlay(
            GpsMyLocationProvider(this), binding.mapView
        )
        myLocationOverlay.enableMyLocation()
        binding.mapView.overlays.add(myLocationOverlay)
    }

    private fun connectMqtt() {
        if (isConnecting) return
        isConnecting = true

        setConnStatus(ConnState.CONNECTING)

        executor.execute {
            try {
                try { mqttClient?.disconnect() } catch (e: Exception) { /* ignore */ }

                val clientId = "receiver_${System.currentTimeMillis()}"
                mqttClient = MqttClient(MQTT_BROKER, clientId, MemoryPersistence())

                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "连接断开: ${cause?.message}")
                        mainHandler.post { setConnStatus(ConnState.DISCONNECTED) }
                        mainHandler.postDelayed({ connectMqtt() }, 4000)
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        handleLocationMessage(message.toString())
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
                mqttClient?.subscribe(MQTT_TOPIC, 1)

                isConnecting = false
                mainHandler.post { setConnStatus(ConnState.CONNECTED) }
                Log.d(TAG, "MQTT 连接并订阅成功")
            } catch (e: Exception) {
                isConnecting = false
                Log.e(TAG, "MQTT 错误: ${e.message}")
                mainHandler.post { setConnStatus(ConnState.DISCONNECTED) }
                mainHandler.postDelayed({ connectMqtt() }, 5000)
            }
        }
    }

    private fun handleLocationMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            val lat = json.getDouble("lat")
            val lon = json.getDouble("lon")
            val accuracy = json.getDouble("accuracy")
            val timestamp = json.getLong("timestamp")
            val timeStr = TIME_FORMAT.format(Date(timestamp))

            mainHandler.post { updateMapAndUI(lat, lon, accuracy, timeStr) }
        } catch (e: Exception) {
            Log.e(TAG, "解析消息失败: ${e.message}")
        }
    }

    private fun updateMapAndUI(lat: Double, lon: Double, accuracy: Double, timeStr: String) {
        val point = GeoPoint(lat, lon)

        // 更新状态栏信息
        binding.tvCoords.text = "纬度: %.6f    经度: %.6f".format(lat, lon)
        binding.tvAccuracy.text = "精度: %.1fm".format(accuracy)
        binding.tvLastUpdate.text = "更新: $timeStr"
        setConnStatus(ConnState.RECEIVING)

        // 创建或更新地图标记
        if (senderMarker == null) {
            senderMarker = Marker(binding.mapView).apply {
                title = "发送器"
                snippet = "%.6f, %.6f".format(lat, lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            binding.mapView.overlays.add(senderMarker)
        }

        senderMarker?.apply {
            position = point
            snippet = "精度: %.1fm  更新: %s".format(accuracy, timeStr)
        }

        // 首次收到位置时放大地图到该位置
        if (!hasFirstFix) {
            hasFirstFix = true
            binding.mapView.controller.setZoom(16.0)
        }

        binding.mapView.controller.animateTo(point)
        binding.mapView.invalidate()
    }

    private enum class ConnState { CONNECTING, CONNECTED, RECEIVING, DISCONNECTED }

    private fun setConnStatus(state: ConnState) {
        when (state) {
            ConnState.CONNECTING -> {
                binding.tvConnStatus.text = "● 连接中..."
                binding.tvConnStatus.setTextColor(0xFFFFC107.toInt())
            }
            ConnState.CONNECTED -> {
                binding.tvConnStatus.text = "● 已连接"
                binding.tvConnStatus.setTextColor(0xFF4CAF50.toInt())
            }
            ConnState.RECEIVING -> {
                binding.tvConnStatus.text = "● 接收中"
                binding.tvConnStatus.setTextColor(0xFF4CAF50.toInt())
            }
            ConnState.DISCONNECTED -> {
                binding.tvConnStatus.text = "● 已断开"
                binding.tvConnStatus.setTextColor(0xFFE53935.toInt())
            }
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
        executor.execute {
            try { mqttClient?.disconnect() } catch (e: Exception) { /* ignore */ }
        }
        executor.shutdown()
    }
}
