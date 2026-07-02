"""
포켓몬 팀 프리뷰 인식 프로토타입 (1단계 검증용, PC에서 실행)

목적: 팀 프리뷰 스크린샷에서 상대 6마리 스프라이트를 잘라내
      우리가 가진 레퍼런스 스프라이트(317장)와 매칭해 정확도를 확인한다.
      => APK 만들기 전에 "인식이 되는가?"만 값싸게 검증.

준비물:
  1) 파이썬 + 라이브러리:  pip install opencv-python numpy
  2) 사이트에서 "JSON 파일로 저장"한 pokedex.json 을 이 폴더에 둔다
  3) 팀 프리뷰 스크린샷을 이 폴더에 shot.png 로 저장

실행:
  python match.py shot.png
결과:
  - refs/ 폴더에 레퍼런스 스프라이트 자동 다운로드(최초 1회)
  - debug_crops/ 에 잘라낸 상대 6칸 이미지 저장 (크롭 좌표 확인용)
  - 콘솔에 각 칸별 top-3 후보 + 점수 출력

크롭이 엉뚱하면 아래 CONFIG 의 좌표(화면 비율)를 조정하면 된다.
"""
import cv2, numpy as np, json, os, sys, urllib.request, hashlib

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "refs")
DBG = os.path.join(HERE, "debug_crops")
os.makedirs(CACHE, exist_ok=True)
os.makedirs(DBG, exist_ok=True)

# ---- CONFIG: 상대 6칸 크롭 위치 (스크린샷 가로/세로 대비 비율) ----
# 오른쪽 세로 열에 상대 6마리. 스프라이트는 각 빨간 패널의 왼쪽에 위치.
# 값은 추정치이므로 debug_crops 결과 보고 조정하세요.
COL_X0, COL_X1 = 0.735, 0.865      # 스프라이트가 들어있는 가로 구간
ROW_TOP, ROW_BOT = 0.14, 0.92      # 6칸이 차지하는 세로 구간(첫칸 위 ~ 마지막칸 아래)
N = 6

def load_dex(path):
    data = json.load(open(path, encoding="utf-8"))
    dex = data.get("dex", data)
    out = []
    for m in dex.values():
        if isinstance(m, dict) and m.get("img") and m.get("types"):
            out.append({"name": m["name"], "img": m["img"], "types": m["types"]})
    return out

def fetch_bytes(url):
    fn = os.path.join(CACHE, hashlib.md5(url.encode()).hexdigest() + ".img")
    if not os.path.exists(fn):
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        open(fn, "wb").write(urllib.request.urlopen(req, timeout=20).read())
    return np.frombuffer(open(fn, "rb").read(), np.uint8)

def prep_ref(url):
    """레퍼런스: 투명 배경 제거 -> 스프라이트 bbox 크롭 -> 128 그레이"""
    try:
        raw = fetch_bytes(url)
        img = cv2.imdecode(raw, cv2.IMREAD_UNCHANGED)
        if img is None: return None
    except Exception:
        return None
    if img.ndim == 3 and img.shape[2] == 4:
        alpha = img[:, :, 3]; bgr = img[:, :, :3]; mask = alpha > 16
    else:
        bgr = img if img.ndim == 3 else cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)
        g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY); mask = g < 248
    ys, xs = np.where(mask)
    if len(xs) < 10:
        g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    else:
        bgr = bgr[ys.min():ys.max()+1, xs.min():xs.max()+1]
        g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    return cv2.resize(g, (128, 128))

def prep_crop(bgr):
    g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    return cv2.resize(g, (128, 128))

orb = cv2.ORB_create(500)
def orb_des(gray):
    _, des = orb.detectAndCompute(gray, None); return des
bf = cv2.BFMatcher(cv2.NORM_HAMMING)
def orb_score(d1, d2):
    if d1 is None or d2 is None: return 0
    try:
        m = bf.knnMatch(d1, d2, k=2)
    except cv2.error:
        return 0
    good = 0
    for pair in m:
        if len(pair) == 2 and pair[0].distance < 0.75 * pair[1].distance: good += 1
    return good
def tmpl_score(a, b):
    return float(cv2.matchTemplate(a, b, cv2.TM_CCOEFF_NORMED).max())

def main():
    if len(sys.argv) < 2:
        print("사용법: python match.py <스크린샷파일>  (예: python match.py shot.png)"); return
    shot = cv2.imread(sys.argv[1])
    if shot is None:
        print("스크린샷을 못 읽었어요:", sys.argv[1]); return
    H, W = shot.shape[:2]
    dexp = os.path.join(HERE, "pokedex.json")
    if not os.path.exists(dexp):
        print("pokedex.json 이 이 폴더에 없어요. 사이트에서 'JSON 파일로 저장' 후 여기로 옮기세요."); return
    mons = load_dex(dexp)
    print(f"레퍼런스 {len(mons)}마리 준비 중(최초 1회 다운로드)...")
    refs = []
    for i, m in enumerate(mons):
        g = prep_ref(m["img"])
        if g is not None:
            refs.append({**m, "g": g, "d": orb_des(g)})
        if (i+1) % 50 == 0: print(f"  ...{i+1}/{len(mons)}")
    print(f"레퍼런스 로드 완료: {len(refs)}\n")

    x0, x1 = int(W*COL_X0), int(W*COL_X1)
    band = (ROW_BOT - ROW_TOP) / N
    for s in range(N):
        cy0 = int(H*(ROW_TOP + band*s)); cy1 = int(H*(ROW_TOP + band*(s+1)))
        crop = shot[cy0:cy1, x0:x1]
        cv2.imwrite(os.path.join(DBG, f"slot{s+1}.png"), crop)
        cg = prep_crop(crop); cd = orb_des(cg)
        scored = []
        for r in refs:
            scored.append((orb_score(cd, r["d"]), tmpl_score(cg, r["g"]), r["name"], "/".join(r["types"])))
        scored.sort(key=lambda t: (t[0], t[1]), reverse=True)
        print(f"[{s+1}번칸] 추정:")
        for orbn, tm, name, ty in scored[:3]:
            print(f"    {name:<16} ({ty})   ORB매칭 {orbn:>3}  |  템플릿 {tm:.2f}")
        print()
    print("crop 이 엉뚱하면 match.py 상단 CONFIG 좌표를 조정하고 다시 실행하세요.")
    print("debug_crops/ 안의 slot1~6.png 로 크롭 위치를 확인할 수 있어요.")

if __name__ == "__main__":
    main()
