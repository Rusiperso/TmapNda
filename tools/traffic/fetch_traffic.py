"""국가교통정보센터 교통소통정보(고속도로 정체)와 가변속도제한표지(VSL)를 받아 작은 파일 두 개로 정리한다.

- jam.json : 지금 정체 중인 고속도로 구간(이어진 구간은 하나로 묶음)
- vsl.json : 제한속도가 기본값에서 바뀐 가변속도제한표지

GitHub Actions(.github/workflows/traffic.yml)가 15분마다 돌려서 결과를 traffic-data 가지에 올리고,
서버(Cloudflare 일꾼)가 그 파일을 읽어 앱에 내려준다. 인증키는 환경변수 ITS_API_KEY.
"""
import gzip
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request

BASE = "https://openapi.its.go.kr:9443"
HERE = os.path.dirname(os.path.abspath(__file__))
LINKS_PATH = os.path.join(HERE, "links_ex.json.gz")

# 고속국도 중 길이순 상위 노선(전체 길이의 약 90%). 노선번호(ROAD_NO)에 10을 곱해 4자리로 만든 값이 API의 routeNo.
ROUTES = ["1", "55", "15", "35", "45", "10", "25", "30", "65", "50", "12", "17", "20", "400", "100",
          "60", "29", "40", "27", "301", "110", "14", "151", "52"]

JAM_MAX_KMH = 40      # 이 속도 이하면 정체
JAM_MIN_KMH = 6       # 이보다 느린 값은 측정 안 된 값으로 보고 버린다
CHAIN_GAP_M = 1500    # 같은 방향에서 이 거리 안에 이어지면 한 정체로 묶는다


def get(path: str, params: dict, tries: int = 3):
    url = f"{BASE}/{path}?" + urllib.parse.urlencode(params)
    last = None
    for i in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                d = json.loads(r.read().decode("utf-8"))
            code = d.get("header", {}).get("resultCode")
            if code != 0:
                raise RuntimeError(f"{path} resultCode={code} {d.get('header', {}).get('resultMsg')}")
            return d["body"]["items"]
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(2 * (i + 1))
    raise last


def dist_m(lon1, lat1, lon2, lat2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def bearing(lon1, lat1, lon2, lat2):
    p1, p2, dl = math.radians(lat1), math.radians(lat2), math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def load_links():
    return json.loads(gzip.decompress(open(LINKS_PATH, "rb").read()))


def build_jams(key: str, links: dict):
    """링크 표: linkId -> [lon0,lat0,lonM,latM,lon1,lat1,길이m,도로명,최대속도,등급]"""
    cong = []
    errors = 0
    for no in ROUTES:
        route = f"{int(no) * 10:04d}"
        try:
            items = get("trafficInfo", {"apiKey": key, "type": "ex", "routeNo": route, "drcType": "all", "getType": "json"})
        except Exception as e:  # noqa: BLE001
            errors += 1
            print(f"[경고] 노선 {route} 실패: {e}", file=sys.stderr)
            continue
        for e in items:
            l = links.get(e.get("linkId"))
            if not l:
                continue
            try:
                sp = float(e.get("speed") or 0)
            except ValueError:
                continue
            if sp < JAM_MIN_KMH or sp > JAM_MAX_KMH:
                continue
            cong.append({
                "id": e["linkId"], "s": e.get("startNodeId"), "t": e.get("endNodeId"),
                "sp": sp, "l": l, "road": e.get("roadName") or l[7], "dir": e.get("roadDrcType") or "",
            })
    # 같은 방향에서 끝 노드 = 시작 노드로 이어지는 구간을 하나로 묶는다.
    by_start = {c["s"]: c for c in cong}
    has_prev = {c["t"] for c in cong}
    used = set()
    segs = []
    for c in cong:
        if c["s"] in has_prev:
            continue  # 앞 구간이 있으면 머리가 아님
        chain = [c]
        used.add(c["id"])
        cur = c
        while cur["t"] in by_start and by_start[cur["t"]]["id"] not in used:
            cur = by_start[cur["t"]]
            chain.append(cur)
            used.add(cur["id"])
        segs.append(chain)
    for c in cong:  # 고리 모양이라 머리가 없던 것
        if c["id"] not in used:
            segs.append([c])
            used.add(c["id"])
    out = []
    for ch in segs:
        head, tail = ch[0]["l"], ch[-1]["l"]
        length = sum(x["l"][6] for x in ch)
        spd = sum(x["sp"] * x["l"][6] for x in ch) / max(length, 1)
        heading = bearing(head[0], head[1], head[4], head[5])
        out.append({
            "x": round(head[0], 5), "y": round(head[1], 5),     # 머리(들어서게 되는 쪽) 위치
            "x2": round(tail[4], 5), "y2": round(tail[5], 5),    # 꼬리 위치
            "v": int(round(spd)), "len": int(length), "h": int(round(heading)),
            "r": ch[0]["road"], "d": ch[0]["dir"],
        })
    return out, errors


def build_vsl(key: str, links: dict):
    items = get("vslInfo", {"apiKey": key, "type": "all", "drcType": "all", "minX": 124, "maxX": 132, "minY": 33, "maxY": 39, "getType": "json"})
    out = []
    for e in items:
        try:
            lim, dflt = int(e["limitSpeed"]), int(e["defLmtSpeed"])
        except (KeyError, ValueError):
            continue
        if lim >= dflt or lim <= 0:
            continue  # 기본값이면 평소와 같으니 안 알린다(낮아진 곳만)
        # coordX가 위도, coordY가 경도로 오는 점에 주의
        lat, lon = float(e["coordX"]), float(e["coordY"])
        h = -1
        l = links.get(e.get("linkId"))
        if l:
            h = int(round(bearing(l[0], l[1], l[4], l[5])))
        out.append({"x": round(lon, 5), "y": round(lat, 5), "v": lim, "d": dflt, "h": h, "n": e.get("roadNo", "")})
    return out


def main():
    key = os.environ.get("ITS_API_KEY", "").strip()
    if not key:
        print("ITS_API_KEY 가 없습니다", file=sys.stderr)
        return 2
    outdir = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(outdir, exist_ok=True)
    now = int(time.time() * 1000)
    links = load_links()
    jams, errors = build_jams(key, links)
    vsl = build_vsl(key, links)
    # 노선이 절반 넘게 실패하면 낡은 값이 더 낫다 - 파일을 새로 안 쓰고 실패로 끝낸다.
    if errors > len(ROUTES) // 2:
        print("노선 실패가 너무 많아 갱신하지 않습니다", file=sys.stderr)
        return 1
    json.dump({"updatedAt": now, "items": jams}, open(os.path.join(outdir, "jam.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    json.dump({"updatedAt": now, "items": vsl}, open(os.path.join(outdir, "vsl.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print(f"정체 {len(jams)}곳, 제한속도 바뀜 {len(vsl)}곳, 노선 실패 {errors}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
