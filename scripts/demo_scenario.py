"""
Mini MES API 시나리오 테스트 (표준 라이브러리만 사용, 설치 필요 없음)

사용법:
    1) db/01_schema.sql, db/02_seed.sql 실행 (데이터 초기화)
    2) API 서버 실행 (MiniMesApplication)
    3) python demo_scenario.py

확인하는 것:
    [1] 작업지시 생성 -> LOT 자동 발행
    [2] 8개 공정 Track-in/out 정상 흐름
    [3] 멱등키 재전송: 같은 요청을 다시 보내도 한 번만 반영
    [4] 업무 규칙 위반 차단: 다른 공정 설비 투입, 수량 불일치, 다른 공정 불량 코드
    [5] 수율 이상 자동 HOLD -> 투입 차단 -> HOLD 해제
    [6] 동시성: 같은 LOT에 동시 요청 10건 -> 정확히 1건만 성공
"""
import json
import sys
import threading
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080/api"

FG = "MLCC-1206-C0G-1KV"
EQUIPMENT = {"P10": "TC-01", "P20": "PR-01", "P30": "ST-01", "P40": "CT-01",
             "P50": "SN-01", "P60": "TM-01", "P70": "PL-01", "P80": "TS-01"}
DEFECT = {"P10": "D-SHT", "P20": "D-PRT", "P30": "D-STK", "P40": "D-CUT",
          "P50": "D-CRK", "P60": "D-TRM", "P70": "D-PLT", "P80": "D-CAP"}
ROUTING = ["P10", "P20", "P30", "P40", "P50", "P60", "P70", "P80"]

passed = 0
failed = 0


def call(method, path, body=None):
    """(HTTP 상태코드, JSON 응답) 반환. 4xx/5xx도 예외 없이 돌려준다."""
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as res:
            return res.status, json.loads(res.read().decode("utf-8") or "null")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw}


