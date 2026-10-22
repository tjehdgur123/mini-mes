package com.minimes.lot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Track-in / Track-out 요청·응답 */
public final class TrackDtos {

    private TrackDtos() {
    }

    /**
     * @param requestKey 설비/단말이 요청마다 새로 만드는 멱등키(UUID 등). 재전송 시에는 같은 값을 보낸다.
     */
    public record TrackInRequest(
            @NotBlank String equipmentCode,
            @Size(max = 20) String operatorId,
            @NotBlank @Size(max = 64) String requestKey) {
    }

    public record TrackInResponse(
            String lotNo,
            int seq,
            String processCode,
            String processName,
            String equipmentCode,
            long inQty,
            LocalDateTime trackInAt,
            boolean replayed) {
    }

    public record DefectInput(
            @NotBlank String defectCode,
            @NotNull @Positive Long qty) {
    }

    public record TrackOutRequest(
            @NotNull @PositiveOrZero Long goodQty,
            @Valid List<DefectInput> defects,
            @NotBlank @Size(max = 64) String requestKey) {

        public List<DefectInput> defectsOrEmpty() {
            return defects == null ? List.of() : defects;
        }
    }

    public record TrackOutResponse(
            String lotNo,
            int seq,
            String processCode,
            long inQty,
            long goodQty,
            long defectQty,
            BigDecimal yieldPct,
            String lotStatus,
            String holdReason,
            String nextProcessCode,
            boolean replayed) {
    }

    public record ReleaseHoldRequest(
            @NotBlank @Size(max = 20) String operatorId,
            @NotBlank @Size(max = 150) String reason) {
    }

    public record LotStatusResponse(String lotNo, String lotStatus, Integer currentSeq, long qty) {
    }
}
