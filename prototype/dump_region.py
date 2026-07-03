"""상대 패널 오른쪽을 넓게 크롭해서 타입아이콘 위치 파악용."""
import cv2, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
DBG = os.path.join(HERE, "debug_crops"); os.makedirs(DBG, exist_ok=True)
shot = cv2.imread(os.path.join(HERE, sys.argv[1] if len(sys.argv) > 1 else "shot.png.jpg"))
H, W = shot.shape[:2]; print(f"{W}x{H}")
ROW_TOP, ROW_BOT = 0.14, 0.92; N = 6; band = (ROW_BOT-ROW_TOP)/N
X0, X1 = 0.80, 0.97   # 스프라이트 오른쪽 ~ 패널 오른쪽 끝
for s in range(N):
    cy0 = int(H*(ROW_TOP+band*s)); cy1 = int(H*(ROW_TOP+band*(s+1)))
    reg = shot[cy0:cy1, int(W*X0):int(W*X1)]
    cv2.imwrite(os.path.join(DBG, f"rfull{s+1}.png"), reg)
print("saved rfull1~6.png")
