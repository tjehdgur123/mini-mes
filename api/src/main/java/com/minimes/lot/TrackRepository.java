package com.minimes.lot;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.SqlParameterValue;
import com.minimes.common.MesException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Track-in/out 처리에 필요한 SQL 모음.
 * JPA 대신 SQL을 직접 써서 잠금 범위와 실행되는 쿼리를 명확히 드러낸다.
 */
@Repository
public class TrackRepository {

    /** 행 잠금 대기 최대 시간(초). 무한 대기로 단말이 멈추는 것을 막는다. */
    private static final int LOCK_WAIT_SECONDS = 5;

    private final JdbcClient jdbc;

    public TrackRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ rows

    public record LotRow(String lotNo, String itemCode, String woNo, long qty, Integer currentSeq,
                         String lotStatus, String holdReason, long version) {
    }

    public record EquipmentRow(String equipmentCode, String processCode, String status) {
    }

    public record HistoryRow(long historyId, String lotNo, int seq, String processCode, String equipmentCode,
                             long inQty, Long goodQty, Long defectQty,
                             LocalDateTime trackInAt, LocalDateTime trackOutAt) {
    }

    public record DefectCodeRow(String defectCode, String processCode) {
    }

    public record YieldStat(long sumIn, long sumGood) {
    }

    private static final String HISTORY_COLUMNS = """
            history_id, lot_no, seq, process_code, equipment_code,
            in_qty, good_qty, defect_qty, track_in_at, track_out_at
            """;

    // ------------------------------------------------------------------ lot

    /** LOT 행 잠금. 같은 LOT에 대한 요청은 여기서 한 줄로 세워진다. */
    public Optional<LotRow> lockLot(String lotNo) {
        return jdbc.sql("""
                        SELECT lot_no, item_code, wo_no, qty, current_seq, lot_status, hold_reason, version
                        FROM   lot
                        WHERE  lot_no = :lotNo
                        FOR UPDATE WAIT %d
                        """.formatted(LOCK_WAIT_SECONDS))
                .param("lotNo", lotNo)
                .query(LotRow.class)
                .optional();
    }

    public void updateLotToRun(String lotNo, int seq, long version) {
        int n = jdbc.sql("""
                        UPDATE lot
                        SET    lot_status = 'RUN', current_seq = :seq,
                               version = version + 1, updated_at = LOCALTIMESTAMP
                        WHERE  lot_no = :lotNo AND version = :version
                        """)
                .param("seq", seq).param("lotNo", lotNo).param("version", version)
                .update();
        requireOneRow(n, "LOT 상태 변경(RUN)", lotNo);
    }

    public void updateLotAfterTrackOut(String lotNo, long qty, String status, String holdReason, long version) {
        int n = jdbc.sql("""
                        UPDATE lot
                        SET    qty = :qty, lot_status = :status, hold_reason = :holdReason,
                               version = version + 1, updated_at = LOCALTIMESTAMP
                        WHERE  lot_no = :lotNo AND version = :version
                        """)
                .param("qty", qty).param("status", status).param("holdReason", nullableText(holdReason))
                .param("lotNo", lotNo).param("version", version)
                .update();
        requireOneRow(n, "LOT 상태 변경(Track-out)", lotNo);
    }

    public void releaseHold(String lotNo, String newStatus, long version) {
        int n = jdbc.sql("""
                        UPDATE lot
                        SET    lot_status = :status, hold_reason = NULL,
                               version = version + 1, updated_at = LOCALTIMESTAMP
                        WHERE  lot_no = :lotNo AND version = :version
                        """)
                .param("status", newStatus).param("lotNo", lotNo).param("version", version)
                .update();
        requireOneRow(n, "HOLD 해제", lotNo);
    }

    // ------------------------------------------------------------------ routing / master

    public Optional<String> findRoutingProcess(String itemCode, int seq) {
        return jdbc.sql("SELECT process_code FROM routing WHERE item_code = :item AND seq = :seq")
                .param("item", itemCode).param("seq", seq)
                .query(String.class)
                .optional();
    }

    public String findProcessName(String processCode) {
        return jdbc.sql("SELECT process_name FROM process WHERE process_code = :code")
                .param("code", processCode)
                .query(String.class)
                .single();
    }

