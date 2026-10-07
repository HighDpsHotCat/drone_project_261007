package com.example.dronecontroller

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.dronecontroller.ble.DroneBleClient
import com.example.dronecontroller.camera.CameraStream
import com.example.dronecontroller.camera.VideoRecorder
import com.example.dronecontroller.control.ControlInput
import com.example.dronecontroller.control.Stick

class DroneController(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("controller", 0)
    val ble = DroneBleClient(application) { leaveControl() }
    var controlScreen by mutableStateOf(false); private set
    var foreground by mutableStateOf(false); private set
    val controlsEnabled get() = foreground && controlScreen && ble.ready && !ble.emergency
    var input by mutableStateOf(ControlInput()); private set
    var streamAddress by mutableStateOf(preferences.getString("stream", "http://192.168.4.1:81/stream")!!); private set
    var frame by mutableStateOf<Bitmap?>(null); private set
    var cameraStatus by mutableStateOf("기체 Wi-Fi에 연결하면 영상을 볼 수 있습니다."); private set
    var cameraReady by mutableStateOf(false); private set
    var recordingStatus by mutableStateOf(""); private set
    var recording by mutableStateOf(false); private set
    var recordingBusy by mutableStateOf(false); private set
    var savedVideo by mutableStateOf<Uri?>(null); private set
    private var recorder: VideoRecorder? = null
    private val camera = CameraStream(application, { bitmap ->
        frame = bitmap; recorder?.offer(bitmap)
    }, { message, ready ->
        cameraStatus = message
        if (cameraReady && !ready) { stopRecording(); frame = null }
        cameraReady = ready
    })

    fun updateStreamAddress(address: String) { streamAddress = address }
    fun enterControl() {
        if (!ble.ready) return
        controlScreen = true; neutral(); startCamera()
    }
    fun leaveControl() {
        neutral(); controlScreen = false; camera.stop(); cameraReady = false; frame = null; stopRecording()
    }
    fun startCamera() {
        stopRecording(); frame = null; cameraReady = false
        preferences.edit().putString("stream", streamAddress.trim()).apply()
        camera.start(streamAddress)
    }
    fun horizontal(stick: Stick) {
        if (!controlsEnabled) return
        input = input.copy(horizontal = stick); ble.setInput(input)
    }
    fun altitude(stick: Stick) {
        if (!controlsEnabled) return
        input = input.copy(altitude = stick.y); ble.setInput(input)
    }
    fun neutral() { input = ControlInput(); ble.setInput(input) }
    fun resume() { foreground = true; neutral() }
    fun pause() { foreground = false; neutral() }
    fun emergencyStop() { neutral(); ble.emergencyStop() }
    fun background() { leaveControl(); ble.disconnect() }

    fun startRecording() {
        val bitmap = frame ?: return
        if (!cameraReady || recorder != null) return
        recordingBusy = true; recordingStatus = "녹화 준비 중…"
        recorder = VideoRecorder(getApplication(), bitmap, {
            recording = true; recordingStatus = "● 녹화 중"
        }, { uri, message ->
            recorder = null; recording = false; recordingBusy = false
            if (uri != null) savedVideo = uri
            recordingStatus = message
        }).also { it.start() }
    }
    fun stopRecording() {
        if (recorder != null) {
            recording = false; recordingStatus = "녹화 마무리 중…"; recorder?.stop()
        }
    }
    override fun onCleared() { camera.close(); recorder?.stop(); ble.close() }
}
