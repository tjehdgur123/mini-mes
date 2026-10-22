"""
Mini MES 설비 시뮬레이터 (표준 라이브러리만 사용)

설비 10대가 각각 스레드로 동작하며 실제 현장처럼 실적을 MES API로 보낸다.

    - 설비는 자기 공정의 대기(WAIT) LOT을 먼저 온 순서대로 가져가 Track-in -> 가공 -> Track-out
    - 같은 공정 설비가 2대면 같은 LOT을 동시에 잡으려 경쟁한다 (한 대만 성공해야 정상)
    - 네트워크 응답 유실: 요청은 서버에 반영됐는데 응답만 잃어버린 상황을 만들고 같은 멱등키로 재전송
    - 설비 고장: 가공 후 일정 확률로 DOWN -> 몇 초 뒤 IDLE 복구
    - 공정 이상: --drift 로 지정한 설비는 시간이 지날수록 불량률이 올라가 MES가 자동 HOLD
    - 품질 담당자: HOLD LOT을 일정 시간 뒤 해제
    - 생산 계획: 대기 LOT이 떨어지면 작업지시를 새로 발행

종료 시 실적 통계와 서버의 데이터 정합성 점검(/api/reports/integrity) 결과를 출력한다.

사용법:
    python equipment_simulator.py                 # 3분 동안 실행
    python equipment_simulator.py --duration 600 --loss 0.2 --drift SN-01
"""
import argparse
import json
import random
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from datetime import datetime

# ---------------------------------------------------------------------- 공장 설정

EQUIPMENT = [  # (설비, 공정)
    ("TC-01", "P10"), ("PR-01", "P20"), ("ST-01", "P30"), ("CT-01", "P40"),
    ("SN-01", "P50"), ("SN-02", "P50"), ("TM-01", "P60"), ("PL-01", "P70"),
    ("TS-01", "P80"), ("TS-02", "P80"),
]
# 공정별 기준 가공 시간(초). 소성과 측정이 길어서 설비가 2대씩 있다.
CYCLE_SEC = {"P10": 2.0, "P20": 2.0, "P30": 2.0, "P40": 1.5, "P50": 5.0, "P60": 2.0, "P70": 2.0, "P80": 3.5}
# 공정별 불량 코드와 평상시 불량률 범위
DEFECTS = {
    "P10": {"D-SHT": (0.001, 0.003)},
    "P20": {"D-PRT": (0.002, 0.004)},
    "P30": {"D-STK": (0.001, 0.003)},
    "P40": {"D-CUT": (0.001, 0.002)},
    "P50": {"D-CRK": (0.003, 0.006), "D-DLM": (0.002, 0.004)},
    "P60": {"D-TRM": (0.002, 0.003)},
    "P70": {"D-PLT": (0.001, 0.003)},
    "P80": {"D-CAP": (0.005, 0.008), "D-IR": (0.002, 0.004)},
}
BREAKDOWN_REASONS = {
    "P10": "슬러리 공급 펌프 압력 저하", "P20": "스크린 마스크 막힘", "P30": "적층 정렬 카메라 오류",
    "P40": "절단 블레이드 마모", "P50": "히터 온도 편차", "P60": "디핑 헤드 막힘",
    "P70": "도금액 농도 이탈", "P80": "측정 프로브 접촉 불량",
}
PRODUCTS = ["MLCC-1206-C0G-1KV", "MLCC-0805-X7R-100V"]

# ---------------------------------------------------------------------- 공용 상태

stop_event = threading.Event()
print_lock = threading.Lock()
stats = Counter()
stats_lock = threading.Lock()


def count(key, n=1):
    with stats_lock:
        stats[key] += n


