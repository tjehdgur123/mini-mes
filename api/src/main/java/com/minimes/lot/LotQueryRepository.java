package com.minimes.lot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 조회 전용 SQL (db/03_queries.sql 의 쿼리를 API로 노출) */
@Repository
public class LotQueryRepository {

    private final JdbcClient jdbc;

    public LotQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record LotInfo(String lotNo, String itemCode, String itemName, String woNo, long qty,
                          Integer currentSeq, String lotStatus, String holdReason,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record HistoryView(long historyId, String lotNo, int seq, String processCode, String processName,
                              String equipmentCode, String operatorId, long inQty, Long goodQty, Long defectQty,
                              LocalDateTime trackInAt, LocalDateTime trackOutAt) {
    }

    public record DefectView(long historyId, String defectCode, String defectName, long qty) {
    }

    public record TraceNode(int depth, String lotNo, String relationType, BigDecimal linkQty,
                            String itemCode, String itemName, String lotStatus, BigDecimal currentQty) {
    }

    public record WipRow(String lotNo, String woNo, String itemCode, String lotStatus, long qty, Integer currentSeq,
                         String currentProcessCode, String currentProcess, String nextProcessCode, String nextProcess,
                         String holdReason, LocalDateTime updatedAt) {
    }

    public record ProcessYield(String itemCode, int seq, String processCode, String processName,
                               long inQty, long goodQty, BigDecimal yieldPct, BigDecimal cumulativeRtyPct) {
    }

    public Optional<LotInfo> findLot(String lotNo) {
        return jdbc.sql("""
                        SELECT l.lot_no, l.item_code, i.item_name, l.wo_no, l.qty, l.current_seq,
                               l.lot_status, l.hold_reason, l.created_at, l.updated_at
                        FROM   lot l JOIN item i ON i.item_code = l.item_code
                        WHERE  l.lot_no = :lotNo
                        """)
                .param("lotNo", lotNo)
                .query(LotInfo.class)
                .optional();
    }

    /** 자기 자신 + 분할 이전 조상 LOT의 공정 이력 (db/03_queries.sql Q4) */
    public List<HistoryView> findLineageHistory(String lotNo) {
        return jdbc.sql("""
                        WITH lineage AS (
                          SELECT :lotNo AS lot_no FROM dual
                          UNION
                          SELECT parent_lot_no
                          FROM   lot_genealogy
                          WHERE  relation_type = 'SPLIT'
                          START  WITH child_lot_no = :lotNo
                          CONNECT BY PRIOR parent_lot_no = child_lot_no AND relation_type = 'SPLIT'
                        )
                        SELECT h.history_id, h.lot_no, h.seq, h.process_code, p.process_name,
                               h.equipment_code, h.operator_id, h.in_qty, h.good_qty, h.defect_qty,
                               h.track_in_at, h.track_out_at
                        FROM   lot_history h
                        JOIN   lineage ln ON ln.lot_no = h.lot_no
                        JOIN   process p  ON p.process_code = h.process_code
                        ORDER  BY h.seq
                        """)
                .param("lotNo", lotNo)
                .query(HistoryView.class)
                .list();
    }

    public List<DefectView> findDefects(List<Long> historyIds) {
        if (historyIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT d.history_id, d.defect_code, c.defect_name, d.qty
                        FROM   lot_defect d JOIN defect_code c ON c.defect_code = d.defect_code
                        WHERE  d.history_id IN (:ids)
                        ORDER  BY d.history_id, d.qty DESC
                        """)
                .param("ids", historyIds)
                .query(DefectView.class)
                .list();
    }

    /** 역방향: 이 LOT을 만드는 데 들어간 LOT들 (db/03_queries.sql Q2) */
    public List<TraceNode> traceBackward(String lotNo) {
        return jdbc.sql("""
                        SELECT LEVEL AS depth, g.parent_lot_no AS lot_no, g.relation_type, g.qty AS link_qty,
                               l.item_code, i.item_name, l.lot_status, l.qty AS current_qty
                        FROM   lot_genealogy g
                        JOIN   lot  l ON l.lot_no = g.parent_lot_no
                        JOIN   item i ON i.item_code = l.item_code
                        START  WITH g.child_lot_no = :lotNo
                        CONNECT BY PRIOR g.parent_lot_no = g.child_lot_no
                        ORDER  SIBLINGS BY g.parent_lot_no
                        """)
                .param("lotNo", lotNo)
                .query(TraceNode.class)
                .list();
    }

    /** 정방향: 이 LOT이 들어간 LOT들 (db/03_queries.sql Q3) */
    public List<TraceNode> traceForward(String lotNo) {
        return jdbc.sql("""
                        SELECT LEVEL AS depth, g.child_lot_no AS lot_no, g.relation_type, g.qty AS link_qty,
                               l.item_code, i.item_name, l.lot_status, l.qty AS current_qty
                        FROM   lot_genealogy g
                        JOIN   lot  l ON l.lot_no = g.child_lot_no
                        JOIN   item i ON i.item_code = l.item_code
                        START  WITH g.parent_lot_no = :lotNo
                        CONNECT BY PRIOR g.child_lot_no = g.parent_lot_no
                        ORDER  SIBLINGS BY g.child_lot_no
                        """)
                .param("lotNo", lotNo)
                .query(TraceNode.class)
                .list();
    }

    /** 재공 현황 (db/03_queries.sql Q8) */
    public List<WipRow> findWip() {
        return jdbc.sql("""
                        SELECT l.lot_no, l.wo_no, l.item_code, l.lot_status, l.qty, l.current_seq,
                               rc.process_code  AS current_process_code,
                               cur.process_name AS current_process,
                               rn.process_code  AS next_process_code,
                               nxt.process_name AS next_process,
                               l.hold_reason, l.updated_at
                        FROM   lot l
                        LEFT   JOIN routing rc  ON rc.item_code = l.item_code AND rc.seq = l.current_seq
                        LEFT   JOIN process cur ON cur.process_code = rc.process_code
                        LEFT   JOIN routing rn  ON rn.item_code = l.item_code AND rn.seq = l.current_seq + 1
                                               AND l.lot_status = 'WAIT'
                        LEFT   JOIN process nxt ON nxt.process_code = rn.process_code
                        WHERE  l.lot_status IN ('WAIT','RUN','HOLD')
                        ORDER  BY l.lot_status, l.lot_no
                        """)
                .query(WipRow.class)
                .list();
    }

    /** 공정별 수율·누적 직행률 (db/03_queries.sql Q1) */
    public List<ProcessYield> findProcessYield() {
        return jdbc.sql("""
                        WITH proc_yield AS (
                          SELECT l.item_code, h.seq, h.process_code,
                                 SUM(h.in_qty) AS in_qty, SUM(h.good_qty) AS good_qty,
                                 SUM(h.good_qty) / SUM(h.in_qty) AS yield
                          FROM   lot_history h JOIN lot l ON l.lot_no = h.lot_no
                          WHERE  h.track_out_at IS NOT NULL
                          GROUP  BY l.item_code, h.seq, h.process_code
                        )
                        SELECT py.item_code, py.seq, py.process_code, p.process_name, py.in_qty, py.good_qty,
                               ROUND(py.yield * 100, 2) AS yield_pct,
                               ROUND(EXP(SUM(LN(py.yield)) OVER (PARTITION BY py.item_code ORDER BY py.seq)) * 100, 2)
                                 AS cumulative_rty_pct
                        FROM   proc_yield py JOIN process p ON p.process_code = py.process_code
                        ORDER  BY py.item_code, py.seq
                        """)
                .query(ProcessYield.class)
                .list();
    }
}
