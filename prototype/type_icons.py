"""
타입 아이콘 색 인식 프로토타입.
상대 패널 오른쪽의 타입 아이콘 색을 읽어 타입 조합을 판별한다.
색 기준은 우리가 가진 18타입 RGB.
"""
import cv2, numpy as np, os, sys, json
HERE = os.path.dirname(os.path.abspath(__file__))
DBG = os.path.join(HERE, "debug_crops"); os.makedirs(DBG, exist_ok=True)

KO = {"Normal":"노말","Fire":"불꽃","Water":"물","Electric":"전기","Grass":"풀","Ice":"얼음",
      "Fighting":"격투","Poison":"독","Ground":"땅","Flying":"비행","Psychic":"에스퍼","Bug":"벌레",
      "Rock":"바위","Ghost":"고스트","Dragon":"드래곤","Dark":"악","Steel":"강철","Fairy":"페어리"}
def load_dex():
    p = os.path.join(HERE, "pokedex.json")
    if not os.path.exists(p): return []
    d = json.load(open(p, encoding="utf-8")); dex = d.get("dex", d)
    return [(m["name"], frozenset(KO[t] for t in m["types"])) for m in dex.values()
            if isinstance(m, dict) and m.get("types")]
def candidates(dex, typeset):
    ts = frozenset(typeset)
    return [n for n, s in dex if s == ts]

# 타입 -> RGB (★=게임 스샷에서 보정한 실제 아이콘색, 나머지는 op.gg값 임시)
TYPE_RGB = {
    "노말": (153,153,153), "불꽃": (208,65,58), "물": (72,122,226), "전기": (236,190,72),
    "풀": (94,155,63), "얼음": (66,216,255), "격투": (255,162,2), "독": (150,80,200),
    "땅": (135,83,47), "비행": (138,177,229), "에스퍼": (216,85,125), "벌레": (159,164,36),
    "바위": (188,184,137), "고스트": (135,77,195), "드래곤": (96,108,205), "악": (79,71,71),
    "강철": (124,162,182), "페어리": (216,123,223),
}
NAMES = list(TYPE_RGB.keys())
COLS = np.array([TYPE_RGB[t] for t in NAMES], np.float32)  # RGB
# 타입이 아닌 색(패널 크림슨/보라/흰 심볼/♂성별 파랑/회색벽 등) -> 무시
REJECT_RGB = [(99,23,47),(110,36,44),(101,36,58),(98,34,76),(98,27,73),(120,40,66),(70,18,38),
              (32,60,205),(72,101,198),(45,70,200),(66,97,200),   # ♂ 성별 파랑
              (165,150,66),(159,145,68),(150,129,57),(200,182,83),(236,215,120),  # 노란 벽/기둥
              (255,255,255),(235,235,235),(150,150,150),(90,88,92),(60,55,58)]
ALLCOLS = np.vstack([COLS, np.array(REJECT_RGB, np.float32)])
NT = len(NAMES)

# 타입 아이콘 영역(패널 대비 비율)
TCOL_X0, TCOL_X1 = 0.833, 0.890
ICON_Y0, ICON_Y1 = 0.06, 0.40   # 패널 높이 대비, 타입 아이콘 세로 구간(성별 ♂ 제외)
DIST = 55
MINPIX = 12

def find_panels(shot):
    """오른쪽 상대 열에서 크림슨 패널들의 세로 위치(y0,y1)를 자동 검출."""
    H, W = shot.shape[:2]
    reg = shot[:, int(W*0.76):int(W*0.90)]
    hsv = cv2.cvtColor(reg, cv2.COLOR_BGR2HSV)
    Hh, S, V = cv2.split(hsv)
    mag = (((Hh >= 156) | (Hh <= 8)) & (S > 50) & (V > 30))
    rows = mag.sum(1).astype(float)
    on = rows > rows.max()*0.30
    bands = []; i = 0
    while i < H:
        if on[i]:
            j = i
            while j < H and on[j]: j += 1
            if (j - i) > H*0.075: bands.append((i, j))   # 이름 배너(얇음) 제외, 패널만
            i = j
        else:
            i += 1
    return bands

def classify(region_bgr):
    rgb = cv2.cvtColor(region_bgr, cv2.COLOR_BGR2RGB).reshape(-1, 3).astype(np.float32)
    # 각 픽셀 -> 타입색+제외색 중 가장 가까운 것
    d = np.sqrt(((rgb[:, None, :] - ALLCOLS[None, :, :]) ** 2).sum(2))
    nearest = d.argmin(1); mind = d.min(1)
    counts = np.zeros(NT, int)
    for i in range(NT):
        counts[i] = int(((nearest == i) & (mind < DIST)).sum())  # 제외색으로 간 픽셀은 제외됨
    order = counts.argsort()[::-1]
    return [(NAMES[i], int(counts[i])) for i in order if counts[i] >= MINPIX][:3]

def main():
    shot = cv2.imread(sys.argv[1] if len(sys.argv) > 1 else "shot.png.jpg")
    if shot is None: print("스크린샷 못 읽음"); return
    H, W = shot.shape[:2]; print(f"스크린샷 {W}x{H}")
    dex = load_dex()
    x0, x1 = int(W*TCOL_X0), int(W*TCOL_X1)
    panels = find_panels(shot)
    print(f"검출된 패널 {len(panels)}개\n")
    for s, (py0, py1) in enumerate(panels):
        bh = py1 - py0
        cy0 = int(py0 + bh*ICON_Y0); cy1 = int(py0 + bh*ICON_Y1)
        reg = shot[cy0:cy1, x0:x1]
        cv2.imwrite(os.path.join(DBG, f"treg{s+1}.png"), reg)
        res = classify(reg)
        # 단일/복합 판정: 최상위 대비 35% 이상인 타입만 채택
        top = res[0][1] if res else 0
        chosen = [t for t, c in res if c >= max(MINPIX, 0.35*top)][:2]
        combo = "/".join(chosen) if chosen else "(못 읽음)"
        cand = candidates(dex, chosen) if chosen and dex else []
        print(f"[{s+1}번칸] 타입 {combo:<10} 후보: {', '.join(cand) if cand else '(없음/데이터부족)'}")
    print("\ntreg1~6.png 로 타입아이콘 영역 확인 가능.")

if __name__ == "__main__":
    main()
