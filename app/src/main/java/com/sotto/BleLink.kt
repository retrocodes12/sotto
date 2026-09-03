package com.sotto

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * The Bluetooth Low Energy transport: the same frames the sound link carries, over the radio.
 *
 * Every phone both advertises and scans, so they find each other with no pairing and no server,
 * exactly like the chirps do. The advertisement carries only the 16-bit id, which is what makes
 * presence work at range. Frames travel over a GATT connection: each phone runs a server with one
 * writable characteristic, and sending means writing to every peer it has seen — broadcast
 * semantics, so the app's own duplicate suppression behaves the same as it does over sound.
 *
 * Everything here is best-effort. Bluetooth off, permission refused, a phone that will not
 * connect: the link reports itself unavailable and the app keeps talking through the speaker.
 *
 * The invariant that makes this safe, and the reason lint's permission check is suppressed for
 * the class: nothing starts until [start] has confirmed the permissions, and every single call
 * into the Bluetooth stack below sits inside a runCatching, so a permission revoked while the
 * app runs surfaces as a failed send rather than a crash. Any Bluetooth call added to this file
 * must keep both halves of that.
 */
@SuppressLint("MissingPermission")   // see the invariant above every Bluetooth call in this file
class BleLink(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        /** A frame arrived over the radio. Called on the BLE thread. */
        fun onBleFrame(payload: ByteArray)
        /** An advertisement from [id] was seen, [rssi] dBm. Called on the BLE thread. */
        fun onBlePresence(id: Int, rssi: Int)
        /** Something the user should know: null clears it. */
        fun onBleState(running: Boolean, peers: Int, note: String?)
    }

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    /** Bluetooth being turned on or off in the phone's own settings. */
    private val adapterWatcher = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: android.content.Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> if (wanted) start(myId)
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    val wantedBefore = wanted
                    stop()
                    wanted = wantedBefore   // the user still wants it; we are just waiting for the radio
                    report("Bluetooth is off")
                }
            }
        }
    }
    private var watching = false
    @Volatile private var wanted = false
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val thread = HandlerThread("sotto-ble").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var running = false
    @Volatile private var myId = 0
    private var server: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    /** Peers seen advertising, by their Sotto id. */
    private val peers = HashMap<Int, Peer>()
    /** Live outbound connections, by device address. */
    private val conns = HashMap<String, Conn>()
    /** Inbound fragments being reassembled, by device address. */
    private val inbox = HashMap<String, Reassembly>()
    /** When each peer's presence was last passed up, so scan results do not flood the app. */
    private val lastReported = HashMap<Int, Long>()

    private class Peer(val device: BluetoothDevice, var lastSeen: Long, var rssi: Int)

    private class Reassembly(val total: Int, val msgId: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var at = 0L
        fun complete() = parts.all { it != null }
        fun join(): ByteArray {
            var n = 0
            for (p in parts) n += p!!.size
            val out = ByteArray(n)
            var o = 0
            for (p in parts) { p!!.copyInto(out, o); o += p.size }
            return out
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    fun hasHardware(): Boolean = adapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    fun isOn(): Boolean = adapter?.isEnabled == true

    /** Permissions this Android version needs before any of it can run. */
    fun missingPermissions(): List<String> = neededPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun neededPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)   // pre-12 scanning is gated on location

    /** Starts advertising, scanning and the GATT server. Safe to call repeatedly. */
    fun start(id: Int) {
        myId = id
        wanted = true
        if (!watching) {
            watching = true
            runCatching {
                context.registerReceiver(adapterWatcher, android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            }.onFailure { watching = false }
        }
        handler.post {
            if (running) return@post
            if (!hasHardware()) { report("this phone has no Bluetooth LE"); return@post }
            if (!isOn()) { report("Bluetooth is off"); return@post }
            val missing = missingPermissions()
            if (missing.isNotEmpty()) { report("Bluetooth permission not granted"); return@post }
            running = true
            openServer()
            startAdvertising()
            startScanning()
            Log.i(TAG, "started as ${IdentityStore.tagOf(myId)}")
            report(null)
        }
    }

    fun stop() {
        wanted = false
        handler.post {
            if (!running) return@post
            running = false
            stopAdvertising()
            stopScanning()
            for (c in conns.values.toList()) c.close()
            conns.clear()
            peers.clear()
            inbox.clear()
            lastReported.clear()
            runCatching { server?.close() }
            server = null
            characteristic = null
            Log.i(TAG, "stopped")
            report(null)
        }
    }

    fun close() {
        stop()
        if (watching) { watching = false; runCatching { context.unregisterReceiver(adapterWatcher) } }
        handler.post { thread.quitSafely() }
    }

    /** True when at least one phone has been heard recently, so a frame would actually go somewhere. */
    fun canSend(): Boolean {
        if (!running) return false
        synchronized(peers) {
            val now = SystemClock.elapsedRealtime()
            return peers.values.any { now - it.lastSeen < PEER_FRESH_MS }
        }
    }

    fun peerCount(): Int {
        synchronized(peers) {
            val now = SystemClock.elapsedRealtime()
            return peers.values.count { now - it.lastSeen < PEER_FRESH_MS }
        }
    }

    /** Ids heard over the radio within [withinMs]. */
    fun nearbyIds(withinMs: Long): List<Int> {
        synchronized(peers) {
            val now = SystemClock.elapsedRealtime()
            return peers.entries.filter { now - it.value.lastSeen < withinMs }.map { it.key }
        }
    }

    // ---- sending ---------------------------------------------------------------------------

    /**
     * Writes [frame] to every peer seen recently, and reports whether any of them acknowledged
     * it. Returns false straight away when nobody is in range, in which case [onResult] never
     * runs; otherwise [onResult] fires exactly once, on the BLE thread, and a false there means
     * the caller must fall back to the speaker.
     */
    fun send(frame: ByteArray, onResult: (Boolean) -> Unit): Boolean {
        if (!running) return false
        val targets = synchronized(peers) {
            val now = SystemClock.elapsedRealtime()
            peers.values.filter { now - it.lastSeen < PEER_FRESH_MS }.map { it.device }
        }
        if (targets.isEmpty()) return false
        handler.post {
            var pending = 0
            var any = false
            var settled = false
            val each = { ok: Boolean ->
                if (ok) any = true
                if (--pending == 0 && !settled) { settled = true; onResult(any) }
            }
            val live = targets.mapNotNull { connectionTo(it) }
            if (live.isEmpty()) { onResult(false); return@post }
            pending = live.size
            for (c in live) c.enqueue(Pending(frame, each))
        }
        return true
    }

    /** One frame on its way to one phone, and who to tell when it lands or does not. */
    private class Pending(val frame: ByteArray, val onResult: (Boolean) -> Unit) {
        private var done = false
        fun settle(ok: Boolean) { if (!done) { done = true; onResult(ok) } }
    }

    // ---- advertising -----------------------------------------------------------------------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising failed, code $errorCode")
            report(when (errorCode) {
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "this phone cannot advertise over Bluetooth"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Bluetooth advertisement too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "another app is using Bluetooth advertising"
                else -> null
            })
        }
    }

    private fun startAdvertising() {
        val advertiser = runCatching { adapter?.bluetoothLeAdvertiser }.getOrNull() ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)   // and therefore scannable, which is what carries the response
            .setTimeout(0)
            .build()
        // A legacy advertisement holds 31 bytes, of which the flags take 3 and a 128-bit
        // service uuid takes 18. Our three bytes of service data would not fit beside them,
        // so they ride in the scan response, which gets 31 bytes of its own. Android merges
        // the two before handing a scan record to the callback.
        val primary = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val response = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), byteArrayOf(FORMAT, (myId shr 8).toByte(), myId.toByte()))
            .build()
        runCatching { advertiser.startAdvertising(settings, primary, response, advertiseCallback) }
            .onFailure { Log.w(TAG, "startAdvertising threw", it) }
    }

    private fun stopAdvertising() {
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
    }

    // ---- scanning --------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { for (r in results) handle(r) }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed, code $errorCode")
            report("Bluetooth scanning failed")
        }

        private fun handle(result: ScanResult) {
            val sd = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            if (sd.size < 3 || sd[0] != FORMAT) return
            val id = ((sd[1].toInt() and 0xFF) shl 8) or (sd[2].toInt() and 0xFF)
            if (id == 0 || id == myId) return
            val fresh: Boolean
            var stale: String? = null
            synchronized(peers) {
                val now = SystemClock.elapsedRealtime()
                val p = peers[id]
                fresh = p == null || now - p.lastSeen > PEER_FRESH_MS
                when {
                    p == null -> peers[id] = Peer(result.device, now, result.rssi)
                    // Android rotates a phone's Bluetooth address for privacy; the id in the
                    // advertisement is what identifies it, so follow the address it moved to
                    // and drop the connection to the one it left.
                    p.device.address != result.device.address -> {
                        stale = p.device.address
                        peers[id] = Peer(result.device, now, result.rssi)
                    }
                    else -> { p.lastSeen = now; p.rssi = result.rssi }
                }
            }
            stale?.let { addr -> handler.post { conns[addr]?.close() } }
            // Advertisements arrive several times a second; the app only needs to know a phone
            // is there, and every report costs a preferences write and a recomposition.
            val now = SystemClock.elapsedRealtime()
            val last = lastReported[id] ?: 0L
            if (fresh || now - last > PRESENCE_REPORT_MS) {
                lastReported[id] = now
                callbacks.onBlePresence(id, result.rssi)
            }
            if (fresh) { Log.i(TAG, "peer ${IdentityStore.tagOf(id)} at ${result.rssi} dBm"); report(null) }
        }
    }

    private fun startScanning() {
        val scanner = runCatching { adapter?.bluetoothLeScanner }.getOrNull() ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()
        runCatching { scanner.startScan(filters, settings, scanCallback) }
            .onFailure { Log.w(TAG, "startScan threw", it) }
    }

    private fun stopScanning() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // ---- receiving: our GATT server --------------------------------------------------------

    private fun openServer() {
        val c = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(c) }
        val s = runCatching { manager?.openGattServer(context, serverCallback) }.getOrNull()
        if (s == null) { report("Bluetooth server could not start"); return }
        runCatching { s.addService(service) }
        server = s
        characteristic = c
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null) }
            if (ch.uuid != CHAR_UUID) return
            handler.post { accept(device.address, value) }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) handler.post { inbox.remove(device.address) }
        }
    }

    /**
     * Reassembles a fragment. Frames are small, but a phone that refuses a bigger MTU still
     * works. The header is [total, index, message], and the message byte keeps two frames from
     * the same phone from being spliced into one.
     */
    private fun accept(address: String, part: ByteArray) {
        if (part.size < 4) return
        val total = part[0].toInt() and 0xFF
        val index = part[1].toInt() and 0xFF
        val msgId = part[2].toInt() and 0xFF
        if (total == 0 || index >= total) return
        val body = part.copyOfRange(3, part.size)
        if (total == 1) { deliver(body); return }
        val now = SystemClock.elapsedRealtime()
        val held = inbox[address]
        val r = if (held != null && held.total == total && held.msgId == msgId && index != 0 && now - held.at < REASSEMBLE_MS) held
                else Reassembly(total, msgId).also { inbox[address] = it }
        r.at = now
        r.parts[index] = body
        if (r.complete()) { inbox.remove(address); deliver(r.join()) }
    }

    private fun deliver(frame: ByteArray) {
        if (frame.isEmpty()) return
        callbacks.onBleFrame(frame)
    }

    // ---- sending: outbound connections ------------------------------------------------------

    private fun connectionTo(device: BluetoothDevice): Conn? {
        conns[device.address]?.let { return it }
        if (conns.size >= MAX_CONNS) {
            // Evict the connection that has been idle longest, and only one with nothing queued,
            // so a busy transfer is never thrown away.
            conns.values.filter { it.idle() }.minByOrNull { it.lastUsed }?.close()
            if (conns.size >= MAX_CONNS) return null
        }
        val c = runCatching { Conn(device) }.getOrNull() ?: return null
        if (!c.opened()) return null   // the adapter refused: do not cache a dead connection
        conns[device.address] = c
        return c
    }

    /**
     * One outbound GATT connection with a small queue. Bluetooth allows a single outstanding
     * write, so frames wait their turn; the connection is dropped after a quiet spell.
     */
    /**
     * One outbound GATT connection. Bluetooth allows a single outstanding operation, so frames
     * and their fragments go one at a time and the peer's acknowledgement drives the next one.
     * Every frame is answered, success or failure, so the caller can fall back to the speaker.
     */
    private inner class Conn(val device: BluetoothDevice) : BluetoothGattCallback() {
        private var gatt: BluetoothGatt? = null
        private var target: BluetoothGattCharacteristic? = null
        private var mtu = 23
        private var ready = false
        private var closed = false
        private var msgId = 0
        private val queue = ArrayDeque<Pending>()
        private var current: Pending? = null
        private var fragments: List<ByteArray> = emptyList()
        private var fragIndex = 0
        var lastUsed = SystemClock.elapsedRealtime()

        private val idleTimer = Runnable { if (idle()) close() }
        private val setupTimer = Runnable { if (!ready) { Log.w(TAG, "${device.address} never became ready"); close() } }
        private val writeTimer = Runnable { Log.w(TAG, "${device.address} write timed out"); failCurrent() }

        init {
            gatt = runCatching { device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
            if (gatt != null) handler.postDelayed(setupTimer, SETUP_MS)
        }

        fun opened() = gatt != null

        fun idle() = current == null && queue.isEmpty()

        fun enqueue(p: Pending) {
            if (closed) { p.settle(false); return }
            lastUsed = SystemClock.elapsedRealtime()
            handler.removeCallbacks(idleTimer)
            handler.postDelayed(idleTimer, IDLE_MS)
            if (queue.size >= MAX_QUEUE) queue.removeFirst().settle(false)
            queue.addLast(p)
            pump()
        }

        private fun pump() {
            if (closed || !ready || current != null) return
            val p = queue.removeFirstOrNull() ?: return
            val c = target ?: run { p.settle(false); return }
            val room = (mtu - 3 - 3).coerceAtLeast(16)   // ATT header, then our three-byte fragment header
            val total = ((p.frame.size + room - 1) / room).coerceAtLeast(1)
            if (total > 255) { p.settle(false); pump(); return }
            msgId = (msgId + 1) and 0xFF
            fragments = (0 until total).map { i ->
                val from = i * room
                val to = minOf(p.frame.size, from + room)
                ByteArray(3 + (to - from)).also {
                    it[0] = total.toByte(); it[1] = i.toByte(); it[2] = msgId.toByte()
                    p.frame.copyInto(it, 3, from, to)
                }
            }
            fragIndex = 0
            current = p
            writeNext(c)
        }

        private fun writeNext(c: BluetoothGattCharacteristic) {
            val g = gatt ?: return failCurrent()
            if (fragIndex >= fragments.size) {   // every fragment acknowledged
                handler.removeCallbacks(writeTimer)
                current?.settle(true)
                current = null
                pump()
                return
            }
            val part = fragments[fragIndex]
            // With response, so the peer's acknowledgement is real delivery, not just a
            // handover to our own controller.
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                runCatching { g.writeCharacteristic(c, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS }.getOrDefault(false)
            } else {
                @Suppress("DEPRECATION")
                runCatching { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = part; g.writeCharacteristic(c) }.getOrDefault(false)
            }
            if (!ok) { failCurrent(); return }
            handler.removeCallbacks(writeTimer)
            handler.postDelayed(writeTimer, WRITE_MS)
        }

        private fun failCurrent() {
            handler.removeCallbacks(writeTimer)
            current?.settle(false)
            current = null
            if (!closed) pump()
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Service discovery is the real goal; a phone that refuses a bigger MTU
                    // must not be left waiting for a callback that never comes.
                    val asked = runCatching { g.requestMtu(247) }.getOrDefault(false)
                    if (!asked) runCatching { g.discoverServices() }
                } else {
                    close()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                runCatching { g.discoverServices() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                target = runCatching { g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID) }.getOrNull()
                if (target == null) { Log.w(TAG, "${device.address} has no Sotto service"); close(); return@post }
                handler.removeCallbacks(setupTimer)
                ready = true
                pump()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (closed || current == null) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { failCurrent(); return@post }
                fragIndex++
                writeNext(c)
            }
        }

        /** Answers everything still waiting, so no frame disappears without the caller knowing. */
        fun close() {
            if (closed) return
            closed = true
            ready = false
            handler.removeCallbacks(idleTimer)
            handler.removeCallbacks(setupTimer)
            handler.removeCallbacks(writeTimer)
            current?.settle(false)
            current = null
            while (queue.isNotEmpty()) queue.removeFirst().settle(false)
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
            conns.remove(device.address)
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun report(note: String?) {
        callbacks.onBleState(running, peerCount(), note)
    }

    companion object {
        private const val TAG = "SottoBle"
        /** This app's own service. Nothing else advertises it. */
        val SERVICE_UUID: UUID = UUID.fromString("50770001-0000-1000-8000-00805f9b34fb")
        val CHAR_UUID: UUID = UUID.fromString("50770002-0000-1000-8000-00805f9b34fb")
        /** Marks the advertisement's layout, so a future change can be told apart. */
        private const val FORMAT: Byte = 1
        private const val PEER_FRESH_MS = 45_000L
        private const val REASSEMBLE_MS = 5_000L
        private const val IDLE_MS = 25_000L
        private const val MAX_CONNS = 4
        private const val MAX_QUEUE = 64
        private const val SETUP_MS = 12_000L
        private const val WRITE_MS = 5_000L
        private const val PRESENCE_REPORT_MS = 5_000L
        /** Protocol id the app uses to label frames that came over the radio. */
        const val PROTOCOL_ID = 200
    }
}