    public List<DefectCodeRow> findDefectCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT defect_code, process_code FROM defect_code WHERE defect_code IN (:codes)")
                .param("codes", codes)
                .query(DefectCodeRow.class)
                .list();
    }

    // ------------------------------------------------------------------ equipment

    public Optional<EquipmentRow> lockEquipment(String equipmentCode) {
        return jdbc.sql("""
                        SELECT equipment_code, process_code, status
                        FROM   equipment
                        WHERE  equipment_code = :code
                        FOR UPDATE WAIT %d
                        """.formatted(LOCK_WAIT_SECONDS))
                .param("code", equipmentCode)
                .query(EquipmentRow.class)
                .optional();
    }

    public void updateEquipmentStatus(String equipmentCode, String status) {
        jdbc.sql("UPDATE equipment SET status = :status WHERE equipment_code = :code")
                .param("status", status).param("code", equipmentCode)
                .update();
    }

    // ------------------------------------------------------------------ history

    public Optional<HistoryRow> findHistoryByInKey(String requestKey) {
        return jdbc.sql("SELECT " + HISTORY_COLUMNS + " FROM lot_history WHERE in_request_key = :key")
                .param("key", requestKey)
                .query(HistoryRow.class)
                .optional();
    }

    public Optional<HistoryRow> findHistoryByOutKey(String requestKey) {
        return jdbc.sql("SELECT " + HISTORY_COLUMNS + " FROM lot_history WHERE out_request_key = :key")
                .param("key", requestKey)
                .query(HistoryRow.class)
                .optional();
    }

    public Optional<HistoryRow> findOpenHistory(String lotNo, int seq) {
        return jdbc.sql("SELECT " + HISTORY_COLUMNS
                        + " FROM lot_history WHERE lot_no = :lotNo AND seq = :seq AND track_out_at IS NULL")
                .param("lotNo", lotNo).param("seq", seq)
                .query(HistoryRow.class)
                .optional();
    }

    public long insertHistory(String lotNo, int seq, String processCode, String equipmentCode,
                              String operatorId, long inQty, String requestKey) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO lot_history
                          (lot_no, seq, process_code, equipment_code, operator_id, in_qty, track_in_at, in_request_key)
                        VALUES
                          (:lotNo, :seq, :process, :equipment, :operator, :inQty, LOCALTIMESTAMP, :key)
                        """)
                .param("lotNo", lotNo).param("seq", seq).param("process", processCode)
                .param("equipment", equipmentCode).param("operator", nullableText(operatorId))
                .param("inQty", inQty).param("key", requestKey)
                .update(keyHolder, "HISTORY_ID");
        return keyHolder.getKeyAs(Number.class).longValue();
    }

    public void closeHistory(long historyId, long goodQty, long defectQty, String requestKey) {
        int n = jdbc.sql("""
                        UPDATE lot_history
                        SET    good_qty = :good, defect_qty = :defect,
                               track_out_at = LOCALTIMESTAMP, out_request_key = :key
                        WHERE  history_id = :id AND track_out_at IS NULL
                        """)
                .param("good", goodQty).param("defect", defectQty).param("key", requestKey).param("id", historyId)
                .update();
        requireOneRow(n, "공정 이력 종료", String.valueOf(historyId));
    }

    public void insertDefect(long historyId, String defectCode, long qty) {
        jdbc.sql("INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (:id, :code, :qty)")
                .param("id", historyId).param("code", defectCode).param("qty", qty)
                .update();
    }

    /** 같은 공정의 "다른" 완료 이력 수율 합계 (자기 자신 제외) */
    public YieldStat othersYield(String processCode, long excludeHistoryId) {
        return jdbc.sql("""
                        SELECT NVL(SUM(in_qty), 0) AS sum_in, NVL(SUM(good_qty), 0) AS sum_good
                        FROM   lot_history
                        WHERE  process_code = :process
                        AND    history_id <> :id
                        AND    track_out_at IS NOT NULL
                        """)
                .param("process", processCode).param("id", excludeHistoryId)
                .query(YieldStat.class)
                .single();
    }

    // ------------------------------------------------------------------ work order

    public void markWorkOrderInProgress(String woNo) {
        jdbc.sql("UPDATE work_order SET status = 'IN_PROGRESS' WHERE wo_no = :wo AND status IN ('CREATED','RELEASED')")
                .param("wo", woNo)
                .update();
    }

    /** 작업지시의 모든 LOT이 끝났으면 작업지시 완료 처리 */
    public void completeWorkOrderIfDone(String woNo) {
        jdbc.sql("""
                        UPDATE work_order w
                        SET    w.status = 'COMPLETED'
                        WHERE  w.wo_no = :wo
                        AND    w.status = 'IN_PROGRESS'
                        AND    NOT EXISTS (SELECT 1 FROM lot l
                                           WHERE  l.wo_no = w.wo_no
                                           AND    l.lot_status NOT IN ('FINISHED','SPLIT','SCRAPPED'))
                        """)
                .param("wo", woNo)
                .update();
    }

    // ------------------------------------------------------------------ util

    /** Oracle은 타입 없는 NULL 바인딩에서 오류가 날 수 있어 VARCHAR로 명시 */
    private static SqlParameterValue nullableText(String value) {
        return new SqlParameterValue(Types.VARCHAR, value);
    }

    /** version 조건부 UPDATE가 0건이면 다른 트랜잭션이 먼저 바꾼 것 */
    private static void requireOneRow(int updated, String action, String key) {
        if (updated != 1) {
            throw MesException.conflict("CONCURRENT_UPDATE", action + " 실패: " + key + " 이(가) 다른 요청에 의해 먼저 변경되었습니다.");
        }
    }
}
