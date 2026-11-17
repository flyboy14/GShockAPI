package org.avmedia.gshockapi.io

import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.model.HeartRateMonitor
import org.avmedia.gshockapi.model.SmartAlarm
import org.avmedia.gshockapi.model.SoundMode
import org.avmedia.gshockapi.model.WatchFaceItem
import org.avmedia.gshockapi.utils.Utils
import timber.log.Timber

/**
 * DW-H5600 (module 3516 family) settings decoded from the official app's BLE traffic.
 * See docs/DW-H5600-protocol.md and FINDINGS.md in the h5600-probe folder for the raw captures.
 *
 * 0x42 (6 bytes): [0]=0x42, [1]=sound mode, [2..5]=per-function CUSTOM table (kept as-is).
 * 0x50 (5 bytes): smart alarm: hh mm flags(01 on, 02 snooze) window(min).
 * 0x51 (6 bytes): [0]=0x51, [1]=face (1 weekday, 2 custom), [2..5]=display item (all four set).
 *
 * All reads go through [RawSettingIO] (shared requests, retries); all writes are read-modify-write
 * through [RawSettingIO.modify], so they keep unknown bytes and cannot undo each other.
 */
object H5600SettingsIO {
    const val SOUND_MODE_CODE = 0x42
    const val WATCH_FACE_CODE = 0x51
    const val SMART_ALARM_CODE = 0x50

    /**
     * CASIO_SETTING_FOR_HEART_RATE_MONITOR (5 bytes), decoded from the CASIO app (Dart class
     * WatchSettingForHeartRateMonitorData): byte 1 bits 0-1 = monitoring condition (0 Off,
     * 1 RunActOnly, 2 AlwaysOn), bit 2 in-range notification, bit 3 = NOT use target, bit 4 alert;
     * byte 2 = selected target; bytes 3/4 = plan heart-rate limits.
     */
    const val HEART_RATE_MONITOR_CODE = 0x41
    private const val HEART_RATE_MONITOR_SIZE = 5

    /** CASIO_CURRENT_TIME_MANAGER. The official app requests it after connecting and writes the reply back. */
    const val TIME_MANAGER_CODE = 0x39

    private const val PACKET_SIZE = 6
    private const val SMART_ALARM_SIZE = 5

    fun isSupported(): Boolean = WatchInfo.model == WatchInfo.WatchModel.DW_H5600

    private fun checkSupported() = check(isSupported()) { "Not a DW-H5600 family watch (model=${WatchInfo.model})" }

    private fun checkPacket(code: Int, packet: IntArray, size: Int = PACKET_SIZE) =
        check(packet.size == size && packet[0] == code) { "Unexpected 0x%02X packet: %s".format(code, packet.toHex()) }

    private suspend fun readChecked(code: Int, size: Int = PACKET_SIZE): IntArray {
        checkSupported()
        return RawSettingIO.request(code).also { checkPacket(code, it, size) }
    }

    /** Checked read-modify-write; returns the packet before the change. */
    private suspend fun modifyChecked(code: Int, size: Int = PACKET_SIZE, transform: (IntArray) -> IntArray?): IntArray {
        checkSupported()
        return RawSettingIO.modify(code) { p -> checkPacket(code, p, size); transform(p) }
    }

    // ---- Connection ----

    /**
     * Connection handshake copied from the official CASIO app (capture 2026-11-16 17:57:06):
     *   app -> 39 (request), watch -> 39 04, app -> 39 04 (written back unchanged), watch -> 47 01.
     * Without it the watch drops Smart Sync's connection after ~45 s; with the official app it stays
     * connected. Echoes exactly what the watch sent; nothing else is written.
     */
    suspend fun connectionHandshake() {
        if (!isSupported()) return
        val reply = RawSettingIO.request(TIME_MANAGER_CODE)
        check(reply.isNotEmpty() && reply[0] == TIME_MANAGER_CODE && reply.size <= 4) { "Unexpected 0x39 reply: ${reply.toHex()}" }
        RawSettingIO.write(reply)
    }

