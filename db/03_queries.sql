-- =====================================================================
-- Mini MES (MLCC Line) - 핵심 조회 쿼리
-- 면접에서 설명할 수 있도록 쿼리마다 "현장에서 언제 쓰는지"를 적어 둠
-- =====================================================================


-- ---------------------------------------------------------------------
-- Q1. 공정별 수율과 직행률(RTY, Rolled Throughput Yield)
--   언제: "어느 공정에서 가장 많이 잃고 있나?" 일일/주간 생산회의
--   포인트: 공정 수율의 곱 = 직행률. 곱셈 집계 함수가 없으므로 EXP(SUM(LN(x))) 사용
-- ---------------------------------------------------------------------
WITH proc_yield AS (
  SELECT l.item_code,
         h.seq,
         h.process_code,
         SUM(h.in_qty)   AS in_qty,
         SUM(h.good_qty) AS good_qty,
         SUM(h.good_qty) / SUM(h.in_qty) AS yield
  FROM   lot_history h
  JOIN   lot l ON l.lot_no = h.lot_no
  WHERE  h.track_out_at IS NOT NULL
  GROUP  BY l.item_code, h.seq, h.process_code
)
SELECT py.item_code,
       py.seq,
       p.process_name,
       py.in_qty,
       py.good_qty,
       ROUND(py.yield * 100, 2) AS yield_pct,
       ROUND(EXP(SUM(LN(py.yield)) OVER (PARTITION BY py.item_code ORDER BY py.seq)) * 100, 2)
         AS cumulative_rty_pct
FROM   proc_yield py
JOIN   process p ON p.process_code = py.process_code
ORDER  BY py.item_code, py.seq;


-- ---------------------------------------------------------------------
-- Q2. 역방향 추적 (Backward Trace): 완제품 LOT -> 원자재 LOT
--   언제: 고객사 클레임 발생 시 "이 LOT에 어떤 원자재가 들어갔나?"
--   포인트: Oracle 계층 쿼리 CONNECT BY. 분할(SPLIT)을 거쳐도 끝까지 거슬러 올라감
-- ---------------------------------------------------------------------
SELECT LEVEL                                   AS depth,
       LPAD(' ', (LEVEL - 1) * 2) || g.parent_lot_no AS lot_tree,
       g.relation_type,
       g.qty,
       pl.item_code,
       i.item_name
FROM   lot_genealogy g
JOIN   lot  pl ON pl.lot_no   = g.parent_lot_no
JOIN   item i  ON i.item_code = pl.item_code
START  WITH g.child_lot_no = 'L-20261012-002A'
CONNECT BY PRIOR g.parent_lot_no = g.child_lot_no
ORDER  SIBLINGS BY g.parent_lot_no;


-- ---------------------------------------------------------------------
-- Q3. 정방향 추적 (Forward Trace): 원자재 LOT -> 영향받은 생산 LOT
--   언제: 공급사가 "Ni 페이스트 특정 LOT에 문제가 있다"고 통보했을 때
--         어느 LOT을 HOLD 해야 하는지, 출하된 건 없는지 즉시 파악
-- ---------------------------------------------------------------------
SELECT LEVEL                                   AS depth,
       LPAD(' ', (LEVEL - 1) * 2) || g.child_lot_no AS lot_tree,
       g.relation_type,
       cl.lot_status,
       cl.qty                                  AS current_qty,
       CASE WHEN cl.lot_status IN ('FINISHED') THEN '출하 가능 상태 - 출하 여부 확인 필요'
            WHEN cl.lot_status IN ('WAIT','RUN') THEN '진행 중 - HOLD 검토'
            ELSE NULL END                      AS action
FROM   lot_genealogy g
JOIN   lot cl ON cl.lot_no = g.child_lot_no
START  WITH g.parent_lot_no = 'R-NI-2610-01'
CONNECT BY PRIOR g.child_lot_no = g.parent_lot_no
ORDER  SIBLINGS BY g.child_lot_no;


-- ---------------------------------------------------------------------
-- Q4. LOT 전체 공정 이력 (분할 이전 부모 LOT 이력 포함)
--   언제: 품질팀이 "이 LOT은 어느 설비에서, 누가, 언제 작업했나?"를 요청할 때
--   포인트: 분할된 자식 LOT은 1~7공정 이력이 부모 LOT에 남아 있으므로
--           SPLIT 관계만 따라 올라가서 자기 자신 + 조상 LOT 이력을 합친다
-- ---------------------------------------------------------------------
WITH lineage AS (
  SELECT 'L-20261012-002A' AS lot_no FROM dual
  UNION
  SELECT parent_lot_no
  FROM   lot_genealogy
  WHERE  relation_type = 'SPLIT'
  START  WITH child_lot_no = 'L-20261012-002A'
  CONNECT BY PRIOR parent_lot_no = child_lot_no AND relation_type = 'SPLIT'
)
SELECT h.seq,
       p.process_name,
       h.lot_no,
       h.equipment_code,
       h.operator_id,
       h.in_qty,
       h.good_qty,
       h.defect_qty,
       TO_CHAR(h.track_in_at,  'MM-DD HH24:MI') AS track_in,
       TO_CHAR(h.track_out_at, 'MM-DD HH24:MI') AS track_out,
       ROUND((CAST(h.track_out_at AS DATE) - CAST(h.track_in_at AS DATE)) * 24, 1) AS hours
