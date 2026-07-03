"""실험: 타입으로 좁힌 후보 안에서, 게임 스프라이트를 오려내 색 히스토그램으로 순위.
   정답이 1위로 오는지 확인용. (레퍼런스=op.gg 일러스트라 절대매칭은 안 되지만,
   소수 후보 중 순위 매기기는 되는지 본다.)"""
import cv2, json, os, sys
import numpy as np
import type_icons as ti
import match as mt

HERE = os.path.dirname(os.path.abspath(__file__))
_data = json.load(open(os.path.join(HERE, "pokedex.json"), encoding="utf-8"))
_dex = _data.get("dex", _data)
IMG = {m["name"]: m.get("img") for m in _dex.values() if isinstance(m, dict)}
_refcache = {}
def refdesc(name):
    if name in _refcache: return _refcache[name]
    url = IMG.get(name); d = mt.prep_ref(url) if url else None
    _refcache[name] = d; return d

def run(shotfile):
    s = cv2.imread(os.path.join(HERE, shotfile))
    H, W = s.shape[:2]
    dex = ti.load_dex()
    panels = ti.find_panels(s)
    x0i, x1i = int(W*ti.TCOL_X0), int(W*ti.TCOL_X1)
    x0s, x1s = int(W*0.735), int(W*0.865)   # 스프라이트(패널 왼쪽) 영역
    print(f"\n===== {shotfile} ({len(panels)}칸) =====")
    for k, (py0, py1) in enumerate(panels):
        bh = py1 - py0
        reg = s[int(py0+bh*ti.ICON_Y0):int(py0+bh*ti.ICON_Y1), x0i:x1i]
        res = ti.classify(reg); top = res[0][1] if res else 0
        chosen = [t for t, c in res if c >= max(ti.MINPIX, 0.35*top)][:2]
        cands = ti.candidates(dex, chosen)
        combo = "/".join(chosen) if chosen else "?"
        if len(cands) <= 1:
            print(f"[{k+1}] {combo:<10} 확정: {cands[0] if cands else '(없음)'}")
            continue
        # 게임 스프라이트 추출 -> 색 히스토그램
        cd = mt.extract_from_crop(s[py0:py1, x0s:x1s])
        if cd is None:
            print(f"[{k+1}] {combo:<10} 후보(추출실패): {', '.join(cands)}"); continue
        scored = []
        for n in cands:
            rd = refdesc(n)
            if rd is not None:
                sim = float(cv2.compareHist(cd[1], rd[1], cv2.HISTCMP_CORREL))
                scored.append((sim, n))
        scored.sort(reverse=True)
        ranked = " > ".join(f"{n}({sim:.2f})" for sim, n in scored)
        print(f"[{k+1}] {combo:<10} 색순위: {ranked}")

if __name__ == "__main__":
    for f in (sys.argv[1:] or ["shot.png.jpg", "shot2.png.jpg", "shot3.png.jpg"]):
        run(f)