    /** Read-only: reads packets the official app reads on every connect, for logging/comparison. */
    suspend fun readDiagnostics(): Map<Int, String> =
        listOf(0x45, 0x4F).associateWith { code ->
            runCatching { RawSettingIO.request(code).toHex() }.getOrElse { "error: ${it.message}" }
        }

    // ---- Home location (0x24) ----

    /**
     * Home location for sunrise/sunset: `24 00 01 <lat f64 BE> <lon f64 BE> 04`. Trailing byte 0x04
     * as the current official app writes it (user's capture 2026-11-16 23:08; izivkov's older capture
     * had 0x01). The watch computes sunrise/sunset from it; without it the watch shows dashes.
     * Last location given by the app; used when [homeLocationProvider] has none.
     */
    @Volatile
    private var homeLocation: Pair<Double, Double>? = null

    private fun isValid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0

    fun rememberHomeLocation(latitude: Double, longitude: Double) {
        if (isValid(latitude, longitude)) homeLocation = latitude to longitude
    }

    /**
     * Supplied by the app: the location to send right now (real or "weather" coordinates). Asked at
     * time-sync time so a sync never goes out without a location, even before the app has
     * remembered one in this process.
     */
    @Volatile
    var homeLocationProvider: (() -> Pair<Double, Double>?)? = null

    /**
     * Called by TimeIO in the middle of a time sync, after the DST/world-city writes (1D, 1E) and
     * before the city names (1F) and the time (09): the exact place the official app writes 0x24,
     * so the watch recalculates sunrise/sunset with the location already in place.
     * Never throws: a bad or missing location must not stop the time from being set.
     */
    fun writeLocationInTimeSync() {
        if (!isSupported()) return
        val loc = runCatching { homeLocationProvider?.invoke() }.getOrNull()?.takeIf { isValid(it.first, it.second) }
            ?: homeLocation ?: return
        runCatching { setHomeLocation(loc.first, loc.second) }.onFailure { Timber.e(it, "0x24 location write failed") }
    }

