package com.minimes.lot;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.minimes.common.MesException;
import com.minimes.lot.LotQueryRepository.DefectView;
import com.minimes.lot.LotQueryRepository.HistoryView;
import com.minimes.lot.LotQueryRepository.LotInfo;
import com.minimes.lot.LotQueryRepository.ProcessYield;
import com.minimes.lot.LotQueryRepository.TraceNode;
import com.minimes.lot.LotQueryRepository.WipRow;
import com.minimes.lot.TrackDtos.LotStatusResponse;
import com.minimes.lot.TrackDtos.ReleaseHoldRequest;
import com.minimes.lot.TrackDtos.TrackInRequest;
import com.minimes.lot.TrackDtos.TrackInResponse;
import com.minimes.lot.TrackDtos.TrackOutRequest;
import com.minimes.lot.TrackDtos.TrackOutResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class LotController {

    private final TrackService trackService;
    private final LotQueryRepository query;

    public LotController(TrackService trackService, LotQueryRepository query) {
        this.trackService = trackService;
        this.query = query;
    }

    // ------------------------------------------------------------------ 실적

    @PostMapping("/lots/{lotNo}/track-in")
    public TrackInResponse trackIn(@PathVariable String lotNo, @Valid @RequestBody TrackInRequest req) {
        return trackService.trackIn(lotNo, req);
    }

    @PostMapping("/lots/{lotNo}/track-out")
    public TrackOutResponse trackOut(@PathVariable String lotNo, @Valid @RequestBody TrackOutRequest req) {
        return trackService.trackOut(lotNo, req);
    }

    @PostMapping("/lots/{lotNo}/release-hold")
    public LotStatusResponse releaseHold(@PathVariable String lotNo, @Valid @RequestBody ReleaseHoldRequest req) {
        return trackService.releaseHold(lotNo, req);
    }

    // ------------------------------------------------------------------ 조회

    public record HistoryWithDefects(HistoryView history, List<DefectView> defects) {
    }

    public record LotDetail(LotInfo lot, List<HistoryWithDefects> histories) {
    }

    @GetMapping("/lots/{lotNo}")
    public LotDetail lot(@PathVariable String lotNo) {
        LotInfo lot = query.findLot(lotNo)
                .orElseThrow(() -> MesException.notFound("LOT_NOT_FOUND", "LOT이 없습니다: " + lotNo));
        List<HistoryView> histories = query.findLineageHistory(lotNo);
        Map<Long, List<DefectView>> defects = query
                .findDefects(histories.stream().map(HistoryView::historyId).toList())
                .stream().collect(Collectors.groupingBy(DefectView::historyId));
        return new LotDetail(lot, histories.stream()
                .map(h -> new HistoryWithDefects(h, defects.getOrDefault(h.historyId(), List.of())))
                .toList());
    }

    /** direction=backward(기본): 투입된 원자재/부모 LOT, forward: 이 LOT이 들어간 LOT */
    @GetMapping("/lots/{lotNo}/trace")
    public List<TraceNode> trace(@PathVariable String lotNo,
                                 @RequestParam(defaultValue = "backward") String direction) {
        query.findLot(lotNo).orElseThrow(() -> MesException.notFound("LOT_NOT_FOUND", "LOT이 없습니다: " + lotNo));
        return switch (direction) {
            case "backward" -> query.traceBackward(lotNo);
            case "forward" -> query.traceForward(lotNo);
            default -> throw MesException.badRequest("INVALID_DIRECTION", "direction은 backward 또는 forward 입니다.");
        };
    }

    @GetMapping("/wip")
    public List<WipRow> wip() {
        return query.findWip();
    }

    @GetMapping("/reports/process-yield")
    public List<ProcessYield> processYield() {
        return query.findProcessYield();
    }
}
