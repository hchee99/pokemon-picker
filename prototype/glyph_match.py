# -*- coding: utf-8 -*-
"""타입 아이콘의 '흰 심볼 모양' 매칭 (색이 아닌 형태 기반).

색 분류는 경기장 조명/틴트에 따라 아이콘 색이 변해 계속 어긋났다.
반면 아이콘 안의 흰 심볼(불꽃/물방울/번개...) 모양은 어디서나 동일하다.

원리:
 1) 아이콘 네모의 위치는 화면 비율로 완전히 고정 (icon_geom 측정):
    왼쪽 박스 x/W 0.8385-0.8574, 오른쪽 박스 0.8603-0.8792,
    세로는 패널 상단 + 패널높이의 0.125~0.512. 단일 타입은 오른쪽만.
 2) 박스 안에서 흰 픽셀(max>=190, max-min<=45)만 뽑아 20x20 격자로 축소
    -> 심볼 모양 마스크
 3) 18타입 템플릿(정답 스샷들에서 평균)과 IoU 비교, 최고점이 타입
"""
import cv2, numpy as np, os, sys, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import type_icons as T

HERE = os.path.dirname(os.path.abspath(__file__))
TEMPLATE_PATH = os.path.join(HERE, "glyph_templates.json")

# 아이콘 박스 (3120x1440에서 측정, 비율이라 해상도 무관)
LX0, LX1 = 0.8385, 0.8574   # 왼쪽 아이콘 x/W
RX0, RX1 = 0.8603, 0.8792   # 오른쪽 아이콘 x/W
IY0, IY1 = 0.125, 0.512     # 패널높이 대비 세로
GRID = 20                    # 심볼 마스크 격자
MIN_GLYPH = 25               # 이보다 흰 픽셀이 적으면 '아이콘 없음' (격자 셀 기준)

# 검증된 정답 (색 인식 + 실전 확인). shot8은 프리뷰 화면이 아니라 제외.
GROUND_TRUTH = {
    "shot.png.jpg":  ["불꽃/비행", "악", "비행/강철", "물/페어리", "고스트/드래곤", "독/드래곤"],
    "shot2.png.jpg": ["악/물", "땅", "고스트/페어리", "불꽃/고스트", "에스퍼/강철", "독/바위"],
    "shot3.png.jpg": ["에스퍼/강철", "풀/악", "강철/드래곤", "악/드래곤", "물/페어리", "전기"],
    "shot4.png.jpg": ["전기", "강철/페어리", "악/드래곤", "땅/드래곤", "노말/비행", "비행/강철"],
    "shot5.png.jpg": ["불꽃/격투", "고스트/페어리", "고스트", "땅", "물/악", "물/고스트"],
    "shot6.png.jpg": ["불꽃/비행", "악", "비행/강철", "물/페어리", "고스트/드래곤", "독/드래곤"],
    "shot7.png.jpg": ["풀/고스트", "땅/물", "강철/드래곤", "물/비행", "강철/벌레", "고스트/드래곤"],
    "shot9.png.jpg": ["고스트/드래곤", "물/고스트", "페어리", "악/풀", "에스퍼/강철", "불꽃/비행"],
    "shot10.png.jpg": ["불꽃/격투", "얼음/페어리", "땅/드래곤", "비행/강철", "물/악", "독/바위"],
}

def boxes(shot, py0, py1):
    """슬롯 패널 -> (왼쪽박스, 오른쪽박스) BGR 크롭"""
    H, W = shot.shape[:2]
    bh = py1 - py0
    y0 = py0 + int(bh * IY0); y1 = py0 + int(bh * IY1)
    L = shot[y0:y1, int(W * LX0):int(W * LX1)]
    R = shot[y0:y1, int(W * RX0):int(W * RX1)]
    return L, R

