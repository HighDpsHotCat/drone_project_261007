package com.example.dronecontroller.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.dronecontroller.control.ControlInput
import com.example.dronecontroller.control.ControlProtocol

enum class LinkState { DISCONNECTED, CONNECTING, DISCOVERING, READY }
data class NearbyDevice(val address: String, val name: String, val rssi: Int)

/** All state and GATT operations are serialized on the main thread. */
@SuppressLint("MissingPermission")
class DroneBleClient(context: Context, private val onLost: () -> Unit) {
    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    var devices by mutableStateOf<List<NearbyDevice>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var state by mutableStateOf(LinkState.DISCONNECTED); private set
    var message by mutableStateOf("연결할 기기를 검색하세요."); private set
    var deviceName by mutableStateOf(""); private set
    var telemetry by mutableStateOf(""); private set
    var emergency by mutableStateOf(false); private set
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var input = ControlInput()
    private var sequence = 0
    private var writing = false
    private var pending: ByteArray? = null
    private var disconnectAfterWrite = false
    private var closing by mutableStateOf(false)
    val ready get() = state == LinkState.READY && !closing
    fun bluetoothEnabled() = hasPermissions() && adapter?.isEnabled == true

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    fun scan() {
        if (!hasPermissions()) { message = "블루투스 권한이 필요합니다."; return }
        if (adapter?.isEnabled != true) { message = "휴대폰의 블루투스를 켜 주세요."; return }
        val location = app.getSystemService(LocationManager::class.java)
        val locationEnabled = if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled
            else location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT < 31 && !locationEnabled) {
            message = "기기 검색을 위해 휴대폰의 위치 서비스를 켜 주세요."; return
        }
        if (scanning || state != LinkState.DISCONNECTED) return
        devices = emptyList()
        try {
            adapter.bluetoothLeScanner.startScan(null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanning = true
            message = "주변 BLE 기기 검색 중…"
            handler.postDelayed(scanTimeout, 10_000)
        } catch (e: SecurityException) { message = "블루투스 권한을 다시 허용해 주세요." }
        catch (e: IllegalStateException) { message = "블루투스를 켜고 다시 검색해 주세요." }
    }

