package com.minimes.workorder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.minimes.common.MesException;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 작업지시 생성과 LOT 발행.
 * 계획 수량을 LOT 크기로 나눠 LOT을 만들고, 모든 LOT은 WAIT / 공정 순번 0 으로 시작한다.
 */
@Service
public class WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_LOTS_PER_ORDER = 100;

    private final JdbcClient jdbc;

    public WorkOrderService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record CreateRequest(
            @NotBlank String itemCode,
            @NotNull @Positive Long planQty,
            @NotNull @Positive Long lotSize,
            LocalDate dueDate) {
    }

    public record LotSummary(String lotNo, long qty) {
    }

    public record CreateResponse(String woNo, String itemCode, long planQty, String status, List<LotSummary> lots) {
    }

    public record WorkOrderRow(String woNo, String itemCode, long planQty, LocalDate dueDate, String status,
                               long lotCount, long finishedQty, LocalDateTime createdAt) {
    }

    @Transactional
    public CreateResponse create(CreateRequest req) {
        String itemType = jdbc.sql("SELECT item_type FROM item WHERE item_code = :item AND use_yn = 'Y'")
                .param("item", req.itemCode())
                .query(String.class)
                .optional()
                .orElseThrow(() -> MesException.notFound("ITEM_NOT_FOUND", "사용 중인 품목이 없습니다: " + req.itemCode()));
        if (!"FG".equals(itemType)) {
            throw MesException.badRequest("NOT_FINISHED_GOOD", "완제품(FG)만 작업지시를 만들 수 있습니다.");
        }
        Long routingCount = jdbc.sql("SELECT COUNT(*) FROM routing WHERE item_code = :item")
                .param("item", req.itemCode())
                .query(Long.class)
                .single();
        if (routingCount == 0) {
            throw MesException.conflict("ROUTING_NOT_DEFINED", "라우팅이 등록되지 않은 품목입니다: " + req.itemCode());
        }
        long lotCount = (req.planQty() + req.lotSize() - 1) / req.lotSize();
        if (lotCount > MAX_LOTS_PER_ORDER) {
            throw MesException.badRequest("TOO_MANY_LOTS",
                    "LOT이 %d개 생성됩니다. LOT 크기를 늘려주세요 (최대 %d개).".formatted(lotCount, MAX_LOTS_PER_ORDER));
        }

        String today = LocalDate.now().format(YMD);
        // 채번: 오늘 날짜 접두어의 마지막 번호 + 1.
        // 동시에 두 요청이 같은 번호를 만들면 PK 충돌 -> 409(DUPLICATE_KEY)로 재시도 유도
        String woNo = "WO-" + today + "-" + "%03d".formatted(nextSerial("work_order", "wo_no", "WO-" + today + "-"));
        jdbc.sql("""
                        INSERT INTO work_order (wo_no, item_code, plan_qty, due_date, status)
                        VALUES (:wo, :item, :qty, :due, 'RELEASED')
                        """)
                .param("wo", woNo).param("item", req.itemCode()).param("qty", req.planQty())
                .param("due", req.dueDate() == null ? null : java.sql.Date.valueOf(req.dueDate()),
                        java.sql.Types.DATE)
                .update();

        String lotPrefix = "L-" + today + "-";
        int serial = nextSerial("lot", "lot_no", lotPrefix);
        List<LotSummary> lots = new ArrayList<>();
        long remaining = req.planQty();
        for (int i = 0; i < lotCount; i++) {
            long qty = Math.min(req.lotSize(), remaining);
            String lotNo = lotPrefix + "%03d".formatted(serial + i);
            jdbc.sql("""
                            INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status)
                            VALUES (:lot, :item, :wo, :qty, 0, 'WAIT')
                            """)
                    .param("lot", lotNo).param("item", req.itemCode()).param("wo", woNo).param("qty", qty)
                    .update();
            lots.add(new LotSummary(lotNo, qty));
            remaining -= qty;
        }
        log.info("작업지시 생성: {} item={} qty={} lots={}", woNo, req.itemCode(), req.planQty(), lots.size());
        return new CreateResponse(woNo, req.itemCode(), req.planQty(), "RELEASED", lots);
    }

    public List<WorkOrderRow> list() {
        return jdbc.sql("""
                        SELECT w.wo_no, w.item_code, w.plan_qty, w.due_date, w.status,
                               COUNT(l.lot_no) AS lot_count,
                               NVL(SUM(CASE WHEN l.lot_status = 'FINISHED' THEN l.qty END), 0) AS finished_qty,
                               w.created_at
                        FROM   work_order w
                        LEFT   JOIN lot l ON l.wo_no = w.wo_no
                        GROUP  BY w.wo_no, w.item_code, w.plan_qty, w.due_date, w.status, w.created_at
                        ORDER  BY w.created_at DESC
                        """)
                .query(WorkOrderRow.class)
                .list();
    }

    /** prefix 로 시작하는 번호 중 끝 3자리의 최댓값 + 1 (분할 LOT의 접미사 'A','B'는 제외) */
    private int nextSerial(String table, String column, String prefix) {
        int max = jdbc.sql("""
                        SELECT NVL(MAX(TO_NUMBER(SUBSTR(%2$s, :start, 3))), 0)
                        FROM   %1$s
                        WHERE  %2$s LIKE :prefix || '%%'
                        AND    LENGTH(%2$s) = :len
                        """.formatted(table, column))
                .param("start", prefix.length() + 1)
                .param("prefix", prefix)
                .param("len", prefix.length() + 3)
                .query(Integer.class)
                .single();
        return max + 1;
    }
}
