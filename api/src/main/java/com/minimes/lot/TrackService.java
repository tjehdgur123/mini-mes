package com.minimes.lot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.minimes.common.MesException;
import com.minimes.lot.TrackDtos.DefectInput;
import com.minimes.lot.TrackDtos.LotStatusResponse;
import com.minimes.lot.TrackDtos.ReleaseHoldRequest;
import com.minimes.lot.TrackDtos.TrackInRequest;
import com.minimes.lot.TrackDtos.TrackInResponse;
import com.minimes.lot.TrackDtos.TrackOutRequest;
import com.minimes.lot.TrackDtos.TrackOutResponse;
import com.minimes.lot.TrackRepository.DefectCodeRow;
import com.minimes.lot.TrackRepository.EquipmentRow;
import com.minimes.lot.TrackRepository.HistoryRow;
import com.minimes.lot.TrackRepository.LotRow;
import com.minimes.lot.TrackRepository.YieldStat;

/**
 * 공정 실적 처리의 핵심.
 *
 * <p>모든 변경은 하나의 트랜잭션 안에서 다음 순서를 지킨다.
 * <ol>
 *   <li>LOT 행 잠금 (같은 LOT 요청 직렬화)</li>
 *   <li>멱등키 확인 (잠금 이후에 확인해야 동시 재전송도 정확히 걸러짐)</li>
 *   <li>업무 규칙 검증 (상태, 라우팅 순서, 설비-공정 일치, 수량)</li>
 *   <li>설비 행 잠금 (항상 LOT → 설비 순서로 잠가 데드락 방지)</li>
 *   <li>이력/LOT/설비/작업지시 갱신</li>
 * </ol>
 */
@Service
public class TrackService {

    private static final Logger log = LoggerFactory.getLogger(TrackService.class);

    private final TrackRepository repo;
    private final BigDecimal yieldDropThreshold;

    public TrackService(TrackRepository repo,
                        @Value("${mes.hold.yield-drop-threshold:0.01}") BigDecimal yieldDropThreshold) {
        this.repo = repo;
        this.yieldDropThreshold = yieldDropThreshold;
    }

    // =====================================================================
    // Track-in
    // =====================================================================

    @Transactional
    public TrackInResponse trackIn(String lotNo, TrackInRequest req) {
        LotRow lot = lockLotOrThrow(lotNo);

        // 재전송된 요청이면 이전 결과를 그대로 돌려준다 (이중 투입 방지)
        Optional<HistoryRow> replay = repo.findHistoryByInKey(req.requestKey());
        if (replay.isPresent()) {
            HistoryRow h = replay.get();
            requireSameLot(h, lotNo, req.requestKey());
            log.info("Track-in 재전송 감지: lot={}, key={}", lotNo, req.requestKey());
            return toTrackInResponse(h, true);
        }

        if (!"WAIT".equals(lot.lotStatus())) {
            throw MesException.conflict("INVALID_LOT_STATUS",
                    "Track-in은 WAIT 상태에서만 가능합니다. 현재 상태: " + lot.lotStatus()
                            + (lot.holdReason() != null ? " (" + lot.holdReason() + ")" : ""));
        }

        int nextSeq = lot.currentSeq() + 1;
        String processCode = repo.findRoutingProcess(lot.itemCode(), nextSeq)
                .orElseThrow(() -> MesException.conflict("ROUTING_END",
                        "더 진행할 공정이 없습니다. lot=" + lotNo + ", 마지막 공정 순번=" + lot.currentSeq()));

        EquipmentRow eq = repo.lockEquipment(req.equipmentCode())
                .orElseThrow(() -> MesException.notFound("EQUIPMENT_NOT_FOUND",
                        "설비가 없습니다: " + req.equipmentCode()));
        if (!processCode.equals(eq.processCode())) {
            throw MesException.conflict("EQUIPMENT_PROCESS_MISMATCH",
                    "%s 설비는 %s 공정 설비입니다. 이 LOT의 다음 공정은 %s 입니다."
                            .formatted(eq.equipmentCode(), eq.processCode(), processCode));
        }
        if (!"IDLE".equals(eq.status())) {
            throw MesException.conflict("EQUIPMENT_NOT_AVAILABLE",
                    "설비가 투입 가능한 상태가 아닙니다: " + eq.equipmentCode() + " (" + eq.status() + ")");
        }

        long historyId = repo.insertHistory(lotNo, nextSeq, processCode, eq.equipmentCode(),
                req.operatorId(), lot.qty(), req.requestKey());
        repo.updateLotToRun(lotNo, nextSeq, lot.version());
        repo.updateEquipmentStatus(eq.equipmentCode(), "RUN");
        repo.markWorkOrderInProgress(lot.woNo());

        log.info("Track-in: lot={}, seq={}, process={}, equipment={}, qty={}, historyId={}",
                lotNo, nextSeq, processCode, eq.equipmentCode(), lot.qty(), historyId);

        HistoryRow saved = repo.findHistoryByInKey(req.requestKey()).orElseThrow();
        return toTrackInResponse(saved, false);
    }

