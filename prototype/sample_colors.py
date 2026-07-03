"""각 칸 타입아이콘 영역의 대표 색(k-means)을 뽑아 실제 게임 아이콘색 확인용."""
import cv2, numpy as np, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
shot = cv2.imread(os.path.join(HERE, sys.argv[1] if len(sys.argv) > 1 else "shot.png.jpg"))
H, W = shot.shape[:2]
TCOL_X0, TCOL_X1 = 0.833, 0.890
ROW_TOP, ROW_BOT = 0.14, 0.92; N = 6; band = (ROW_BOT-ROW_TOP)/N
BAND_Y0, BAND_Y1 = 0.06, 0.32
ANSWER = ["불꽃/비행", "악", "비행/강철", "물/페어리", "물/고스트", "불꽃/고스트?"]
x0, x1 = int(W*TCOL_X0), int(W*TCOL_X1)
for s in range(N):
    ry0 = ROW_TOP + band*s
    cy0 = int(H*(ry0+band*BAND_Y0)); cy1 = int(H*(ry0+band*BAND_Y1))
    reg = cv2.cvtColor(shot[cy0:cy1, x0:x1], cv2.COLOR_BGR2RGB).reshape(-1, 3).astype(np.float32)
    # 흰 심볼/크림슨 패널 제외: 채도 있는 유채색만
    hsv = cv2.cvtColor(reg.reshape(-1, 1, 3).astype(np.uint8), cv2.COLOR_RGB2HSV).reshape(-1, 3)
    keep = (hsv[:, 1] > 70) & (hsv[:, 2] > 80)
    px = reg[keep]
    print(f"[{s+1}번칸] 정답={ANSWER[s]}  유효픽셀 {len(px)}")
    if len(px) < 20:
        print("   (샘플 부족)\n"); continue
    K = 3
    crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 1.0)
    _, lab, cen = cv2.kmeans(px, K, None, crit, 5, cv2.KMEANS_PP_CENTERS)
    order = np.argsort(-np.bincount(lab.flatten(), minlength=K))
    for i in order:
        c = cen[i].astype(int); cnt = int((lab.flatten() == i).sum())
        print(f"   RGB({c[0]:3d},{c[1]:3d},{c[2]:3d})  x{cnt}")
    print()
