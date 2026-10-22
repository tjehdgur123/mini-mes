-- =====================================================================
-- Mini MES (MLCC Line) - Sample Data
-- 시나리오: 2026-10-12 ~ 2026-10-20, MLCC 2개 품목 / 작업지시 3건 / LOT 9개
-- 수량은 모든 공정을 칩 환산 수량(EA) 기준으로 단순화
-- =====================================================================

-- 1. 품목
INSERT INTO item (item_code, item_name, item_type, spec, unit) VALUES ('RAW-BT-C0G', 'BaTiO3 유전체 분말 (C0G용)', 'RAW', 'D50 0.3um', 'KG');
INSERT INTO item (item_code, item_name, item_type, spec, unit) VALUES ('RAW-BT-X7R', 'BaTiO3 유전체 분말 (X7R용)', 'RAW', 'D50 0.2um', 'KG');
INSERT INTO item (item_code, item_name, item_type, spec, unit) VALUES ('RAW-NI-PST', 'Ni 내부전극 페이스트', 'RAW', 'Ni 50wt%', 'KG');
INSERT INTO item (item_code, item_name, item_type, spec, unit) VALUES ('RAW-CU-PST', 'Cu 외부전극 페이스트', 'RAW', 'Cu 70wt%', 'KG');
INSERT INTO item (item_code, item_name, item_type, spec, unit) VALUES ('MLCC-1206-C0G-1KV', '고압 MLCC 1206 C0G 1kV 1nF', 'FG', '3.2x1.6mm, 1000V, 102J', 'EA');
INSERT INTO item (item_code, item_name, item_type, spec, unit) VALUES ('MLCC-0805-X7R-100V', '전장용 MLCC 0805 X7R 100V 100nF', 'FG', '2.0x1.25mm, 100V, 104K, AEC-Q200', 'EA');

-- 2. 공정
INSERT INTO process (process_code, process_name, description) VALUES ('P10', '시트 성형', '유전체 슬러리를 얇은 세라믹 시트로 성형 (Tape Casting)');
INSERT INTO process (process_code, process_name, description) VALUES ('P20', '내부전극 인쇄', 'Ni 페이스트로 시트 위에 내부전극 패턴 인쇄');
INSERT INTO process (process_code, process_name, description) VALUES ('P30', '적층/압착', '인쇄된 시트를 정렬해 수백 층 적층 후 압착');
INSERT INTO process (process_code, process_name, description) VALUES ('P40', '절단', '적층 블록을 개별 칩 크기로 절단');
INSERT INTO process (process_code, process_name, description) VALUES ('P50', '소성', '고온 소성으로 세라믹과 전극을 일체화');
INSERT INTO process (process_code, process_name, description) VALUES ('P60', '외부전극', '칩 양 끝에 Cu 외부전극 도포 및 경화');
INSERT INTO process (process_code, process_name, description) VALUES ('P70', '도금', '외부전극 위 Ni/Sn 도금');
INSERT INTO process (process_code, process_name, description) VALUES ('P80', '측정/선별', '정전용량, 절연저항 측정 후 양품 선별');

-- 3. 라우팅 (두 품목 모두 8개 공정 동일 순서)
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-1206-C0G-1KV', 1, 'P10');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-1206-C0G-1KV', 2, 'P20');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-1206-C0G-1KV', 3, 'P30');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-1206-C0G-1KV', 4, 'P40');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-1206-C0G-1KV', 5, 'P50');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-1206-C0G-1KV', 6, 'P60');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-1206-C0G-1KV', 7, 'P70');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-1206-C0G-1KV', 8, 'P80');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-0805-X7R-100V', 1, 'P10');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-0805-X7R-100V', 2, 'P20');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-0805-X7R-100V', 3, 'P30');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-0805-X7R-100V', 4, 'P40');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-0805-X7R-100V', 5, 'P50');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-0805-X7R-100V', 6, 'P60');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-0805-X7R-100V', 7, 'P70');
INSERT INTO routing (item_code, seq, process_code) VALUES ('MLCC-0805-X7R-100V', 8, 'P80');