    // =====================================================================
    // Track-out
    // =====================================================================

    @Transactional
    public TrackOutResponse trackOut(String lotNo, TrackOutRequest req) {
        LotRow lot = lockLotOrThrow(lotNo);

        Optional<HistoryRow> replay = repo.findHistoryByOutKey(req.requestKey());
        if (replay.isPresent()) {
            HistoryRow h = replay.get();
            requireSameLot(h, lotNo, req.requestKey());
            log.info("Track-out 재전송 감지: lot={}, key={}", lotNo, req.requestKey());
            return toTrackOutResponse(h, lot, lot.lotStatus(), lot.holdReason(), true);
        }

        if (!"RUN".equals(lot.lotStatus())) {
            throw MesException.conflict("INVALID_LOT_STATUS",
                    "Track-out은 RUN 상태에서만 가능합니다. 현재 상태: " + lot.lotStatus());
        }

        HistoryRow h = repo.findOpenHistory(lotNo, lot.currentSeq())
                .orElseThrow(() -> new IllegalStateException(
                        "RUN 상태인데 진행 중 공정 이력이 없습니다. lot=" + lotNo + " (데이터 정합성 점검 필요)"));

        List<DefectInput> defects = req.defectsOrEmpty();
        validateDefects(defects, h.processCode());

        long defectQty = defects.stream().mapToLong(DefectInput::qty).sum();
        long goodQty = req.goodQty();
        if (goodQty + defectQty != h.inQty()) {
            throw MesException.badRequest("QTY_MISMATCH",
                    "양품(%d) + 불량(%d) = %d 이 투입 수량(%d)과 다릅니다."
                            .formatted(goodQty, defectQty, goodQty + defectQty, h.inQty()));
        }

        repo.closeHistory(h.historyId(), goodQty, defectQty, req.requestKey());
        for (DefectInput d : defects) {
            repo.insertDefect(h.historyId(), d.defectCode(), d.qty());
        }

        // 다음 상태 결정: 마지막 공정이면 FINISHED, 아니면 WAIT. 단, 수율 이상이면 HOLD가 우선
        boolean lastProcess = repo.findRoutingProcess(lot.itemCode(), h.seq() + 1).isEmpty();
        String newStatus = lastProcess ? "FINISHED" : "WAIT";
        String holdReason = detectYieldAnomaly(h, goodQty).orElse(null);
        if (holdReason != null) {
            newStatus = "HOLD";
            log.warn("자동 HOLD: lot={}, {}", lotNo, holdReason);
        }

        repo.updateLotAfterTrackOut(lotNo, goodQty, newStatus, holdReason, lot.version());

        // LOT → 설비 순서로 잠금 (Track-in과 같은 순서)
        repo.lockEquipment(h.equipmentCode()).orElseThrow();
        repo.updateEquipmentStatus(h.equipmentCode(), "IDLE");

        if ("FINISHED".equals(newStatus)) {
            repo.completeWorkOrderIfDone(lot.woNo());
        }

        log.info("Track-out: lot={}, seq={}, in={}, good={}, defect={}, status={}",
                lotNo, h.seq(), h.inQty(), goodQty, defectQty, newStatus);

        HistoryRow closed = repo.findHistoryByOutKey(req.requestKey()).orElseThrow();
        return toTrackOutResponse(closed, lot, newStatus, holdReason, false);
    }

    // =====================================================================
    // HOLD 해제
    // =====================================================================

    @Transactional
    public LotStatusResponse releaseHold(String lotNo, ReleaseHoldRequest req) {
        LotRow lot = lockLotOrThrow(lotNo);
        if (!"HOLD".equals(lot.lotStatus())) {
            throw MesException.conflict("INVALID_LOT_STATUS", "HOLD 상태가 아닙니다. 현재 상태: " + lot.lotStatus());
        }
        boolean lastProcessDone = repo.findRoutingProcess(lot.itemCode(), lot.currentSeq() + 1).isEmpty();
        String newStatus = lastProcessDone ? "FINISHED" : "WAIT";
        repo.releaseHold(lotNo, newStatus, lot.version());
        if ("FINISHED".equals(newStatus)) {
            repo.completeWorkOrderIfDone(lot.woNo());
        }
        log.info("HOLD 해제: lot={}, operator={}, 사유={}, 이전 HOLD 사유={}",
                lotNo, req.operatorId(), req.reason(), lot.holdReason());
        return new LotStatusResponse(lotNo, newStatus, lot.currentSeq(), lot.qty());
    }

