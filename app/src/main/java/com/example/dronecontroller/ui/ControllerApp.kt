package com.example.dronecontroller.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.dronecontroller.DroneController
import com.example.dronecontroller.ble.LinkState

@Composable
fun ControllerApp(controller: DroneController, share: (Uri) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0D1822)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (controller.controlScreen) ControlScreen(controller, share) else PairingScreen(controller, share)
        }
    }
}

@Composable
private fun PairingScreen(controller: DroneController, share: (Uri) -> Unit) {
    val ble = controller.ble
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { ble.scan() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionDenied = !ble.hasPermissions()
        if (!permissionDenied) {
            if (ble.bluetoothEnabled()) ble.scan() else enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DRONE CONTROLLER", color = Color(0xFF79D4FE), fontWeight = FontWeight.Bold)
        Text("기기 연결", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Text("BLE로 조종하고, 기체 Wi-Fi로 OV3660 영상을 받습니다.", color = Color.LightGray)
        Status(ble.message, ble.ready)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                permissionDenied = false
                if (ble.scanning) ble.stopScan()
                else if (!ble.hasPermissions()) permissions.launch(ble.requiredPermissions())
                else if (ble.bluetoothEnabled()) ble.scan() else enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }, enabled = ble.state == LinkState.DISCONNECTED) { Text(if (ble.scanning) "검색 중지" else "기기 검색") }
            OutlinedButton(onClick = { controller.leaveControl(); ble.disconnect() }, enabled = ble.state != LinkState.DISCONNECTED) { Text("연결 해제") }
        }
        if (permissionDenied) {
            Text("블루투스 권한을 허용해야 검색·연결할 수 있습니다.", color = Color(0xFFFFB4AB))
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("앱 권한 설정") }
        }
        ble.devices.forEach { device ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, fontWeight = FontWeight.Bold)
                        Text("${device.address} · ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { ble.connect(device) }, enabled = ble.state == LinkState.DISCONNECTED) { Text("연결") }
                }
            }
        }
        if (ble.devices.isEmpty()) Text(if (ble.scanning) "기기를 찾고 있습니다…" else "검색된 기기가 없습니다.", color = Color.LightGray)
        HorizontalDivider()
        Text("카메라 연결", color = Color.White, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = controller.streamAddress, onValueChange = controller::updateStreamAddress,
            label = { Text("MJPEG 영상 주소") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        Text("휴대폰을 기체 Wi-Fi에 연결하세요. 인터넷이 없어도 연결을 유지하세요.", color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) { Text("Wi-Fi 설정") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("위치 설정") }
        }
        Button(onClick = controller::enterControl, enabled = ble.ready,
            modifier = Modifier.fillMaxWidth()) { Text("컨트롤 화면으로") }
        Text("조종 서비스 연결이 완료되면 컨트롤 화면을 열 수 있습니다.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        if (controller.recordingStatus.isNotEmpty()) Text(controller.recordingStatus, color = Color.LightGray)
        controller.savedVideo?.let { uri -> OutlinedButton(onClick = { share(uri) }, enabled = !controller.recordingBusy) { Text("최근 녹화 영상 공유") } }
    }
}

@Composable
private fun ControlScreen(controller: DroneController, share: (Uri) -> Unit) {
    val ble = controller.ble
    BackHandler { controller.leaveControl() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = controller::leaveControl) { Text("‹ 기기 연결") }
            Text(ble.deviceName, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Button(onClick = controller::emergencyStop, enabled = !ble.emergency,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) { Text("비상 정지") }
        }
        Text(if (ble.emergency) "비상 정지 요청 · 조종 잠김" else "연결됨 · 손을 떼면 위치·고도 유지 요청",
            Modifier.padding(horizontal = 16.dp), color = if (ble.emergency) Color(0xFFFFB4AB) else Color(0xFF79D4FE),
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = controller::startCamera) { Text("영상 재연결") }
            Button(onClick = { if (controller.recording) controller.stopRecording() else controller.startRecording() },
                enabled = controller.recording || (!controller.recordingBusy && controller.cameraReady)) {
                Text(if (controller.recording) "녹화 중지" else if (controller.recordingBusy) "저장 중…" else "녹화")
            }
            TextButton(onClick = { controller.savedVideo?.let(share) }, enabled = controller.savedVideo != null && !controller.recordingBusy) { Text("공유") }
        }
        if (controller.recordingStatus.isNotEmpty()) Text(controller.recordingStatus,
            Modifier.padding(horizontal=16.dp), color=Color.LightGray, style=MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            controller.frame?.let { bitmap ->
                Image(remember(bitmap) { bitmap.asImageBitmap() }, "OV3660 실시간 영상",
                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Text(controller.cameraStatus, Modifier.align(Alignment.TopCenter).background(Color(0xCC0D1822)).padding(10.dp),
                color = Color.White, style = MaterialTheme.typography.bodySmall)
            val diameter = minOf(160.dp, maxWidth * .38f, maxHeight * .55f)
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x55000000)).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Joystick("전후 · 좌우", diameter, false, controller.controlsEnabled, controller::horizontal)
                Joystick("상승 · 하강", diameter, true, controller.controlsEnabled, controller::altitude)
            }
        }
        if (ble.telemetry.isNotBlank()) Text("기체: ${ble.telemetry}", Modifier.padding(8.dp), color = Color.LightGray,
            style=MaterialTheme.typography.bodySmall, maxLines=2, overflow=TextOverflow.Ellipsis)
    }
}

@Composable
private fun Status(message: String, ready: Boolean) {
    Text((if (ready) "● " else "○ ") + message, color = if (ready) Color(0xFF79D4FE) else Color.LightGray)
}