-- 4. 설비
INSERT INTO equipment (equipment_code, equipment_name, process_code, status) VALUES ('TC-01', '시트 성형기 #1', 'P10', 'IDLE');
INSERT INTO equipment (equipment_code, equipment_name, process_code, status) VALUES ('PR-01', '스크린 인쇄기 #1', 'P20', 'IDLE');
INSERT INTO equipment (equipment_code, equipment_name, process_code, status) VALUES ('ST-01', '적층기 #1', 'P30', 'IDLE');
INSERT INTO equipment (equipment_code, equipment_name, process_code, status) VALUES ('CT-01', '절단기 #1', 'P40', 'IDLE');
INSERT INTO equipment (equipment_code, equipment_name, process_code, status) VALUES ('SN-01', '소성로 #1', 'P50', 'IDLE');
INSERT INTO equipment (equipment_code, equipment_name, process_code, status) VALUES ('SN-02', '소성로 #2', 'P50', 'IDLE');
INSERT INTO equipment (equipment_code, equipment_name, process_code, status) VALUES ('TM-01', '외부전극 도포기 #1', 'P60', 'IDLE');
INSERT INTO equipment (equipment_code, equipment_name, process_code, status) VALUES ('PL-01', '도금 라인 #1', 'P70', 'IDLE');
INSERT INTO equipment (equipment_code, equipment_name, process_code, status) VALUES ('TS-01', '측정/선별기 #1', 'P80', 'IDLE');
INSERT INTO equipment (equipment_code, equipment_name, process_code, status) VALUES ('TS-02', '측정/선별기 #2', 'P80', 'RUN');

-- 5. 불량 코드
INSERT INTO defect_code (defect_code, defect_name, process_code) VALUES ('D-SHT', '시트 핀홀/두께 불량', 'P10');
INSERT INTO defect_code (defect_code, defect_name, process_code) VALUES ('D-PRT', '인쇄 번짐/누락', 'P20');
INSERT INTO defect_code (defect_code, defect_name, process_code) VALUES ('D-STK', '적층 어긋남', 'P30');
INSERT INTO defect_code (defect_code, defect_name, process_code) VALUES ('D-CUT', '절단 치핑', 'P40');
INSERT INTO defect_code (defect_code, defect_name, process_code) VALUES ('D-CRK', '소성 크랙', 'P50');
INSERT INTO defect_code (defect_code, defect_name, process_code) VALUES ('D-DLM', '디라미네이션(층간 박리)', 'P50');
INSERT INTO defect_code (defect_code, defect_name, process_code) VALUES ('D-TRM', '외부전극 도포 불량', 'P60');
INSERT INTO defect_code (defect_code, defect_name, process_code) VALUES ('D-PLT', '도금 불량', 'P70');
INSERT INTO defect_code (defect_code, defect_name, process_code) VALUES ('D-CAP', '정전용량 규격 이탈', 'P80');
INSERT INTO defect_code (defect_code, defect_name, process_code) VALUES ('D-IR', '절연저항 불량', 'P80');

-- 6. 작업지시
INSERT INTO work_order (wo_no, item_code, plan_qty, due_date, status, created_at) VALUES ('WO-20261012-001', 'MLCC-1206-C0G-1KV', 200000, DATE '2026-10-17', 'IN_PROGRESS', TIMESTAMP '2026-10-11 16:00:00');
INSERT INTO work_order (wo_no, item_code, plan_qty, due_date, status, created_at) VALUES ('WO-20261014-001', 'MLCC-0805-X7R-100V', 300000, DATE '2026-10-21', 'IN_PROGRESS', TIMESTAMP '2026-10-13 16:00:00');
INSERT INTO work_order (wo_no, item_code, plan_qty, due_date, status, created_at) VALUES ('WO-20261019-001', 'MLCC-1206-C0G-1KV', 100000, DATE '2026-10-24', 'RELEASED', TIMESTAMP '2026-10-19 16:00:00');

