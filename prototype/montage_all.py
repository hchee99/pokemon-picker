"""여러 스샷의 타입아이콘을 한 장(그리드)으로 모으고 각 슬롯 대표색 출력 -> 색 보정용."""
import cv2, numpy as np, os, sys
import type_icons as t
HERE = os.path.dirname(os.path.abspath(__file__))
shots = sys.argv[1:] or ["shot.png.jpg", "shot2.png.jpg", "shot3.png.jpg", "shot4.png.jpg", "shot5.png.jpg"]
CW, CH = 120, 60
grid = np.full((CH*len(shots), CW*6, 3), 255, np.uint8)
for r, f in enumerate(shots):
    s = cv2.imread(os.path.join(HERE, f))
    if s is None:
        print("못읽음", f); continue
    W = s.shape[1]; x0, x1 = int(W*t.TCOL_X0), int(W*t.TCOL_X1)
    panels = t.find_panels(s)
    print(f"\n{f}: 패널 {len(panels)}")
    for c, (py0, py1) in enumerate(panels[:6]):
        bh = py1-py0
        reg = s[int(py0+bh*t.ICON_Y0):int(py0+bh*t.ICON_Y1), x0:x1]
        grid[r*CH:(r+1)*CH, c*CW:(c+1)*CW] = cv2.resize(reg, (CW, CH))
        # 대표 유채색(패널/성별 제외)
        rgb = cv2.cvtColor(reg, cv2.COLOR_BGR2RGB).reshape(-1, 3).astype(np.float32)
        hsv = cv2.cvtColor(reg, cv2.COLOR_BGR2HSV).reshape(-1, 3)
        keep = (hsv[:, 1] > 80) & (hsv[:, 2] > 90)
        px = rgb[keep]
        cols = []
        if len(px) > 30:
            crit = (cv2.TERM_CRITERIA_EPS+cv2.TERM_CRITERIA_MAX_ITER, 20, 1.0)
            _, lab, cen = cv2.kmeans(px, 3, None, crit, 4, cv2.KMEANS_PP_CENTERS)
            cnt = np.bincount(lab.flatten(), minlength=3)
            for i in cnt.argsort()[::-1]:
                cc = cen[i].astype(int)
                # 크림슨 패널/파랑성별 대충 제외
                if not (cc[0] > cc[1] and cc[0] > cc[2] and cc[1] < 60):  # 크림슨 아님
                    cols.append(f"({cc[0]},{cc[1]},{cc[2]})x{int(cnt[i])}")
        print(f"  {r+1}행{c+1}열: {'  '.join(cols[:2])}")
cv2.imwrite(os.path.join(HERE, "montage.png"), grid)
print("\nmontage.png 저장 (행=스샷, 열=상대1~6칸)")