def glyph_grid(box_bgr):
    """박스 -> 20x20 흰 심볼 마스크 (float 0..1). 흰 픽셀 거의 없으면 None."""
    rgb = cv2.cvtColor(box_bgr, cv2.COLOR_BGR2RGB).astype(np.int32)
    mx = rgb.max(2); mn = rgb.min(2)
    white = ((mx >= 190) & (mx - mn <= 45)).astype(np.float32)
    g = cv2.resize(white, (GRID, GRID), interpolation=cv2.INTER_AREA)
    if (g > 0.5).sum() < MIN_GLYPH: return None
    return g

def iou(a, b):
    ab = (a > 0.5); bb = (b > 0.5)
    u = (ab | bb).sum()
    return (ab & bb).sum() / u if u else 0.0

# ---------- 템플릿 생성 ----------
def build_templates():
    acc = {}   # 타입 -> [마스크들]
    for name, combos in GROUND_TRUTH.items():
        shot = cv2.imread(os.path.join(HERE, name))
        if shot is None: continue
        panels = T.find_panels(shot)
        if len(panels) != 6: continue
        for si, combo in enumerate(combos):
            types = combo.split("/")
            L, R = boxes(shot, *panels[si])
            slot_boxes = [R] if len(types) == 1 else [L, R]
            # 어느 박스가 어느 타입인지: 타입색과 가까운 픽셀 수로 결정
            for b in slot_boxes:
                rgb = cv2.cvtColor(b, cv2.COLOR_BGR2RGB).astype(np.float32)
                best_t, best_n = None, 0
                for t in types:
                    c = np.array(T.TYPE_RGB[t], np.float32)
                    n = (np.sqrt(((rgb - c) ** 2).sum(2)) < 50).sum()
                    if n > best_n: best_t, best_n = t, n
                if best_t is None: continue
                g = glyph_grid(b)
                if g is not None: acc.setdefault(best_t, []).append(g)
    tpl = {}
    for t, masks in acc.items():
        m = np.mean(masks, 0)
        tpl[t] = (m > 0.5).astype(np.uint8)
    with open(TEMPLATE_PATH, "w", encoding="utf-8") as f:
        json.dump({t: ["".join(map(str, row)) for row in m] for t, m in tpl.items()},
                  f, ensure_ascii=False, indent=0)
    print(f"템플릿 {len(tpl)}종 저장 ({sorted(tpl, key=T.NAMES.index)})  각 표본수:",
          {t: len(v) for t, v in acc.items()})
    return tpl

def load_templates():
    d = json.load(open(TEMPLATE_PATH, encoding="utf-8"))
    return {t: np.array([[int(c) for c in row] for row in rows], np.float32)
            for t, rows in d.items()}

# ---------- 인식 ----------
def match_box(box_bgr, tpl):
    g = glyph_grid(box_bgr)
    if g is None: return None, 0.0
    scores = {t: iou(g, m) for t, m in tpl.items()}
    t = max(scores, key=scores.get)
    return t, scores[t]

def recognize(shot, tpl, thr=0.45):
    """-> [(types, scores)] 슬롯별. 점수 낮으면 타입에 None."""
    out = []
    for py0, py1 in T.find_panels(shot):
        L, R = boxes(shot, py0, py1)
        lt, ls = match_box(L, tpl)
        rt, rs = match_box(R, tpl)
        types = []
        if lt and ls >= thr: types.append(lt)
        if rt and rs >= thr: types.append(rt)
        out.append((types, (lt, round(ls, 2), rt, round(rs, 2))))
    return out

if __name__ == "__main__":
    tpl = build_templates()
    tpl = load_templates()
    total = ok = 0
    for name, combos in GROUND_TRUTH.items():
        shot = cv2.imread(os.path.join(HERE, name))
        if shot is None: continue
        res = recognize(shot, tpl)
        if len(res) != len(combos): print(f"{name}: 패널 수 불일치"); continue
        line = []
        for (types, dbg), gt in zip(res, combos):
            gset = frozenset(gt.split("/"))
            hit = frozenset(types) == gset
            total += 1; ok += hit
            line.append(("O " if hit else "X ") + "/".join(types) + f" {dbg}")
        print(f"{name}:\n  " + "\n  ".join(line))
    print(f"\n정확도: {ok}/{total}")
