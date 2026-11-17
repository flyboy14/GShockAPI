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

/** DW-H5600 date order on the watch: 0x13 byte 4 (CASIO DateOrder enum). */
enum class DateOrder(val code: Int, val label: String) {
    YMD(0, "Y-M-D"), YDM(1, "Y-D-M"), MDY(2, "M-D-Y"), DMY(3, "D-M-Y");

    companion object {
        fun fromCode(code: Int): DateOrder? = entries.firstOrNull { it.code == code }
    }
}

/** DW-H5600 weekday language: 0x13 byte 5 (CASIO WeekLanguage enum). */
enum class WeekLanguage(val code: Int, val label: String) {
    ENGLISH(0, "English"), SPANISH(1, "Spanish"), FRENCH(2, "French"),
    GERMAN(3, "German"), ITALIAN(4, "Italian"), RUSSIAN(5, "Russian");

    companion object {
        fun fromCode(code: Int): WeekLanguage? = entries.firstOrNull { it.code == code }
    }
}

/**
 * DW-H5600 display / night-time fields of the basic settings packet 0x13 (offsets from the CASIO
 * app's WatchSettingForBasicData, packet index = data index + 1):
 * byte 4 date order, byte 5 weekday language, byte 8 bit 0x20 font (0 Squad, 1 7-segment),
 * bytes 13-16 night time start h, m, end h, m (binary).
 */
data class H5600Basic(
    val dateOrder: Int,
    val weekLanguage: Int,
    val sevenSegmentFont: Boolean,
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

/**
 * DW-H5600 heart-rate zone alert, inside packet 0x41 (CASIO WatchSettingForHeartRateMonitorData):
 * byte 1 bit 0x10 alert on, bit 0x04 notify when back in the zone, bit 0x08 set = custom range instead
 * of a zone; byte 2 = zone 1-5.
 */
data class HeartRateZoneAlert(
    val enabled: Boolean,
    val notifyInRange: Boolean,
    val zone: Int,
)

/** DW-H5600 targets, packet 0x43 (CASIO WatchSettingForTargetValueData): bytes 1-3 daily steps (LE), byte 16 bit 0x01 goal notification. */
data class StepGoal(val steps: Int, val notifyWhenReached: Boolean)
