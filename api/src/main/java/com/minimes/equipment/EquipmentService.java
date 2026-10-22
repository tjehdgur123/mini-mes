package com.minimes.equipment;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.minimes.common.MesException;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 설비 상태 관리.
 * RUN 상태는 Track-in/out 만 바꿀 수 있고, 여기서는 IDLE / DOWN(고장) / PM(보전) 전환만 다룬다.
 * DOWN / PM 은 equipment_event 에 시작~종료 시간으로 남겨 가동률 계산에 쓴다.
 */
@Service
public class EquipmentService {

    private static final Logger log = LoggerFactory.getLogger(EquipmentService.class);
    private static final Set<String> MANUAL_STATUSES = Set.of("IDLE", "DOWN", "PM");

    private final JdbcClient jdbc;

    public EquipmentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record StatusRequest(
            @NotBlank String status,
            @Size(max = 200) String reason,
            @NotBlank @Size(max = 64) String requestKey) {
    }

    public record StatusResponse(String equipmentCode, String status, boolean replayed) {
    }

    public record EquipmentView(String equipmentCode, String equipmentName, String processCode, String processName,
                                String status, String currentLot, LocalDateTime runSince,
                                String stopReason, LocalDateTime stopSince, long todayGoodQty) {
    }

    private record Locked(String equipmentCode, String status) {
    }

    @Transactional
    public StatusResponse changeStatus(String equipmentCode, StatusRequest req) {
        String target = req.status().toUpperCase();
        if (!MANUAL_STATUSES.contains(target)) {
            throw MesException.badRequest("INVALID_STATUS", "IDLE, DOWN, PM 중 하나만 지정할 수 있습니다.");
        }

        Locked eq = jdbc.sql("SELECT equipment_code, status FROM equipment WHERE equipment_code = :code FOR UPDATE WAIT 5")
                .param("code", equipmentCode)
                .query(Locked.class)
                .optional()
                .orElseThrow(() -> MesException.notFound("EQUIPMENT_NOT_FOUND", "설비가 없습니다: " + equipmentCode));

        // 멱등: 같은 요청키로 이미 기록된 이벤트가 있으면 현재 상태를 그대로 돌려준다
        Optional<String> replayed = jdbc.sql("""
                        SELECT equipment_code FROM equipment_event
                        WHERE  request_key = :key OR end_request_key = :key
                        """)
                .param("key", req.requestKey())
                .query(String.class)
                .optional();
        if (replayed.isPresent()) {
            return new StatusResponse(equipmentCode, eq.status(), true);
        }

        if ("RUN".equals(eq.status())) {
            throw MesException.conflict("EQUIPMENT_BUSY",
                    equipmentCode + " 에서 LOT이 진행 중입니다. Track-out 이후에 상태를 바꿀 수 있습니다.");
        }
        if (target.equals(eq.status())) {
            return new StatusResponse(equipmentCode, target, false);
        }

        // 진행 중인 비가동 이벤트 종료
        jdbc.sql("""
                        UPDATE equipment_event
                        SET    ended_at = LOCALTIMESTAMP, end_request_key = :key
                        WHERE  equipment_code = :code AND ended_at IS NULL AND status IN ('DOWN','PM')
                        """)
                .param("code", equipmentCode).param("key", req.requestKey())
                .update();

        if (!"IDLE".equals(target)) {
            jdbc.sql("""
                            INSERT INTO equipment_event (equipment_code, status, started_at, reason, request_key)
                            VALUES (:code, :status, LOCALTIMESTAMP, :reason, :key)
                            """)
                    .param("code", equipmentCode).param("status", target)
                    .param("reason", new SqlParameterValue(Types.VARCHAR, req.reason()))
                    .param("key", req.requestKey())
                    .update();
        }

        jdbc.sql("UPDATE equipment SET status = :status WHERE equipment_code = :code")
                .param("status", target).param("code", equipmentCode)
                .update();

        log.info("설비 상태 변경: {} {} -> {} ({})", equipmentCode, eq.status(), target, req.reason());
        return new StatusResponse(equipmentCode, target, false);
    }

    public List<EquipmentView> list() {
        return jdbc.sql("""
                        SELECT e.equipment_code, e.equipment_name, e.process_code, p.process_name, e.status,
                               h.lot_no      AS current_lot,
                               h.track_in_at AS run_since,
                               ev.reason     AS stop_reason,
                               ev.started_at AS stop_since,
                               (SELECT NVL(SUM(t.good_qty), 0)
                                FROM   lot_history t
                                WHERE  t.equipment_code = e.equipment_code
                                AND    t.track_out_at >= TRUNC(LOCALTIMESTAMP)) AS today_good_qty
                        FROM   equipment e
                        JOIN   process p ON p.process_code = e.process_code
                        LEFT   JOIN lot_history h
                               ON h.equipment_code = e.equipment_code AND h.track_out_at IS NULL
                        LEFT   JOIN equipment_event ev
                               ON ev.equipment_code = e.equipment_code AND ev.ended_at IS NULL
                              AND ev.status IN ('DOWN','PM')
                        ORDER  BY e.process_code, e.equipment_code
                        """)
                .query(EquipmentView.class)
                .list();
    }
}