def check(title, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print(f"  [PASS] {title}")
    else:
        failed += 1
        print(f"  [FAIL] {title}  {detail}")


def key():
    return str(uuid.uuid4())


def track_in(lot, equipment, request_key=None):
    return call("POST", f"/lots/{lot}/track-in",
                {"equipmentCode": equipment, "operatorId": "DEMO", "requestKey": request_key or key()})


def track_out(lot, good, defects, request_key=None):
    return call("POST", f"/lots/{lot}/track-out",
                {"goodQty": good,
                 "defects": [{"defectCode": c, "qty": q} for c, q in defects.items() if q > 0],
                 "requestKey": request_key or key()})


def run_process(lot, process, in_qty, defect_rate):
    """한 공정 Track-in -> Track-out. 양품 수량과 Track-out 응답을 돌려준다."""
    s, r = track_in(lot, EQUIPMENT[process])
    if s != 200:
        raise RuntimeError(f"Track-in 실패 {lot} {process}: {s} {r}")
    defect = round(in_qty * defect_rate)
    s, r = track_out(lot, in_qty - defect, {DEFECT[process]: defect})
    if s != 200:
        raise RuntimeError(f"Track-out 실패 {lot} {process}: {s} {r}")
    return in_qty - defect, r


def main():
    s, _ = call("GET", "/wip")
    if s != 200:
        print("API 서버에 연결할 수 없습니다. 서버를 먼저 실행하세요.")
        sys.exit(1)

    # ------------------------------------------------------------------ [1]
    print("\n[1] 작업지시 생성")
    s, wo = call("POST", "/work-orders", {"itemCode": FG, "planQty": 250000, "lotSize": 100000})
    check("201 Created", s == 201, f"{s} {wo}")
    check("LOT 3개 발행 (100,000 / 100,000 / 50,000)",
          [l["qty"] for l in wo.get("lots", [])] == [100000, 100000, 50000], wo)
    lot_a, lot_b, lot_c = (l["lotNo"] for l in wo["lots"])
    print(f"  작업지시 {wo['woNo']} / LOT {lot_a}, {lot_b}, {lot_c}")

    # ------------------------------------------------------------------ [3] 멱등
    print("\n[3] 멱등키: 같은 Track-in 요청을 두 번 전송")
    k = key()
    s1, r1 = track_in(lot_a, "TC-01", k)
    s2, r2 = track_in(lot_a, "TC-01", k)
    check("첫 요청 정상 처리", s1 == 200 and r1.get("replayed") is False, r1)
    check("재전송은 replayed=true, 같은 결과 반환", s2 == 200 and r2.get("replayed") is True
          and r2.get("trackInAt") == r1.get("trackInAt"), r2)
    k_out = key()
    s1, r1 = track_out(lot_a, 99800, {"D-SHT": 200}, k_out)
    s2, r2 = track_out(lot_a, 99800, {"D-SHT": 200}, k_out)
    check("Track-out 재전송도 한 번만 반영", s1 == 200 and s2 == 200 and r2.get("replayed") is True, r2)
    s, detail = call("GET", f"/lots/{lot_a}")
    check("이력은 1건, LOT 수량 99,800", len(detail["histories"]) == 1 and detail["lot"]["qty"] == 99800, detail)

    # ------------------------------------------------------------------ [4] 규칙 위반
    print("\n[4] 업무 규칙 위반 차단")
    s, r = track_in(lot_a, "SN-01")  # 다음 공정은 P20(인쇄)인데 소성로에 투입
    check("다른 공정 설비 투입 -> 409 EQUIPMENT_PROCESS_MISMATCH",
          s == 409 and r.get("code") == "EQUIPMENT_PROCESS_MISMATCH", r)
    s, r = track_out(lot_a, 1, {})
    check("RUN 아닌 LOT Track-out -> 409 INVALID_LOT_STATUS", s == 409 and r.get("code") == "INVALID_LOT_STATUS", r)
    track_in(lot_a, "PR-01")
    s, r = track_out(lot_a, 99000, {"D-PRT": 100})  # 99,000 + 100 != 99,800
    check("양품+불량 != 투입 -> 400 QTY_MISMATCH", s == 400 and r.get("code") == "QTY_MISMATCH", r)
    s, r = track_out(lot_a, 99700, {"D-CRK": 100})  # 소성 불량 코드를 인쇄 공정에 등록
    check("다른 공정 불량 코드 -> 400 DEFECT_CODE_PROCESS_MISMATCH",
          s == 400 and r.get("code") == "DEFECT_CODE_PROCESS_MISMATCH", r)
    s, r = track_out(lot_a, 99500, {"D-PRT": 300})
    check("올바른 값으로 재시도하면 정상 처리", s == 200 and r.get("lotStatus") == "WAIT", r)

    # ------------------------------------------------------------------ [2] 정상 흐름
    print("\n[2] 정상 흐름: 나머지 6개 공정 진행")
    qty = 99500
    for p in ROUTING[2:]:
        rate = {"P50": 0.008, "P80": 0.011}.get(p, 0.002)
        qty, r = run_process(lot_a, p, qty, rate)
        print(f"  {p} {EQUIPMENT[p]:6} -> 양품 {qty:>7,}  수율 {r['yieldPct']}%  상태 {r['lotStatus']}")
    check("마지막 공정 후 FINISHED", r["lotStatus"] == "FINISHED", r)
    s, r = track_in(lot_a, "TC-01")
    check("완료된 LOT 추가 투입 -> 409", s == 409, r)

    # ------------------------------------------------------------------ [5] 자동 HOLD
    print("\n[5] 소성 공정 불량 급증 -> 자동 HOLD")
    qty = 100000
    for p in ROUTING[:4]:
        qty, _ = run_process(lot_b, p, qty, 0.002)
    qty_before = qty
    qty, r = run_process(lot_b, "P50", qty, 0.05)  # 5% 불량
    print(f"  P50 수율 {r['yieldPct']}% -> {r['lotStatus']}: {r.get('holdReason')}")
    check("자동 HOLD", r["lotStatus"] == "HOLD", r)
    s, r = track_in(lot_b, "TM-01")
    check("HOLD LOT 투입 차단 -> 409 INVALID_LOT_STATUS", s == 409 and r.get("code") == "INVALID_LOT_STATUS", r)
    s, r = call("POST", f"/lots/{lot_b}/release-hold", {"operatorId": "QA01", "reason": "샘플 재측정 결과 규격 내"})
    check("HOLD 해제 -> WAIT", s == 200 and r.get("lotStatus") == "WAIT", r)
    s, r = track_in(lot_b, "TM-01")
    check("해제 후 다음 공정 투입 가능", s == 200, r)
    track_out(lot_b, qty, {})

    # ------------------------------------------------------------------ [6] 동시성
    print("\n[6] 동시성: 같은 LOT에 서로 다른 요청 10건 동시 전송")
    results = []
    barrier = threading.Barrier(10)

    def worker():
        barrier.wait()
        results.append(track_in(lot_c, "TC-01"))

    threads = [threading.Thread(target=worker) for _ in range(10)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    ok = [r for s, r in results if s == 200]
    rejected = [r.get("code") for s, r in results if s != 200]
    print(f"  성공 {len(ok)}건, 거절 {len(rejected)}건 {sorted(set(rejected))}")
    check("정확히 1건만 투입", len(ok) == 1, results)
    s, detail = call("GET", f"/lots/{lot_c}")
    check("이력 1건만 생성", len(detail["histories"]) == 1, detail)

    print("\n[6-2] 같은 멱등키로 동시 재전송 10건")
    results.clear()
    barrier = threading.Barrier(10)
    k = key()

    def resend():
        barrier.wait()
        results.append(track_out(lot_c, 49900, {"D-SHT": 100}, k))

    threads = [threading.Thread(target=resend) for _ in range(10)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    real = [r for s, r in results if s == 200 and r.get("replayed") is False]
    replay = [r for s, r in results if s == 200 and r.get("replayed") is True]
    print(f"  실제 처리 {len(real)}건, 재전송 응답 {len(replay)}건")
    check("실제 처리 1건 + 나머지 9건은 재전송 응답", len(real) == 1 and len(replay) == 9, results)
    s, detail = call("GET", f"/lots/{lot_c}")
    check("LOT 수량 49,900 (이중 차감 없음)", detail["lot"]["qty"] == 49900, detail["lot"])

    # ------------------------------------------------------------------ 결과
    print("\n[조회] 완료된 LOT 상세")
    s, r = call("GET", f"/lots/{lot_a}")
    print(f"  {lot_a}: 상태 {r['lot']['lotStatus']}, 최종 수량 {r['lot']['qty']:,}, 공정 이력 {len(r['histories'])}건")

    print(f"\n결과: PASS {passed} / FAIL {failed}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
