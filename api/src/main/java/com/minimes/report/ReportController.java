package com.minimes.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 대시보드용 집계 조회 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final JdbcClient jdbc;

    public ReportController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Summary(long activeWorkOrders, long wipLots, long holdLots, long runningEquipment,
                          long downEquipment, long totalEquipment, long todayFinishedQty, long todayDefectQty,
                          long todayTrackOuts, LocalDateTime serverTime) {
    }

    public record ParetoRow(String defectCode, String defectName, String processCode, long qty,
                            BigDecimal sharePct, BigDecimal cumulativePct) {
    }

    public record Activity(LocalDateTime at, String type, String lotNo, String processName,
                           String equipmentCode, Long qty, BigDecimal yieldPct, String detail) {
    }

    @GetMapping("/summary")
    public Summary summary() {
        return jdbc.sql("""
                        SELECT
                          (SELECT COUNT(*) FROM work_order WHERE status IN ('RELEASED','IN_PROGRESS')) AS active_work_orders,
                          (SELECT COUNT(*) FROM lot WHERE lot_status IN ('WAIT','RUN','HOLD')
                                                      AND current_seq IS NOT NULL)                     AS wip_lots,
                          (SELECT COUNT(*) FROM lot WHERE lot_status = 'HOLD')                         AS hold_lots,
                          (SELECT COUNT(*) FROM equipment WHERE status = 'RUN')                        AS running_equipment,
                          (SELECT COUNT(*) FROM equipment WHERE status IN ('DOWN','PM'))               AS down_equipment,
                          (SELECT COUNT(*) FROM equipment)                                             AS total_equipment,
                          (SELECT NVL(SUM(h.good_qty), 0)
                           FROM   lot_history h JOIN lot l ON l.lot_no = h.lot_no
                           WHERE  h.track_out_at >= TRUNC(LOCALTIMESTAMP)
                           AND    h.seq = (SELECT MAX(r.seq) FROM routing r WHERE r.item_code = l.item_code))
                                                                                                       AS today_finished_qty,
                          (SELECT NVL(SUM(defect_qty), 0) FROM lot_history
                           WHERE  track_out_at >= TRUNC(LOCALTIMESTAMP))                               AS today_defect_qty,
                          (SELECT COUNT(*) FROM lot_history
                           WHERE  track_out_at >= TRUNC(LOCALTIMESTAMP))                               AS today_track_outs,
                          LOCALTIMESTAMP                                                               AS server_time
                        FROM dual
                        """)
                .query(Summary.class)
                .single();
    }

    /** 불량 파레토 (db/03_queries.sql Q5) */
    @GetMapping("/defect-pareto")
    public List<ParetoRow> pareto() {
        return jdbc.sql("""
                        WITH d AS (
                          SELECT dc.defect_code, dc.defect_name, dc.process_code, SUM(ld.qty) AS qty
                          FROM   lot_defect ld JOIN defect_code dc ON dc.defect_code = ld.defect_code
                          GROUP  BY dc.defect_code, dc.defect_name, dc.process_code
                        )
                        SELECT defect_code, defect_name, process_code, qty,
                               ROUND(qty / SUM(qty) OVER () * 100, 1) AS share_pct,
                               ROUND(SUM(qty) OVER (ORDER BY qty DESC, defect_code
                                                    ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
                                     / SUM(qty) OVER () * 100, 1) AS cumulative_pct
                        FROM   d
                        ORDER  BY qty DESC, defect_code
                        """)
                .query(ParetoRow.class)
                .list();
    }

    public record IntegrityIssue(String checkName, String ref, BigDecimal expected, BigDecimal actual) {
    }

    /**
     * 데이터 정합성 점검 (db/03_queries.sql Q9 + 설비 상태 점검). 빈 배열이면 정상.
     * 화면이 아니라 데이터 자체를 믿을 수 있는지 확인하기 위한 API.
     */
    @GetMapping("/integrity")
    public List<IntegrityIssue> integrity() {
        return jdbc.sql("""
                        SELECT 'DEFECT_SUM_MISMATCH' AS check_name, TO_CHAR(h.history_id) AS ref,
                               h.defect_qty AS expected, NVL(SUM(d.qty), 0) AS actual
                        FROM   lot_history h
                        LEFT   JOIN lot_defect d ON d.history_id = h.history_id
                        WHERE  h.track_out_at IS NOT NULL
                        GROUP  BY h.history_id, h.defect_qty
                        HAVING h.defect_qty <> NVL(SUM(d.qty), 0)
                        UNION ALL
                        SELECT 'FLOW_QTY_MISMATCH', TO_CHAR(cur.history_id), prv.good_qty, cur.in_qty
                        FROM   lot_history cur
                        JOIN   lot_history prv ON prv.lot_no = cur.lot_no AND prv.seq = cur.seq - 1
                        WHERE  cur.in_qty <> prv.good_qty
                        UNION ALL
                        SELECT 'LOT_QTY_MISMATCH', l.lot_no, NVL(h.good_qty, h.in_qty), l.qty
                        FROM   lot l
                        JOIN   lot_history h ON h.lot_no = l.lot_no AND h.seq = l.current_seq
                        WHERE  l.lot_status NOT IN ('SPLIT')
                        AND    l.qty <> NVL(h.good_qty, h.in_qty)
                        UNION ALL
                        SELECT 'SPLIT_QTY_MISMATCH', g.parent_lot_no, h.good_qty, SUM(g.qty)
                        FROM   lot_genealogy g
                        JOIN   lot p         ON p.lot_no = g.parent_lot_no
                        JOIN   lot_history h ON h.lot_no = p.lot_no AND h.seq = p.current_seq
                        WHERE  g.relation_type = 'SPLIT'
                        GROUP  BY g.parent_lot_no, h.good_qty
                        HAVING SUM(g.qty) <> h.good_qty
                        UNION ALL
                        SELECT 'EQUIPMENT_OVERLAP', a.history_id || ' / ' || b.history_id, NULL, NULL
                        FROM   lot_history a
                        JOIN   lot_history b ON b.equipment_code = a.equipment_code
                                            AND b.history_id > a.history_id
                                            AND b.track_in_at < NVL(a.track_out_at, LOCALTIMESTAMP)
                                            AND a.track_in_at < NVL(b.track_out_at, LOCALTIMESTAMP)
                        UNION ALL
                        SELECT 'EQUIPMENT_STATUS_MISMATCH', e.equipment_code, NULL, NULL
                        FROM   equipment e
                        WHERE  CASE WHEN e.status = 'RUN' THEN 1 ELSE 0 END
                            <> (SELECT COUNT(*) FROM lot_history h
                                WHERE  h.equipment_code = e.equipment_code AND h.track_out_at IS NULL)
                        UNION ALL
                        SELECT 'LOT_STATUS_MISMATCH', l.lot_no, NULL, NULL
                        FROM   lot l
                        WHERE  l.current_seq IS NOT NULL
                        AND    CASE WHEN l.lot_status = 'RUN' THEN 1 ELSE 0 END
                            <> (SELECT COUNT(*) FROM lot_history h
                                WHERE  h.lot_no = l.lot_no AND h.track_out_at IS NULL)
                        """)
                .query(IntegrityIssue.class)
                .list();
    }

    /** 최근 현장 이벤트: 투입, 완료, 설비 비가동 */
    @GetMapping("/recent-activity")
    public List<Activity> recentActivity(@RequestParam(defaultValue = "20") int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        return jdbc.sql("""
                        SELECT at, type, lot_no, process_name, equipment_code, qty, yield_pct, detail
                        FROM (
                          SELECT h.track_in_at AS at, 'TRACK_IN' AS type, h.lot_no, p.process_name,
                                 h.equipment_code, h.in_qty AS qty, CAST(NULL AS NUMBER) AS yield_pct,
                                 CAST(NULL AS VARCHAR2(200)) AS detail
                          FROM   lot_history h JOIN process p ON p.process_code = h.process_code
                          UNION ALL
                          SELECT h.track_out_at, 'TRACK_OUT', h.lot_no, p.process_name,
                                 h.equipment_code, h.good_qty, ROUND(h.good_qty / h.in_qty * 100, 2),
                                 CASE WHEN h.defect_qty > 0 THEN '불량 ' || h.defect_qty END
                          FROM   lot_history h JOIN process p ON p.process_code = h.process_code
                          WHERE  h.track_out_at IS NOT NULL
                          UNION ALL
                          SELECT ev.started_at, ev.status, NULL, p.process_name,
                                 ev.equipment_code, NULL, NULL, ev.reason
                          FROM   equipment_event ev
                          JOIN   equipment e ON e.equipment_code = ev.equipment_code
                          JOIN   process p   ON p.process_code = e.process_code
                          WHERE  ev.status IN ('DOWN','PM')
                        )
                        ORDER  BY at DESC
                        FETCH FIRST :n ROWS ONLY
                        """)
                .param("n", n)
                .query(Activity.class)
                .list();
    }
}
