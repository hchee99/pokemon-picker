"""
포켓몬 팀 프리뷰 인식 프로토타입 v3 (배경분리 + 색/모양 매칭)

핵심: 크롭에서 마젠타 패널을 지우고, 중앙의 가장 큰 덩어리(=스프라이트)만 오려낸 뒤,
      레퍼런스 스프라이트와 (1) 색 히스토그램 (2) 모양 상관도 로 비교.
배경 슬라이딩 매칭의 오탐 문제를 없앰.

  pip install opencv-python numpy
  python match.py shot.png
"""
import cv2, numpy as np, json, os, sys, urllib.request, hashlib

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "refs"); DBG = os.path.join(HERE, "debug_crops")
os.makedirs(CACHE, exist_ok=True); os.makedirs(DBG, exist_ok=True)

COL_X0, COL_X1 = 0.735, 0.865
ROW_TOP, ROW_BOT = 0.14, 0.92
N = 6
# 크롭 내부에서 스프라이트가 있는 '패널 안쪽 중앙' 영역(바깥 모서리/아이콘 제외)
SP_X0, SP_X1 = 0.11, 0.66
SP_Y0, SP_Y1 = 0.13, 0.87

def load_dex(path):
    data = json.load(open(path, encoding="utf-8")); dex = data.get("dex", data)
    return [{"name": m["name"], "img": m["img"], "types": m["types"]}
            for m in dex.values() if isinstance(m, dict) and m.get("img") and m.get("types")]

def fetch_bytes(url):
    fn = os.path.join(CACHE, hashlib.md5(url.encode()).hexdigest() + ".img")
    if not os.path.exists(fn):
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        open(fn, "wb").write(urllib.request.urlopen(req, timeout=20).read())
    return np.frombuffer(open(fn, "rb").read(), np.uint8)

def descriptor(bgr, mask):
    """스프라이트 bbox -> (흰배경 96x96 그레이, HS 히스토그램)"""
    ys, xs = np.where(mask > 0)
    if len(xs) < 30: return None
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    b = bgr[y0:y1+1, x0:x1+1].copy(); m = mask[y0:y1+1, x0:x1+1]
    b[m == 0] = (255, 255, 255)
    gray = cv2.resize(cv2.cvtColor(b, cv2.COLOR_BGR2GRAY), (96, 96))
    hsv = cv2.cvtColor(b, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1], m, [30, 32], [0, 180, 0, 256])
    cv2.normalize(hist, hist, 0, 1, cv2.NORM_MINMAX)
    return gray, hist

def prep_ref(url):
    try:
        img = cv2.imdecode(fetch_bytes(url), cv2.IMREAD_UNCHANGED)
        if img is None: return None
    except Exception:
        return None
    if img.ndim == 3 and img.shape[2] == 4:
        bgr = img[:, :, :3]; mask = (img[:, :, 3] > 16).astype(np.uint8)*255
    else:
        bgr = img if img.ndim == 3 else cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)
        mask = (cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY) < 248).astype(np.uint8)*255
    return descriptor(bgr, mask)

def extract_from_crop(crop, dbg=None):
    """크롭 -> 마젠타 제거 + 패널 안쪽 중앙 최대 덩어리 = 스프라이트 마스크"""
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    Hh, S, V = cv2.split(hsv)
    magenta = (((Hh >= 158) | (Hh <= 6)) & (S > 55) & (V > 35))
    ch, cw = crop.shape[:2]
    region = np.zeros((ch, cw), bool)
    region[int(ch*SP_Y0):int(ch*SP_Y1), int(cw*SP_X0):int(cw*SP_X1)] = True
    fg = (~magenta) & region & (V > 25)
    fg = fg.astype(np.uint8)*255
    fg = cv2.morphologyEx(fg, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8))
    n, lab, stats, cent = cv2.connectedComponentsWithStats(fg, 8)
    if n <= 1: return None
    best = max(range(1, n), key=lambda i: stats[i, cv2.CC_STAT_AREA])
    mask = (lab == best).astype(np.uint8)*255
    mask = cv2.dilate(mask, np.ones((5, 5), np.uint8))
    if dbg:
        vis = crop.copy(); vis[mask == 0] = (255, 255, 255)
        cv2.imwrite(dbg, vis)
    return descriptor(crop, mask)

def score(cd, rd):
    shape = float(cv2.matchTemplate(cd[0], rd[0], cv2.TM_CCOEFF_NORMED)[0, 0])
    color = float(cv2.compareHist(cd[1], rd[1], cv2.HISTCMP_CORREL))
    return 0.6*color + 0.4*shape, color, shape

def main():
    shot = cv2.imread(sys.argv[1] if len(sys.argv) > 1 else "shot.png.jpg")
    if shot is None: print("스크린샷 못 읽음"); return
    H, W = shot.shape[:2]; print(f"스크린샷 {W}x{H}")
    mons = load_dex(os.path.join(HERE, "pokedex.json"))
    print(f"레퍼런스 {len(mons)}마리 준비...")
    refs = []
    for i, m in enumerate(mons):
        d = prep_ref(m["img"])
        if d: refs.append({**m, "d": d})
        if (i+1) % 80 == 0: print(f"  ...{i+1}/{len(mons)}")
    print(f"완료: {len(refs)}\n")

    x0, x1 = int(W*COL_X0), int(W*COL_X1); band = (ROW_BOT-ROW_TOP)/N
    for s in range(N):
        cy0 = int(H*(ROW_TOP+band*s)); cy1 = int(H*(ROW_TOP+band*(s+1)))
        crop = shot[cy0:cy1, x0:x1]
        cd = extract_from_crop(crop, os.path.join(DBG, f"ex_full{s+1}.png"))
        if cd is None:
            print(f"[{s+1}번칸] 스프라이트 추출 실패\n"); continue
        scored = sorted(([*score(cd, r["d"]), r["name"], "/".join(r["types"])] for r in refs),
                        key=lambda t: t[0], reverse=True)
        print(f"[{s+1}번칸]")
        for tot, col, sh, name, ty in scored[:3]:
            print(f"    {name:<16} ({ty})   종합 {tot:.3f} (색 {col:.2f} 모양 {sh:.2f})")
        print()
    print("추출 결과: debug_crops/extract1~6.png (흰배경에 스프라이트만 남아야 정상)")

if __name__ == "__main__":
    main()