def log(who, msg):
    with print_lock:
        print(f"[{datetime.now():%H:%M:%S}] {who:6} {msg}", flush=True)


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=20) as res:
                return res.status, json.loads(res.read().decode("utf-8") or "null")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8")
            try:
                return e.code, json.loads(raw)
            except ValueError:
                return e.code, {"code": "HTTP_" + str(e.code), "message": raw[:200]}
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            return 0, {"code": "NETWORK", "message": str(e)}

    def send_reliably(self, who, method, path, body, loss_rate):
        """
        멱등키가 들어 있는 변경 요청 전송.
        loss_rate 확률로 '서버는 처리했지만 응답이 유실된' 상황을 만들고, 같은 키로 재전송한다.
        서버가 replayed=true 로 같은 결과를 돌려주면 이중 처리 없이 복구된 것이다.
        """
        status, res = self.call(method, path, body)
        if status == 0:  # 진짜 네트워크 오류: 같은 키로 재시도
            count("network_error")
            time.sleep(1)
            status, res = self.call(method, path, body)
        if status == 200 and random.random() < loss_rate:
            count("response_lost")
            log(who, f"  ! 응답 유실 가정 -> 같은 요청키로 재전송 ({path.split('/')[-1]})")
            status2, res2 = self.call(method, path, body)
            if status2 == 200 and res2.get("replayed") is True:
                count("replay_ok")
            else:
                count("replay_unexpected")
                log(who, f"  !! 재전송 응답이 예상과 다름: {status2} {res2}")
            return status2, res2
        return status, res


# ---------------------------------------------------------------------- 설비

class Equipment(threading.Thread):
    def __init__(self, api, code, process, args):
        super().__init__(daemon=True, name=code)
        self.api, self.code, self.process, self.args = api, code, process, args
        self.started_at = time.time()   # drift 기준 시각 (설비 점검 후 다시 0부터)
        self.consecutive_holds = 0

    def run(self):
        self.resume_running_lot()
        while not stop_event.is_set():
            lot = self.pick_and_track_in()
            if lot is None:
                time.sleep(0.5 + random.random() * 0.5)
                continue
            self.work_and_track_out(*lot)
            if stop_event.is_set():
                continue
            if self.consecutive_holds >= 2:
                # 같은 설비에서 HOLD가 연속 발생 -> 설비 문제로 보고 보전(PM) 후 정상화
                self.stop("PM", "연속 수율 이상 HOLD -> 설비 점검/교정", random.uniform(5, 8))
                self.started_at = time.time()
                self.consecutive_holds = 0
                count("maintenance")
            elif random.random() < self.args.breakdown:
                self.stop("DOWN", BREAKDOWN_REASONS[self.process], random.uniform(3, 8))
                count("breakdown")

    def resume_running_lot(self):
        """시뮬레이터를 다시 켰을 때 이 설비에서 가공 중이던 LOT이 있으면 먼저 마무리"""
        s, eqs = self.api.call("GET", "/equipment")
        if s != 200:
            return
        me = next((e for e in eqs if e["equipmentCode"] == self.code), None)
        if me and me.get("currentLot"):
            s, lot = self.api.call("GET", f"/lots/{me['currentLot']}")
            if s == 200:
                running = [h["history"] for h in lot["histories"]
                           if h["history"]["equipmentCode"] == self.code and h["history"].get("trackOutAt") is None]
                if running:
                    log(self.code, f"↺ 가공 중이던 {me['currentLot']} 이어서 진행")
                    self.work_and_track_out(me["currentLot"], running[0]["inQty"])

    def pick_and_track_in(self):
        status, wip = self.api.call("GET", "/wip")
        if status != 200:
            return None
        waiting = sorted((w for w in wip
                          if w["lotStatus"] == "WAIT" and w.get("nextProcessCode") == self.process),
                         key=lambda w: (w.get("updatedAt") or "", w["lotNo"]))
        for w in waiting:
            body = {"equipmentCode": self.code, "operatorId": "SIM-" + self.code, "requestKey": str(uuid.uuid4())}
            status, res = self.api.send_reliably(self.code, "POST", f"/lots/{w['lotNo']}/track-in", body,
                                                 self.args.loss)
            if status == 200:
                count("track_in")
                log(self.code, f"▶ Track-in  {w['lotNo']}  {self.process} {res['processName']}  {res['inQty']:,}")
                return w["lotNo"], res["inQty"]
            code = res.get("code")
            if code == "INVALID_LOT_STATUS":
                count("lost_race")  # 같은 공정의 다른 설비가 먼저 가져감 -> 정상
                continue
            if code == "LOCK_TIMEOUT":
                count("lock_timeout")
                continue
            count("track_in_error")
            log(self.code, f"  x Track-in 실패 {w['lotNo']}: {status} {code} {res.get('message')}")
            return None
        return None

    def defect_rate_multiplier(self):
        """--drift 설비는 시작 후 drift_after 초부터 불량률이 점점 커진다 (히터 열화 등)"""
        if self.code not in self.args.drift:
            return 1.0
        elapsed = time.time() - self.started_at
        if elapsed < self.args.drift_after:
            return 1.0
        return min(12.0, 1.0 + (elapsed - self.args.drift_after) / 10.0)

    def work_and_track_out(self, lot_no, in_qty):
        time.sleep(CYCLE_SEC[self.process] * self.args.speed * random.uniform(0.8, 1.2))

        mult = self.defect_rate_multiplier()
        defects = {}
        for code, (lo, hi) in DEFECTS[self.process].items():
            q = round(in_qty * random.uniform(lo, hi) * (mult if code in ("D-CRK", "D-DLM", "D-CAP") else 1))
            if q > 0:
                defects[code] = q
        total = sum(defects.values())
        if total > in_qty:  # 극단적인 경우 보정
            defects = {next(iter(defects)): in_qty}
            total = in_qty
        body = {"goodQty": in_qty - total,
                "defects": [{"defectCode": c, "qty": q} for c, q in defects.items()],
                "requestKey": str(uuid.uuid4())}
        status, res = self.api.send_reliably(self.code, "POST", f"/lots/{lot_no}/track-out", body, self.args.loss)
        if status != 200:
            count("track_out_error")
            log(self.code, f"  x Track-out 실패 {lot_no}: {status} {res}")
            return
        count("track_out")
        st = res["lotStatus"]
        mark = {"HOLD": "■ HOLD", "FINISHED": "★ 완료"}.get(st, "→ " + (res.get("nextProcessCode") or st))
        log(self.code, f"◀ Track-out {lot_no}  {self.process}  양품 {res['goodQty']:,}  "
                       f"수율 {res['yieldPct']}%  {mark}")
        self.consecutive_holds = self.consecutive_holds + 1 if st == "HOLD" else 0
        if st == "HOLD":
            count("auto_hold")
            log(self.code, f"  ■ 자동 HOLD 사유: {res.get('holdReason')}")
        elif st == "FINISHED":
            count("finished_lots")
            count("finished_qty", res["goodQty"])

    def stop(self, status, reason, seconds):
        """비가동(DOWN 고장 / PM 보전) 등록 -> 대기 -> IDLE 복구"""
        s, r = self.api.send_reliably(self.code, "POST", f"/equipment/{self.code}/status",
                                      {"status": status, "reason": reason, "requestKey": str(uuid.uuid4())},
                                      self.args.loss)
        if s != 200:
            log(self.code, f"  x 비가동 등록 실패: {s} {r}")
            return
        down = seconds * self.args.speed
        label = "고장 DOWN" if status == "DOWN" else "보전 PM"
        log(self.code, f"✖ {label}: {reason} ({down:.0f}초)")
        stop_event.wait(down)
        self.api.send_reliably(self.code, "POST", f"/equipment/{self.code}/status",
                               {"status": "IDLE", "reason": "복구 완료", "requestKey": str(uuid.uuid4())},
                               self.args.loss)
        log(self.code, "✔ 복구 IDLE")


