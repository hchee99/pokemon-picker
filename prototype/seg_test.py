"""배경/스프라이트 분리 진단: 색값 출력 + 마스크/오려낸 결과 이미지 저장"""
import cv2, numpy as np, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
shot = cv2.imread(os.path.join(HERE, sys.argv[1] if len(sys.argv) > 1 else "shot.png.jpg"))
H, W = shot.shape[:2]
COL_X0, COL_X1 = 0.735, 0.865
ROW_TOP, ROW_BOT = 0.14, 0.92
N = 6
x0, x1 = int(W*COL_X0), int(W*COL_X1); band = (ROW_BOT-ROW_TOP)/N

for s in [0, 1, 3]:  # 리자몽(밝음), 블래키(어두움), 누리레느(흰/파랑) 로 추정되는 칸
    cy0 = int(H*(ROW_TOP+band*s)); cy1 = int(H*(ROW_TOP+band*(s+1)))
    crop = shot[cy0:cy1, x0:x1]
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    ch, cw = crop.shape[:2]
    pts = {"TL": (6, 6), "TR": (6, cw-7), "BL": (ch-7, 6), "BR": (ch-7, cw-7),
           "center": (ch//2, cw//2), "midleft": (ch//2, cw//6), "midright": (ch//2, cw-cw//6)}
    print(f"[slot{s+1}] {cw}x{ch}")
    for k, (yy, xx) in pts.items():
        print(f"   {k:8} HSV={hsv[yy,xx].tolist()}  BGR={crop[yy,xx].tolist()}")
    Hh, S, V = cv2.split(hsv)
    mag = (((Hh >= 140) | (Hh <= 8)) & (S > 60) & (V > 40)).astype(np.uint8)*255
    cv2.imwrite(os.path.join(HERE, f"seg_crop{s+1}.png"), crop)
    cv2.imwrite(os.path.join(HERE, f"seg_mag{s+1}.png"), mag)
    sprite = crop.copy(); sprite[mag > 0] = (255, 255, 255)
    cv2.imwrite(os.path.join(HERE, f"seg_sprite{s+1}.png"), sprite)
    print()
print("저장: seg_crop*, seg_mag*(배경으로 판정된 부분=흰색), seg_sprite*(배경 제거)")