-- 7. LOT (원자재 LOT 4 + 생산 LOT 7)
INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status, created_at, updated_at) VALUES ('R-BT-C0G-2610-01', 'RAW-BT-C0G', NULL, 60, NULL, 'STOCK', TIMESTAMP '2026-10-01 09:00:00', TIMESTAMP '2026-10-12 08:00:00');
INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status, created_at, updated_at) VALUES ('R-BT-X7R-2610-01', 'RAW-BT-X7R', NULL, 140, NULL, 'STOCK', TIMESTAMP '2026-10-01 09:00:00', TIMESTAMP '2026-10-14 13:00:00');
INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status, created_at, updated_at) VALUES ('R-NI-2610-01', 'RAW-NI-PST', NULL, 30, NULL, 'STOCK', TIMESTAMP '2026-10-02 09:00:00', TIMESTAMP '2026-10-14 18:00:00');
INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status, created_at, updated_at) VALUES ('R-CU-2610-01', 'RAW-CU-PST', NULL, 46, NULL, 'STOCK', TIMESTAMP '2026-10-02 09:00:00', TIMESTAMP '2026-10-14 14:00:00');
INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status, created_at, updated_at) VALUES ('L-20261012-001', 'MLCC-1206-C0G-1KV', 'WO-20261012-001', 96800, 8, 'FINISHED', TIMESTAMP '2026-10-12 07:30:00', TIMESTAMP '2026-10-15 14:00:00');
INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status, created_at, updated_at) VALUES ('L-20261012-002', 'MLCC-1206-C0G-1KV', 'WO-20261012-001', 0, 7, 'SPLIT', TIMESTAMP '2026-10-12 07:30:00', TIMESTAMP '2026-10-15 13:00:00');
INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status, created_at, updated_at) VALUES ('L-20261012-002A', 'MLCC-1206-C0G-1KV', 'WO-20261012-001', 48250, 8, 'FINISHED', TIMESTAMP '2026-10-15 13:00:00', TIMESTAMP '2026-10-15 21:00:00');
INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status, created_at, updated_at) VALUES ('L-20261012-002B', 'MLCC-1206-C0G-1KV', 'WO-20261012-001', 48800, 8, 'RUN', TIMESTAMP '2026-10-15 13:00:00', TIMESTAMP '2026-10-20 15:00:00');
INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status, created_at, updated_at) VALUES ('L-20261014-001', 'MLCC-0805-X7R-100V', 'WO-20261014-001', 142800, 5, 'HOLD', TIMESTAMP '2026-10-14 07:30:00', TIMESTAMP '2026-10-16 08:10:00');
INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status, created_at, updated_at) VALUES ('L-20261014-002', 'MLCC-0805-X7R-100V', 'WO-20261014-001', 149200, 2, 'WAIT', TIMESTAMP '2026-10-14 07:30:00', TIMESTAMP '2026-10-14 22:00:00');
INSERT INTO lot (lot_no, item_code, wo_no, qty, current_seq, lot_status, created_at, updated_at) VALUES ('L-20261019-001', 'MLCC-1206-C0G-1KV', 'WO-20261019-001', 100000, 0, 'WAIT', TIMESTAMP '2026-10-19 16:00:00', TIMESTAMP '2026-10-19 16:00:00');

UPDATE lot SET hold_reason = '소성(P50) 수율 95.97% - 동일 공정 다른 LOT 평균 99.12% 대비 3.15%p 하락' WHERE lot_no = 'L-20261014-001';