# ---------------------------------------------------------------------- 지원 역할

class Planner(threading.Thread):
    """착수 전(공정 0) 대기 LOT이 부족하면 작업지시를 발행해 라인을 계속 돌린다"""

    def __init__(self, api, args):
        super().__init__(daemon=True, name="PLAN")
        self.api, self.args, self.turn = api, args, 0

    def run(self):
        while not stop_event.is_set():
            s, wip = self.api.call("GET", "/wip")
            if s == 200:
                not_started = [w for w in wip if w["lotStatus"] == "WAIT" and (w.get("currentSeq") or 0) == 0]
                active = [w for w in wip if (w.get("currentSeq") or 0) > 0 or w["lotStatus"] != "WAIT"]
                if len(not_started) < 2 and len(active) < self.args.max_wip:
                    item = PRODUCTS[self.turn % len(PRODUCTS)]
                    self.turn += 1
                    s, wo = self.api.call("POST", "/work-orders",
                                          {"itemCode": item, "planQty": self.args.lot_size * 3,
                                           "lotSize": self.args.lot_size})
                    if s == 201:
                        count("work_orders")
                        log("PLAN", f"＋ 작업지시 {wo['woNo']} {item} LOT {len(wo['lots'])}개 발행")
                    else:
                        log("PLAN", f"  x 작업지시 실패: {s} {wo}")
            stop_event.wait(3)


