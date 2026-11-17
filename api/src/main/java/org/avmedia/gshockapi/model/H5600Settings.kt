package org.avmedia.gshockapi.model

/** DW-H5600 sound / notification mode: byte[1] of packet 0x42. */
enum class SoundMode(val code: Int) {
    ALL_OFF(0x00),
    SOUND_ONLY(0x02),
    CUSTOM(0x05),
    VIBE_ONLY(0x08),
    ALL_ON(0x0A), // all five confirmed on the user's watch 2026-11-16
    ;

    companion object {
        fun fromCode(code: Int): SoundMode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * DW-H5600 smart alarm (packet 0x50, one slot): `50 <hour> <minute> <flags> <window>`.
 * flags bit 0x01 = on, 0x02 = snooze; window = minutes before (15/30/45/60). Decoded 2026-11-16.
 */
data class SmartAlarm(
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val snooze: Boolean,
    val windowMin: Int,
)

/** DW-H5600 watch-face display item: bytes[2..5] of packet 0x51 (codes from the official app, 2026-11-16). */
enum class WatchFaceItem(val code: Int) {
    STEPS(0x00),
    HEART_RATE(0x0A),
    HEART_RATE_MIN_MAX(0x0B),
    SLEEP(0x0E),
    SUNRISE_SUNSET(0x12),
    MOON(0x13),
    ;

    companion object {
        fun fromCode(code: Int): WatchFaceItem? = entries.firstOrNull { it.code == code }
    }
}

/**
 * DW-H5600 heart-rate monitor condition: bits 0-1 of byte 1 of packet 0x41. Names and values from the
 * CASIO WATCHES app (Dart enum HeartRateMonitoringCondition: Off 0, RunActOnly 1, AlwaysOn 2).
 * All day = daytime HR, calories, sleep evaluation (high battery use); activities + night = moderate.
 */
enum class HeartRateMonitor(val code: Int) {
    OFF(0x00),
    ACTIVITY_AND_NIGHT(0x01),
    ALL_DAY(0x02),
    ;

    companion object {
        fun fromCode(code: Int): HeartRateMonitor? = entries.firstOrNull { it.code == code }
    }
}

/**
 * DW-H5600 night time in the basic settings packet 0x13, bytes 13-16: start h, m, end h, m (binary;
 * CASIO WatchSettingForBasicData). The CASIO app also has date order (byte 4), weekday language
 * (byte 5) and font (byte 8 bit 0x20) there, but the DW-H5600 does not keep them (tested 2026-11-17).
 */
data class H5600Basic(
    /** Step (inactivity) reminder: byte 12 bit 0x02 (CASIO `stepReminder`; kept by the watch, tested 2026-11-17). */
    val stepReminder: Boolean,
    val nightStartHour: Int,
    val nightStartMin: Int,
    val nightEndHour: Int,
    val nightEndMin: Int,
)

/**
 * DW-H5600 physical settings, packet 0x4F (CASIO WatchPhysicalSettingsData):
 * byte 1 training background, bytes 2-5 preferred sleep onset h, m / wake-up h, m,
 * byte 6 flags (0x01 max HR, 0x02 resting HR, 0x04 VO2max entered by the user), byte 7 max HR,
 * byte 8 resting HR, byte 9 VO2max.
 */
data class H5600Physical(
    val sleepStartHour: Int,
    val sleepStartMin: Int,
    val wakeHour: Int,
    val wakeMin: Int,
    val maxHr: Int,
    val maxHrManual: Boolean,
    val restingHr: Int,
    val restingHrManual: Boolean,
    val vo2Max: Int,
)

/** DW-H5600 targets, packet 0x43 (CASIO WatchSettingForTargetValueData): bytes 1-3 daily steps (LE), byte 16 bit 0x01 goal notification. */
data class StepGoal(val steps: Int, val notifyWhenReached: Boolean)

/**
 * DW-H5600 watch modes in button order, packet 0x52 (CASIO WIDGET_LIST): `52 <mode ids> 00…` (first = 01 Time & date),
 * 20 bytes. A mode left out of the list is hidden on the watch (tested 2026-11-17).
 */
enum class WatchMode(val code: Int, val label: String) {
    TIME_AND_DATE(0x01, "Time & date"),
    HEART_RATE(0x02, "Heart rate"),
    LIFE_LOG(0x03, "Life log"),
    STRESS(0x04, "Stress"),
    NIGHTLY_RECHARGE(0x05, "Nightly recharge"),
    ACTIVITY_LOG(0x06, "Activity log"),
    CARDIO_LOAD(0x07, "Cardio load"),
    BREATHING(0x08, "Breathing exercise"),
    NOTIFICATIONS(0x09, "Notifications"),
    BLOOD_OXYGEN(0x0A, "Blood oxygen"),
    ALTIMETER(0x0B, "Altimeter"),
    BAROMETER(0x0C, "Barometer"),
    COMPASS(0x0D, "Compass"),
    STOPWATCH(0x0E, "Stopwatch"),
    TIMER(0x0F, "Timer"),
    WORLD_TIME(0x10, "World time"),
    ALMANAC(0x11, "Sun & moon"),
    QR(0x12, "QR code"),
    TIDE(0x13, "Tide"),
    ;

    companion object {
        fun fromCode(code: Int): WatchMode? = entries.firstOrNull { it.code == code }
    }
}
