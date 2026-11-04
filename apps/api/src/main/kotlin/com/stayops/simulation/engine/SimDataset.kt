package com.stayops.simulation.engine

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant
import java.time.LocalDate

/**
 * P10 생성기 출력(`gen-v1`)의 시뮬레이션 입력 모델 (docs/simulation/DATASET.md).
 * 엔진이 쓰는 필드만 정의하고 나머지는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SimDataset(
    val metadata: Metadata,
    val property: SimProperty,
    val rooms: List<SimRoom>,
    val staff: List<SimStaff>,
    val shifts: List<SimShift>,
    val reservations: List<SimReservation>,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Metadata(
        val synthetic: Boolean,
        val generatorVersion: String,
        val seed: Long,
        val configHash: String,
        val horizon: Horizon,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Horizon(val startDate: LocalDate, val days: Int, val timezone: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SimProperty(val name: String, val timezone: String, val operationalBufferMinutes: Int)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SimRoom(val roomId: String, val roomNumber: String, val roomType: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SimStaff(val staffId: String, val displayName: String, val role: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SimShift(val staffId: String, val startsAt: Instant, val endsAt: Instant, val breakMinutes: Int)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SimReservation(
        val reservationId: String,
        val roomId: String,
        val roomNumber: String,
        val scheduledCheckInAt: Instant,
        val scheduledCheckOutAt: Instant,
        val realization: Realization,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Realization(
        val checkoutCleaningMinutes: Int,
        val inspectionMinutes: Int,
        val inspectionFails: Boolean,
        val reCleaningMinutes: Int?,
        val reInspectionMinutes: Int?,
        val linenWaitMinutes: Int,
        val maintenanceBlockMinutes: Int,
        val stayoverRequests: List<StayoverRequest>,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StayoverRequest(val date: LocalDate, val minutes: Int)

    /** 엔진이 받아들이는 데이터셋인지. 위반 목록(비어 있으면 통과). */
    fun problems(): List<String> = buildList {
        if (!metadata.synthetic) add("metadata.synthetic must be true (only synthetic datasets are accepted)")
        if (metadata.generatorVersion !in SUPPORTED_GENERATORS) add("unsupported generatorVersion ${metadata.generatorVersion}")
        if (metadata.horizon.days !in 1..MAX_DAYS) add("horizon.days must be 1..$MAX_DAYS")
        if (rooms.isEmpty()) add("rooms must not be empty")
        if (rooms.size > MAX_ROOMS) add("too many rooms (max $MAX_ROOMS)")
        val roomIds = rooms.map { it.roomId }.toSet()
        if (reservations.any { it.roomId !in roomIds }) add("reservation references an unknown room")
        if (reservations.any { !it.scheduledCheckInAt.isBefore(it.scheduledCheckOutAt) }) add("reservation check-in must be before check-out")
        val staffIds = staff.map { it.staffId }.toSet()
        if (shifts.any { it.staffId !in staffIds }) add("shift references unknown staff")
        if (staff.any { it.role != "HOUSEKEEPER" && it.role != "INSPECTOR" }) add("staff role must be HOUSEKEEPER or INSPECTOR")
        val r = reservations.map { it.realization }
        if (r.any { it.checkoutCleaningMinutes <= 0 || it.inspectionMinutes <= 0 || it.linenWaitMinutes < 0 || it.maintenanceBlockMinutes < 0 }) {
            add("realization durations must be positive")
        }
        if (r.any { it.inspectionFails && (it.reCleaningMinutes == null || it.reInspectionMinutes == null) }) {
            add("failed inspections need re-cleaning and re-inspection minutes")
        }
    }

    companion object {
        val SUPPORTED_GENERATORS = setOf("gen-v1")
        const val MAX_DAYS = 31
        const val MAX_ROOMS = 500
    }
}