    /** World city 1's location (`24 01 …`), written after the home location like the official app. */
    fun writeWorldCityLocation(zone: java.time.ZoneId) {
        if (!isSupported()) return
        runCatching {
            val (lat, lon, _) = org.avmedia.gshockapi.casio.CasioTimeZoneHelper.getWorldCityCoordinates(zone)
            val buf = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.BIG_ENDIAN)
            buf.put(0x24.toByte()).put(0x01).put(0x01).putDouble(lat).putDouble(lon).put(0x04)
            RawSettingIO.write(buf.array().map { it.toInt() and 0xFF }.toIntArray())
        }.onFailure { Timber.e(it, "0x24 01 world-city location write failed") }
    }

    fun setHomeLocation(latitude: Double, longitude: Double) {
        checkSupported()
        require(isValid(latitude, longitude)) { "Bad coordinates" }
        homeLocation = latitude to longitude
        val buf = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.put(0x24.toByte()).put(0x00).put(0x01).putDouble(latitude).putDouble(longitude).put(0x04)
        RawSettingIO.write(buf.array().map { it.toInt() and 0xFF }.toIntArray())
    }

    // ---- Sound mode (0x42) ----

    /** Raw sound packet as hex, for mapping the per-function CUSTOM bits. */
    suspend fun getSoundPacketHex(): String = readChecked(SOUND_MODE_CODE).toHex()

    suspend fun getSoundMode(): SoundMode? = SoundMode.fromCode(readChecked(SOUND_MODE_CODE)[1])

    /** Sets the mode (no write if already equal). Returns the mode the watch had before. */
    suspend fun setSoundMode(mode: SoundMode): SoundMode? =
        modifyChecked(SOUND_MODE_CODE) { p -> p.also { it[1] = mode.code } }
            .let { SoundMode.fromCode(it[1]) }
            .also { if (it == mode) Timber.i("Sound mode already $mode, not writing") }

    // ---- Watch face (0x51) ----

    /** Returns null if the four item bytes disagree or hold an unknown code. */
    suspend fun getWatchFaceItem(): WatchFaceItem? = getWatchFaceCode()?.let { WatchFaceItem.fromCode(it) }

    /** Raw display-item code (also for values not yet in [WatchFaceItem]); null if the four bytes disagree. */
    suspend fun getWatchFaceCode(): Int? = itemCode(readChecked(WATCH_FACE_CODE))

    private fun itemCode(p: IntArray): Int? = p.copyOfRange(2, PACKET_SIZE).distinct().singleOrNull()

    /** Watch face: byte 1 of 0x51, 1 = simple face (weekday), 2 = customisable face (item). */
    suspend fun getWatchFaceNumber(): Int = readChecked(WATCH_FACE_CODE)[1]

    suspend fun setWatchFaceNumber(face: Int) {
        require(face == 1 || face == 2) { "face must be 1 or 2" }
        modifyChecked(WATCH_FACE_CODE) { p -> p.also { it[1] = face } }
    }

    /** Sets the display item (no write if already equal). Returns the item the watch had before. */
    suspend fun setWatchFaceItem(item: WatchFaceItem): WatchFaceItem? =
        modifyChecked(WATCH_FACE_CODE) { p -> p.also { for (i in 2 until PACKET_SIZE) it[i] = item.code } }
            .let { before -> itemCode(before)?.let { WatchFaceItem.fromCode(it) } }

    // ---- Smart alarm (0x50) ----

    suspend fun getSmartAlarm(): SmartAlarm =
        readChecked(SMART_ALARM_CODE, SMART_ALARM_SIZE).let { p ->
            SmartAlarm(p[1], p[2], p[3] and 0x01 != 0, p[3] and 0x02 != 0, p[4])
        }

    /** Writes the smart-alarm slot; other flag bits read from the watch are kept. */
    suspend fun setSmartAlarm(a: SmartAlarm) {
        require(a.hour in 0..23 && a.minute in 0..59 && a.windowMin in 0..120) { "Bad smart alarm" }
        modifyChecked(SMART_ALARM_CODE, SMART_ALARM_SIZE) { p ->
            var flags = p[3] and 0x03.inv()
            if (a.enabled) flags = flags or 0x01
            if (a.snooze) flags = flags or 0x02
            intArrayOf(SMART_ALARM_CODE, a.hour, a.minute, flags, a.windowMin)
        }
    }

    // ---- Heart-rate monitor (0x41) ----

    /** Raw monitoring condition (bits 0-1 of byte 1 of 0x41), also for values not in [HeartRateMonitor]. */
    suspend fun getHeartRateMonitorCode(): Int = readChecked(HEART_RATE_MONITOR_CODE, HEART_RATE_MONITOR_SIZE)[1] and 0x03

    suspend fun getHeartRateMonitorHex(): String = readChecked(HEART_RATE_MONITOR_CODE, HEART_RATE_MONITOR_SIZE).toHex()

    /** Sets only the monitoring condition; alert bits, target and limits are kept. Returns the condition before. */
    suspend fun setHeartRateMonitor(mode: HeartRateMonitor): Int =
        modifyChecked(HEART_RATE_MONITOR_CODE, HEART_RATE_MONITOR_SIZE) { p ->
            p.copyOf().also { it[1] = (it[1] and 0x03.inv()) or mode.code }
        }[1] and 0x03

    // ---- Step goal (0x43) ----

    const val TARGET_CODE = 0x43
    private const val TARGET_SIZE = 17

    suspend fun getStepGoal(): org.avmedia.gshockapi.model.StepGoal =
        readChecked(TARGET_CODE, TARGET_SIZE).let { p ->
            org.avmedia.gshockapi.model.StepGoal(p[1] or (p[2] shl 8) or (p[3] shl 16), p[16] and 0x01 != 0)
        }

    suspend fun setStepGoal(g: org.avmedia.gshockapi.model.StepGoal) {
        require(g.steps in 1..99_999) { "step goal out of range" }
        modifyChecked(TARGET_CODE, TARGET_SIZE) { p ->
            p.copyOf().also {
                it[1] = g.steps and 0xFF; it[2] = (g.steps shr 8) and 0xFF; it[3] = (g.steps shr 16) and 0xFF
                it[16] = if (g.notifyWhenReached) it[16] or 0x01 else it[16] and 0x01.inv()
            }
        }
    }

    // ---- Physical settings (0x4F): sleep time, max / resting HR, VO2max ----

    const val PHYSICAL_CODE = 0x4F
    private const val PHYSICAL_SIZE = 10

    suspend fun getPhysical(): org.avmedia.gshockapi.model.H5600Physical =
        readChecked(PHYSICAL_CODE, PHYSICAL_SIZE).let { p ->
            org.avmedia.gshockapi.model.H5600Physical(
                p[2], p[3], p[4], p[5], p[7], p[6] and 0x01 != 0, p[8], p[6] and 0x02 != 0, p[9],
            )
        }

    /** Writes sleep time and the HR values; the "entered by user" flags follow the *Manual fields, VO2max kept. */
    suspend fun setPhysical(v: org.avmedia.gshockapi.model.H5600Physical) {
        require(v.sleepStartHour in 0..23 && v.wakeHour in 0..23 && v.sleepStartMin in 0..59 && v.wakeMin in 0..59) { "bad sleep time" }
        require(v.maxHr in 100..230 && v.restingHr in 30..120) { "bad heart rate" }
        modifyChecked(PHYSICAL_CODE, PHYSICAL_SIZE) { p ->
            p.copyOf().also {
                it[2] = v.sleepStartHour; it[3] = v.sleepStartMin; it[4] = v.wakeHour; it[5] = v.wakeMin
                var f = it[6] and 0x03.inv()
                if (v.maxHrManual) f = f or 0x01
                if (v.restingHrManual) f = f or 0x02
                it[6] = f; it[7] = v.maxHr; it[8] = v.restingHr
            }
        }
    }

    // ---- Watch mode list (0x52) ----

    const val MODE_LIST_CODE = 0x52
    private const val MODE_LIST_SIZE = 20

    /** Mode ids in button order, from byte 1 (01 = Time & date first); 0 bytes = end of list. */
    suspend fun getModeList(): List<Int> =
        readChecked(MODE_LIST_CODE, MODE_LIST_SIZE).drop(1).takeWhile { it != 0 }

    suspend fun setModeList(modes: List<Int>) {
        require(modes.isNotEmpty() && modes.size <= MODE_LIST_SIZE - 1) { "1-${MODE_LIST_SIZE - 1} modes" }
        require(modes.all { it in 1..0xFF } && modes.distinct().size == modes.size) { "bad mode list" }
        modifyChecked(MODE_LIST_CODE, MODE_LIST_SIZE) { p ->
            IntArray(MODE_LIST_SIZE).also {
                it[0] = MODE_LIST_CODE
                modes.forEachIndexed { i, m -> it[1 + i] = m }
            }
        }
    }

    // ---- Basic settings restore (0x13) ----

    /**
     * Writes a full 17-byte 0x13 (basic settings) packet as-is. Intended only for restoring a
     * packet previously read from this same watch (e.g. after stock Smart Sync zeroed bytes 12-16).
     */
    fun restoreBasicSettings(packet: IntArray) {
        checkSupported()
        check(packet.size == 17 && packet[0] == 0x13) { "Not a 17-byte 0x13 packet" }
        RawSettingIO.write(packet)
        SettingsIO.rememberRaw(Utils.byteArrayOfIntArray(packet))
    }
}
