package com.example.dronecontroller

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.DisposableEffect
import com.example.dronecontroller.ui.ControllerApp
import com.example.dronecontroller.ui.theme.DroneControllerTheme

class MainActivity : ComponentActivity() {
    private lateinit var controller: DroneController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ViewModelProvider(this)[DroneController::class.java]
        enableEdgeToEdge()
        setContent {
            DisposableEffect(controller.controlScreen) {
                if (controller.controlScreen) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            DroneControllerTheme(darkTheme = true, dynamicColor = false) {
                ControllerApp(controller) { uri ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = android.content.ClipData.newUri(contentResolver, "드론 영상", uri)
                    }, "녹화 영상 공유"))
                }
            }
        }
    }
    override fun onResume() { super.onResume(); controller.resume() }
    override fun onPause() { controller.pause(); super.onPause() }
    override fun onStop() {
        if (!isChangingConfigurations) controller.background()
        super.onStop()
    }
}