    // =====================================================================
    // 내부 로직
    // =====================================================================

    private LotRow lockLotOrThrow(String lotNo) {
        LotRow lot = repo.lockLot(lotNo)
                .orElseThrow(() -> MesException.notFound("LOT_NOT_FOUND", "LOT이 없습니다: " + lotNo));
        if (lot.currentSeq() == null) {
            throw MesException.conflict("NOT_PRODUCTION_LOT", "원자재 LOT은 공정 실적을 등록할 수 없습니다: " + lotNo);
        }
        return lot;
    }

    private static void requireSameLot(HistoryRow h, String lotNo, String key) {
        if (!h.lotNo().equals(lotNo)) {
            throw MesException.conflict("REQUEST_KEY_CONFLICT",
                    "요청키 " + key + " 는 이미 다른 LOT(" + h.lotNo() + ")에 사용되었습니다.");
        }
    }

    /** 불량 코드 존재 여부, 공정 일치, 코드 중복 검사 */
    private void validateDefects(List<DefectInput> defects, String processCode) {
        Set<String> seen = new HashSet<>();
        for (DefectInput d : defects) {
            if (!seen.add(d.defectCode())) {
                throw MesException.badRequest("DUPLICATE_DEFECT_CODE", "불량 코드가 중복되었습니다: " + d.defectCode());
            }
        }
        Map<String, DefectCodeRow> master = repo.findDefectCodes(seen).stream()
                .collect(Collectors.toMap(DefectCodeRow::defectCode, Function.identity()));
        for (String code : seen) {
            DefectCodeRow row = master.get(code);
            if (row == null) {
                throw MesException.badRequest("DEFECT_CODE_NOT_FOUND", "등록되지 않은 불량 코드입니다: " + code);
            }
            if (row.processCode() != null && !row.processCode().equals(processCode)) {
                throw MesException.badRequest("DEFECT_CODE_PROCESS_MISMATCH",
                        "%s 는 %s 공정 불량 코드입니다. 현재 공정: %s".formatted(code, row.processCode(), processCode));
            }
        }
    }

    /**
     * 같은 공정의 다른 LOT 누적 수율 대비 이번 LOT 수율이 기준 이상 낮으면 HOLD 사유를 돌려준다.
     * 자기 자신을 평균에 넣으면 이상치가 평균을 끌어내리므로 "나머지 LOT"과 비교한다.
     */
    private Optional<String> detectYieldAnomaly(HistoryRow h, long goodQty) {
        YieldStat others = repo.othersYield(h.processCode(), h.historyId());
        if (others.sumIn() == 0) {
            return Optional.empty(); // 비교 대상이 없으면 판단 보류
        }
        BigDecimal lotYield = ratio(goodQty, h.inQty());
        BigDecimal othersYield = ratio(others.sumGood(), others.sumIn());
        if (lotYield.compareTo(othersYield.subtract(yieldDropThreshold)) < 0) {
            return Optional.of("%s 공정 수율 %s%% - 동일 공정 다른 LOT 평균 %s%% 대비 %s%%p 하락".formatted(
                    h.processCode(), pct(lotYield), pct(othersYield), pct(othersYield.subtract(lotYield))));
        }
        return Optional.empty();
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal ratio) {
        return ratio.movePointRight(2).setScale(2, RoundingMode.HALF_UP);
    }

    private TrackInResponse toTrackInResponse(HistoryRow h, boolean replayed) {
        return new TrackInResponse(h.lotNo(), h.seq(), h.processCode(), repo.findProcessName(h.processCode()),
                h.equipmentCode(), h.inQty(), h.trackInAt(), replayed);
    }

    private TrackOutResponse toTrackOutResponse(HistoryRow h, LotRow lot, String status, String holdReason,
                                                boolean replayed) {
        String next = "WAIT".equals(status)
                ? repo.findRoutingProcess(lot.itemCode(), h.seq() + 1).orElse(null)
                : null;
        return new TrackOutResponse(h.lotNo(), h.seq(), h.processCode(), h.inQty(), h.goodQty(), h.defectQty(),
                pct(ratio(h.goodQty(), h.inQty())), status, holdReason, next, replayed);
    }
}