    private val scanTimeout = Runnable { stopScan(); message = "검색 완료 · 기기를 선택하세요." }
    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanning) try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handler.post {
            if (!scanning || !hasPermissions()) return@post
            val device = result.device
            val item = NearbyDevice(device.address,
                result.scanRecord?.deviceName ?: device.name ?: "이름 없는 BLE 기기", result.rssi)
            devices = (devices.filterNot { it.address == item.address } + item).sortedByDescending { it.rssi }
        } }
        override fun onScanFailed(errorCode: Int) { handler.post {
            stopScan(); message = "검색 실패 ($errorCode). 잠시 후 다시 검색해 주세요."
        } }
    }

    fun connect(device: NearbyDevice) {
        if (!hasPermissions() || state != LinkState.DISCONNECTED) return
        stopScan()
        emergency = false; closing = false; deviceName = device.name; telemetry = ""
        state = LinkState.CONNECTING; message = "${device.name} 연결 중…"
        try {
            gatt = adapter?.getRemoteDevice(device.address)?.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail("블루투스를 켜 주세요.") else handler.postDelayed(connectTimeout, 12_000)
        } catch (_: SecurityException) { fail("연결 권한이 없습니다.") }
        catch (_: IllegalArgumentException) { fail("기기 주소를 확인해 주세요.") }
    }

    private val connectTimeout = Runnable { fail("연결 또는 서비스 확인 시간이 초과되었습니다.") }
    private val writeTimeout = Runnable { fail("조종 명령 응답이 없습니다. 연결을 종료했습니다.") }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) { handler.post {
            if (g !== gatt) { g.close(); return@post }
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("기기 연결이 해제되었습니다. ($status)")
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                state = LinkState.DISCOVERING; message = "조종 서비스 확인 중…"
                if (!g.discoverServices()) fail("서비스 검색을 시작하지 못했습니다.")
            }
        } }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { handler.post {
            if (g !== gatt || closing) return@post
            val service = g.getService(ControlProtocol.service)
            rx = service?.getCharacteristic(ControlProtocol.command)
            tx = service?.getCharacteristic(ControlProtocol.telemetry)
            val notify = tx
            val descriptor = notify?.getDescriptor(ControlProtocol.cccd)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || notify == null || descriptor == null ||
                rx!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
                notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                fail("호환되는 드론 조종 서비스가 없습니다."); return@post
            }
            if (!g.setCharacteristicNotification(notify, true)) { fail("상태 알림을 활성화하지 못했습니다."); return@post }
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            if (!ok) fail("상태 알림 등록을 시작하지 못했습니다.")
        } }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { handler.post {
            if (g !== gatt || closing || descriptor.uuid != ControlProtocol.cccd) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("상태 알림 등록에 실패했습니다. ($status)"); return@post }
            handler.removeCallbacks(connectTimeout)
            state = LinkState.READY; message = "연결됨 · 조종 서비스 준비 완료"
            input = ControlInput(); handler.post(heartbeat)
        } }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { handler.post {
            if (g !== gatt || characteristic.uuid != ControlProtocol.command) return@post
            handler.removeCallbacks(writeTimeout); writing = false
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("조종 명령 전송 실패 ($status)"); return@post }
            val next = pending; pending = null
            if (next != null) send(next) else if (disconnectAfterWrite) closeLink("연결을 해제했습니다.")
        } }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(g, characteristic, value)
        }
        @Deprecated("Legacy Android callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            receive(g, characteristic, characteristic.value?.clone() ?: return)
        }
    }

    private fun receive(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handler.post {
        if (g === gatt && characteristic.uuid == ControlProtocol.telemetry)
            telemetry = value.take(160).toByteArray().toString(Charsets.UTF_8).filter { !it.isISOControl() }
    } }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (state != LinkState.READY || closing) return
            send(ControlProtocol.packet(sequence++, input, emergency))
            handler.postDelayed(this, 50)
        }
    }

    fun setInput(value: ControlInput) {
        input = if (emergency || closing) ControlInput() else value
        if (state == LinkState.READY && !closing) send(ControlProtocol.packet(sequence++, input, emergency))
    }

    fun emergencyStop() {
        emergency = true; input = ControlInput(); setInput(input)
        message = "비상 정지 요청 전송 · 재연결 전까지 조종 잠금"
    }

    private fun send(packet: ByteArray) {
        if (state != LinkState.READY) return
        if (writing) { pending = packet; return }
        val g = gatt ?: return
        val characteristic = rx ?: return
        writing = true
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33)
                g.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) { false }
        if (ok) handler.postDelayed(writeTimeout, 1000) else fail("명령을 전송하지 못했습니다.")
    }

    fun disconnect() {
        stopScan(); handler.removeCallbacks(heartbeat)
        if (state == LinkState.READY && !closing) {
            closing = true; input = ControlInput(); disconnectAfterWrite = true
            message = "중립 명령 전송 후 연결 해제 중…"
            send(ControlProtocol.packet(sequence++, input, emergency))
            handler.postDelayed(forceClose, 1200)
        } else if (!closing) closeLink("연결을 해제했습니다.")
    }
    private val forceClose = Runnable { closeLink("연결을 해제했습니다.") }
    private fun fail(reason: String) { closeLink(reason) }
    private fun closeLink(reason: String) {
        handler.removeCallbacks(heartbeat); handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(writeTimeout); handler.removeCallbacks(forceClose)
        val old = gatt; gatt = null
        try { old?.disconnect() } catch (_: SecurityException) { }
        try { old?.close() } catch (_: SecurityException) { }
        rx = null; tx = null; input = ControlInput(); pending = null; writing = false
        closing = false; disconnectAfterWrite = false
        state = LinkState.DISCONNECTED; message = reason; onLost()
    }
    fun close() { stopScan(); closeLink("연결 해제됨") }
}