class QualityInspector(threading.Thread):
    """HOLD LOT을 일정 시간 뒤 해제 (현장에서는 재측정·분석 후 판정)"""

    def __init__(self, api, args):
        super().__init__(daemon=True, name="QA")
        self.api, self.args = api, args
        self.first_seen = {}

    def run(self):
        while not stop_event.is_set():
            s, wip = self.api.call("GET", "/wip")
            if s == 200:
                now = time.time()
                for w in (w for w in wip if w["lotStatus"] == "HOLD"):
                    first = self.first_seen.setdefault(w["lotNo"], now)
                    if now - first >= self.args.hold_release_after:
                        s2, r = self.api.call("POST", f"/lots/{w['lotNo']}/release-hold",
                                              {"operatorId": "QA-SIM", "reason": "재측정 결과 규격 내, 조건부 진행"})
                        if s2 == 200:
                            count("hold_released")
                            log("QA", f"◇ HOLD 해제 {w['lotNo']} -> {r['lotStatus']}")
                        self.first_seen.pop(w["lotNo"], None)
            stop_event.wait(2)


# ---------------------------------------------------------------------- main

def main():
    p = argparse.ArgumentParser(description="Mini MES 설비 시뮬레이터")
    p.add_argument("--base", default="http://localhost:8080/api")
    p.add_argument("--duration", type=int, default=180, help="실행 시간(초)")
    p.add_argument("--speed", type=float, default=1.0, help="가공 시간 배율 (작을수록 빠름)")
    p.add_argument("--loss", type=float, default=0.1, help="응답 유실 확률")
    p.add_argument("--breakdown", type=float, default=0.03, help="가공 후 고장 확률")
    p.add_argument("--drift", nargs="*", default=["SN-01"], help="불량률이 점점 오르는 설비")
    p.add_argument("--drift-after", type=float, default=40, help="drift 시작 시점(초)")
    p.add_argument("--hold-release-after", type=float, default=15, help="HOLD 해제까지 대기(초)")
    p.add_argument("--lot-size", type=int, default=50000)
    p.add_argument("--max-wip", type=int, default=12, help="진행 중 LOT이 이 수 이상이면 작업지시 발행 보류")
    args = p.parse_args()

    api = Api(args.base)
    s, _ = api.call("GET", "/wip")
    if s != 200:
        print("API 서버에 연결할 수 없습니다:", args.base)
        return 1

    print(f"시뮬레이션 시작: {args.duration}초, 응답 유실 {args.loss:.0%}, 고장 {args.breakdown:.0%}, "
          f"불량 증가 설비 {args.drift or '없음'}")
    workers = [Equipment(api, code, proc, args) for code, proc in EQUIPMENT]
    helpers = [Planner(api, args), QualityInspector(api, args)]
    for t in helpers + workers:
        t.start()

    try:
        stop_event.wait(args.duration)
    except KeyboardInterrupt:
        pass
    stop_event.set()
    print("\n종료 신호: 진행 중인 가공을 마무리합니다...")
    for t in workers:
        t.join(timeout=60)

    print("\n================ 시뮬레이션 결과 ================")
    rows = [
        ("작업지시 발행", stats["work_orders"]),
        ("Track-in / Track-out", f"{stats['track_in']} / {stats['track_out']}"),
        ("완료 LOT / 완료 수량", f"{stats['finished_lots']} / {stats['finished_qty']:,}"),
        ("같은 LOT 경쟁에서 밀림 (정상)", stats["lost_race"]),
        ("응답 유실 -> 재전송", stats["response_lost"]),
        ("  재전송이 이중 처리 없이 복구됨", stats["replay_ok"]),
        ("  재전송 응답 이상", stats["replay_unexpected"]),
        ("자동 HOLD / 해제", f"{stats['auto_hold']} / {stats['hold_released']}"),
        ("설비 고장 / 연속 HOLD로 인한 점검", f"{stats['breakdown']} / {stats['maintenance']}"),
        ("오류 (Track-in / Track-out)", f"{stats['track_in_error']} / {stats['track_out_error']}"),
    ]
    for k, v in rows:
        print(f"  {k:<32} {v}")

    s, issues = api.call("GET", "/reports/integrity")
    print(f"\n데이터 정합성 점검: {'이상 없음' if s == 200 and not issues else issues}")
    ok = (s == 200 and not issues and stats["replay_unexpected"] == 0
          and stats["track_in_error"] == 0 and stats["track_out_error"] == 0)
    print("결과:", "정상" if ok else "확인 필요")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