-- 8. 공정 이력 (history_id 1~24 고정, IDENTITY는 1000부터 발급되므로 충돌 없음)
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (1, 'L-20261012-001', 1, 'P10', 'TC-01', 'OP101', 100000, 99800, 200, TIMESTAMP '2026-10-12 08:00:00', TIMESTAMP '2026-10-12 12:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (2, 'L-20261012-001', 2, 'P20', 'PR-01', 'OP102', 99800, 99500, 300, TIMESTAMP '2026-10-12 13:00:00', TIMESTAMP '2026-10-12 17:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (3, 'L-20261012-001', 3, 'P30', 'ST-01', 'OP103', 99500, 99350, 150, TIMESTAMP '2026-10-12 18:00:00', TIMESTAMP '2026-10-12 22:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (4, 'L-20261012-001', 4, 'P40', 'CT-01', 'OP104', 99350, 99200, 150, TIMESTAMP '2026-10-13 08:00:00', TIMESTAMP '2026-10-13 11:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (5, 'L-20261012-001', 5, 'P50', 'SN-01', 'OP105', 99200, 98400, 800, TIMESTAMP '2026-10-13 12:00:00', TIMESTAMP '2026-10-14 08:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (6, 'L-20261012-001', 6, 'P60', 'TM-01', 'OP106', 98400, 98100, 300, TIMESTAMP '2026-10-14 09:00:00', TIMESTAMP '2026-10-14 13:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (7, 'L-20261012-001', 7, 'P70', 'PL-01', 'OP107', 98100, 97900, 200, TIMESTAMP '2026-10-14 14:00:00', TIMESTAMP '2026-10-14 18:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (8, 'L-20261012-001', 8, 'P80', 'TS-01', 'OP108', 97900, 96800, 1100, TIMESTAMP '2026-10-15 08:00:00', TIMESTAMP '2026-10-15 14:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (9, 'L-20261012-002', 1, 'P10', 'TC-01', 'OP101', 100000, 99750, 250, TIMESTAMP '2026-10-12 13:00:00', TIMESTAMP '2026-10-12 17:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (10, 'L-20261012-002', 2, 'P20', 'PR-01', 'OP102', 99750, 99400, 350, TIMESTAMP '2026-10-12 18:00:00', TIMESTAMP '2026-10-12 22:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (11, 'L-20261012-002', 3, 'P30', 'ST-01', 'OP103', 99400, 99200, 200, TIMESTAMP '2026-10-13 08:00:00', TIMESTAMP '2026-10-13 12:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (12, 'L-20261012-002', 4, 'P40', 'CT-01', 'OP104', 99200, 99050, 150, TIMESTAMP '2026-10-13 13:00:00', TIMESTAMP '2026-10-13 16:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (13, 'L-20261012-002', 5, 'P50', 'SN-02', 'OP105', 99050, 98100, 950, TIMESTAMP '2026-10-13 17:00:00', TIMESTAMP '2026-10-14 13:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (14, 'L-20261012-002', 6, 'P60', 'TM-01', 'OP106', 98100, 97850, 250, TIMESTAMP '2026-10-14 14:00:00', TIMESTAMP '2026-10-14 18:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (15, 'L-20261012-002', 7, 'P70', 'PL-01', 'OP107', 97850, 97600, 250, TIMESTAMP '2026-10-15 08:00:00', TIMESTAMP '2026-10-15 12:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (16, 'L-20261012-002A', 8, 'P80', 'TS-01', 'OP108', 48800, 48250, 550, TIMESTAMP '2026-10-15 15:00:00', TIMESTAMP '2026-10-15 21:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (17, 'L-20261012-002B', 8, 'P80', 'TS-02', 'OP109', 48800, NULL, NULL, TIMESTAMP '2026-10-20 15:00:00', NULL);
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (18, 'L-20261014-001', 1, 'P10', 'TC-01', 'OP101', 150000, 149700, 300, TIMESTAMP '2026-10-14 08:00:00', TIMESTAMP '2026-10-14 12:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (19, 'L-20261014-001', 2, 'P20', 'PR-01', 'OP102', 149700, 149250, 450, TIMESTAMP '2026-10-14 13:00:00', TIMESTAMP '2026-10-14 17:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (20, 'L-20261014-001', 3, 'P30', 'ST-01', 'OP103', 149250, 149000, 250, TIMESTAMP '2026-10-14 18:00:00', TIMESTAMP '2026-10-14 22:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (21, 'L-20261014-001', 4, 'P40', 'CT-01', 'OP104', 149000, 148800, 200, TIMESTAMP '2026-10-15 08:00:00', TIMESTAMP '2026-10-15 11:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (22, 'L-20261014-001', 5, 'P50', 'SN-01', 'OP105', 148800, 142800, 6000, TIMESTAMP '2026-10-15 12:00:00', TIMESTAMP '2026-10-16 08:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (23, 'L-20261014-002', 1, 'P10', 'TC-01', 'OP101', 150000, 149650, 350, TIMESTAMP '2026-10-14 13:00:00', TIMESTAMP '2026-10-14 17:00:00');
INSERT INTO lot_history (history_id, lot_no, seq, process_code, equipment_code, operator_id, in_qty, good_qty, defect_qty, track_in_at, track_out_at) VALUES (24, 'L-20261014-002', 2, 'P20', 'PR-01', 'OP102', 149650, 149200, 450, TIMESTAMP '2026-10-14 18:00:00', TIMESTAMP '2026-10-14 22:00:00');

-- 9. 불량 상세
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (1, 'D-SHT', 200);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (2, 'D-PRT', 300);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (3, 'D-STK', 150);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (4, 'D-CUT', 150);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (5, 'D-CRK', 500);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (5, 'D-DLM', 300);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (6, 'D-TRM', 300);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (7, 'D-PLT', 200);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (8, 'D-CAP', 700);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (8, 'D-IR', 400);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (9, 'D-SHT', 250);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (10, 'D-PRT', 350);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (11, 'D-STK', 200);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (12, 'D-CUT', 150);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (13, 'D-CRK', 600);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (13, 'D-DLM', 350);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (14, 'D-TRM', 250);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (15, 'D-PLT', 250);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (16, 'D-CAP', 350);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (16, 'D-IR', 200);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (18, 'D-SHT', 300);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (19, 'D-PRT', 450);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (20, 'D-STK', 250);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (21, 'D-CUT', 200);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (22, 'D-CRK', 4500);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (22, 'D-DLM', 1500);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (23, 'D-SHT', 350);
INSERT INTO lot_defect (history_id, defect_code, qty) VALUES (24, 'D-PRT', 450);

-- 10. LOT 계보 (원자재 투입 + 측정 공정 전 분할)
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('R-BT-C0G-2610-01', 'L-20261012-001', 'CONSUME', 20, TIMESTAMP '2026-10-12 08:00:00');
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('R-BT-C0G-2610-01', 'L-20261012-002', 'CONSUME', 20, TIMESTAMP '2026-10-12 13:00:00');
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('R-BT-X7R-2610-01', 'L-20261014-001', 'CONSUME', 30, TIMESTAMP '2026-10-14 08:00:00');
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('R-BT-X7R-2610-01', 'L-20261014-002', 'CONSUME', 30, TIMESTAMP '2026-10-14 13:00:00');
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('R-NI-2610-01', 'L-20261012-001', 'CONSUME', 5, TIMESTAMP '2026-10-12 13:00:00');
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('R-NI-2610-01', 'L-20261012-002', 'CONSUME', 5, TIMESTAMP '2026-10-12 18:00:00');
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('R-NI-2610-01', 'L-20261014-001', 'CONSUME', 5, TIMESTAMP '2026-10-14 13:00:00');
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('R-NI-2610-01', 'L-20261014-002', 'CONSUME', 5, TIMESTAMP '2026-10-14 18:00:00');
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('R-CU-2610-01', 'L-20261012-001', 'CONSUME', 2, TIMESTAMP '2026-10-14 09:00:00');
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('R-CU-2610-01', 'L-20261012-002', 'CONSUME', 2, TIMESTAMP '2026-10-14 14:00:00');
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('L-20261012-002', 'L-20261012-002A', 'SPLIT', 48800, TIMESTAMP '2026-10-15 13:00:00');
INSERT INTO lot_genealogy (parent_lot_no, child_lot_no, relation_type, qty, created_at) VALUES ('L-20261012-002', 'L-20261012-002B', 'SPLIT', 48800, TIMESTAMP '2026-10-15 13:00:00');

-- 11. 설비 비가동 이벤트 (고장 DOWN / 보전 PM)
INSERT INTO equipment_event (equipment_code, status, started_at, ended_at, reason) VALUES ('SN-01', 'DOWN', TIMESTAMP '2026-10-16 08:30:00', TIMESTAMP '2026-10-16 14:00:00', '3존 온도 편차(+12℃) 발생, 히터 점검');
INSERT INTO equipment_event (equipment_code, status, started_at, ended_at, reason) VALUES ('PL-01', 'PM', TIMESTAMP '2026-10-17 09:00:00', TIMESTAMP '2026-10-17 12:00:00', '정기 보전: 도금액 교체');

COMMIT;