FROM   lot_history h
JOIN   lineage     ln ON ln.lot_no = h.lot_no
JOIN   process     p  ON p.process_code = h.process_code
ORDER  BY h.seq;


-- ---------------------------------------------------------------------
-- Q5. 불량 파레토 (누적 비율)
--   언제: "불량의 80%를 차지하는 상위 유형은?" 개선 우선순위 결정
--   포인트: 윈도우 함수로 누적 합계와 누적 비율을 한 번에 계산
-- ---------------------------------------------------------------------
WITH d AS (
  SELECT dc.defect_code,
         dc.defect_name,
         dc.process_code,
         SUM(ld.qty) AS qty
  FROM   lot_defect  ld
  JOIN   defect_code dc ON dc.defect_code = ld.defect_code
  GROUP  BY dc.defect_code, dc.defect_name, dc.process_code
)
SELECT RANK() OVER (ORDER BY qty DESC)                                   AS rnk,
       defect_code,
       defect_name,
       process_code,
       qty,
       ROUND(qty / SUM(qty) OVER () * 100, 1)                             AS share_pct,
       ROUND(SUM(qty) OVER (ORDER BY qty DESC, defect_code
                            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
             / SUM(qty) OVER () * 100, 1)                                 AS cumulative_pct
FROM   d
ORDER  BY qty DESC, defect_code;


-- ---------------------------------------------------------------------
-- Q6. 이상 LOT 감지: 같은 공정의 "다른 LOT 평균" 대비 수율 하락
--   언제: 매 Track-out 직후 자동 점검 -> 기준 초과 시 HOLD 후보
--   포인트: 자기 자신을 포함한 평균은 이상치에 끌려가므로
--           (전체 합 - 자기 값) / (전체 투입 - 자기 투입) 으로 "나머지 평균"과 비교
-- ---------------------------------------------------------------------
WITH y AS (
  SELECT h.lot_no,
         h.process_code,
         h.equipment_code,
         h.in_qty,
         h.good_qty,
         h.good_qty / h.in_qty                                     AS lot_yield,
         SUM(h.good_qty) OVER (PARTITION BY h.process_code)        AS sum_good,
         SUM(h.in_qty)   OVER (PARTITION BY h.process_code)        AS sum_in,
         COUNT(*)        OVER (PARTITION BY h.process_code)        AS lot_cnt
  FROM   lot_history h
  WHERE  h.track_out_at IS NOT NULL
)
SELECT lot_no,
       process_code,
       equipment_code,
       ROUND(lot_yield * 100, 2)                                          AS lot_yield_pct,
       ROUND((sum_good - good_qty) / (sum_in - in_qty) * 100, 2)          AS others_yield_pct,
       ROUND((lot_yield - (sum_good - good_qty) / (sum_in - in_qty)) * 100, 2) AS gap_pctp
FROM   y
WHERE  lot_cnt > 1
AND    lot_yield < (sum_good - good_qty) / (sum_in - in_qty) - 0.01   -- 1%p 이상 하락
ORDER  BY gap_pctp;


-- ---------------------------------------------------------------------
-- Q7. 설비 가동률 (기간: 2026-10-12 00:00 ~ 2026-10-21 00:00, 216시간)
--   언제: 설비 투자/증설 판단, 병목 설비 파악
--   포인트: 진행 중 작업은 LOCALTIMESTAMP까지, 기간 밖 시간은 잘라서 계산
-- ---------------------------------------------------------------------
WITH params AS (
  SELECT TIMESTAMP '2026-10-12 00:00:00' AS t_from,
         TIMESTAMP '2026-10-21 00:00:00' AS t_to
  FROM   dual
),
run_time AS (
  SELECT h.equipment_code,
         SUM( (CAST(LEAST(NVL(h.track_out_at, LOCALTIMESTAMP), pr.t_to) AS DATE)
             - CAST(GREATEST(h.track_in_at, pr.t_from) AS DATE)) * 24 ) AS run_h
  FROM   lot_history h CROSS JOIN params pr
  WHERE  h.track_in_at < pr.t_to
  AND    NVL(h.track_out_at, LOCALTIMESTAMP) > pr.t_from
  GROUP  BY h.equipment_code
),
stop_time AS (
  SELECT e.equipment_code,
         SUM( (CAST(LEAST(NVL(e.ended_at, LOCALTIMESTAMP), pr.t_to) AS DATE)
             - CAST(GREATEST(e.started_at, pr.t_from) AS DATE)) * 24 ) AS stop_h
  FROM   equipment_event e CROSS JOIN params pr
  WHERE  e.status IN ('DOWN','PM')
  AND    e.started_at < pr.t_to
  AND    NVL(e.ended_at, LOCALTIMESTAMP) > pr.t_from
  GROUP  BY e.equipment_code
)
SELECT eq.equipment_code,
       eq.equipment_name,
       eq.status                                           AS current_status,
       ROUND(NVL(r.run_h, 0), 1)                           AS run_hours,
       ROUND(NVL(s.stop_h, 0), 1)                          AS stop_hours,
       ROUND(NVL(r.run_h, 0) / 216 * 100, 1)               AS utilization_pct
FROM   equipment eq
LEFT   JOIN run_time  r ON r.equipment_code = eq.equipment_code
LEFT   JOIN stop_time s ON s.equipment_code = eq.equipment_code
ORDER  BY utilization_pct DESC;


-- ---------------------------------------------------------------------
-- Q8. 재공(WIP) 현황: 진행 중 LOT의 현재 위치와 다음 공정
--   언제: 아침 조회 "지금 라인에 뭐가 어디 있나?"
-- ---------------------------------------------------------------------
SELECT l.lot_no,
       l.wo_no,
       l.item_code,
       l.lot_status,
       l.qty,
       cur.process_name                               AS current_process,
       nxt.process_name                               AS next_process,
       ROUND((CAST(LOCALTIMESTAMP AS DATE) - CAST(l.updated_at AS DATE)) * 24, 1) AS hours_since_update
FROM   lot l
LEFT   JOIN routing rc  ON rc.item_code = l.item_code
                       AND rc.seq = l.current_seq
LEFT   JOIN process cur ON cur.process_code = rc.process_code
LEFT   JOIN routing rn  ON rn.item_code = l.item_code
                       AND rn.seq = l.current_seq + 1
                       AND l.lot_status = 'WAIT'
LEFT   JOIN process nxt ON nxt.process_code = rn.process_code
WHERE  l.lot_status IN ('WAIT','RUN','HOLD')
ORDER  BY l.lot_status, l.lot_no;


-- ---------------------------------------------------------------------
-- Q9. 데이터 정합성 점검 (결과가 0건이어야 정상)
--   언제: 배치 마감 전, 또는 인터페이스 장애 후 데이터 검증
--   포인트: 화면이 아니라 데이터를 믿을 수 있는지 확인하는 쿼리
-- ---------------------------------------------------------------------
-- 9-1. 공정 이력의 불량 수량 = 불량 상세 합계
SELECT 'DEFECT_SUM_MISMATCH' AS check_name, TO_CHAR(h.history_id) AS ref, h.defect_qty AS expected, NVL(SUM(d.qty), 0) AS actual
FROM   lot_history h
LEFT   JOIN lot_defect d ON d.history_id = h.history_id
WHERE  h.track_out_at IS NOT NULL
GROUP  BY h.history_id, h.defect_qty
HAVING h.defect_qty <> NVL(SUM(d.qty), 0)
UNION ALL
-- 9-2. 같은 LOT에서 다음 공정 투입 수량 = 이전 공정 양품 수량
SELECT 'FLOW_QTY_MISMATCH', TO_CHAR(cur.history_id), prv.good_qty, cur.in_qty
FROM   lot_history cur
JOIN   lot_history prv ON prv.lot_no = cur.lot_no AND prv.seq = cur.seq - 1
WHERE  cur.in_qty <> prv.good_qty
UNION ALL
-- 9-3. LOT 현재 수량 = 마지막 공정 양품 수량 (진행 중이면 투입 수량)
SELECT 'LOT_QTY_MISMATCH', l.lot_no, NVL(h.good_qty, h.in_qty), l.qty
FROM   lot l
JOIN   lot_history h ON h.lot_no = l.lot_no AND h.seq = l.current_seq
WHERE  l.lot_status NOT IN ('SPLIT')
AND    l.qty <> NVL(h.good_qty, h.in_qty)
UNION ALL
-- 9-4. 분할된 자식 수량 합계 = 부모의 분할 직전 양품 수량
SELECT 'SPLIT_QTY_MISMATCH', g.parent_lot_no, h.good_qty, SUM(g.qty)
FROM   lot_genealogy g
JOIN   lot p          ON p.lot_no = g.parent_lot_no
JOIN   lot_history h  ON h.lot_no = p.lot_no AND h.seq = p.current_seq
WHERE  g.relation_type = 'SPLIT'
GROUP  BY g.parent_lot_no, h.good_qty
HAVING SUM(g.qty) <> h.good_qty
UNION ALL
-- 9-5. 같은 설비에서 작업 시간이 겹치는 이력
SELECT 'EQUIPMENT_OVERLAP', a.history_id || ' / ' || b.history_id, NULL, NULL
FROM   lot_history a
JOIN   lot_history b ON b.equipment_code = a.equipment_code
                    AND b.history_id > a.history_id
                    AND b.track_in_at < NVL(a.track_out_at, LOCALTIMESTAMP)
                    AND a.track_in_at < NVL(b.track_out_at, LOCALTIMESTAMP);
