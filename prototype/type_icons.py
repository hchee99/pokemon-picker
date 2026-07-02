"""
타입 아이콘 색 인식 프로토타입.
상대 패널 오른쪽의 타입 아이콘 색을 읽어 타입 조합을 판별한다.
색 기준은 우리가 가진 18타입 RGB.
"""
import cv2, numpy as np, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
DBG = os.path.join(HERE, "debug_crops"); os.makedirs(DBG, exist_ok=True)

# 타입 -> RGB (op.gg/게임 타입색)
TYPE_RGB = {
    "노말": (153,153,153), "불꽃": (255,97,44), "물": (41,146,255), "전기": (255,219,0),
    "풀": (66,191,36), "얼음": (66,216,255), "격투": (255,162,2), "독": (153,77,207),
    "땅": (171,121,57), "비행": (149,201,255), "에스퍼": (255,99,127), "벌레": (159,164,36),
    "바위": (188,184,137), "고스트": (110,69,112), "드래곤": (84,98,214), "악": (79,71,71),
    "강철": (106,174,211), "페어리": (255,177,255),
}
NAMES = list(TYPE_RGB.keys())
COLS = np.array([TYPE_RGB[t] for t in NAMES], np.int16)  # RGB

# 타입 아이콘 영역(화면 비율): 상대 열의 오른쪽, 각 칸 위쪽
TCOL_X0, TCOL_X1 = 0.862, 0.980
ROW_TOP, ROW_BOT = 0.14, 0.92
BAND_Y0, BAND_Y1 = 0.05, 0.55   # 각 칸에서 타입 아이콘이 있는 세로 구간(성별 아이콘 제외)
N = 6
DIST = 42        # 이 거리 안이면 그 타입색으로 인정
MINPIX = 25      # 최소 픽셀수

def classify(region_bgr):
    rgb = cv2.cvtColor(region_bgr, cv2.COLOR_BGR2RGB).reshape(-1, 3).astype(np.int16)
    # 각 픽셀 -> 가장 가까운 타입색 거리
    d = np.sqrt(((rgb[:, None, :] - COLS[None, :, :]) ** 2).sum(2))  # (px,18)
    nearest = d.argmin(1); mind = d.min(1)
    counts = np.zeros(len(NAMES), int)
    for i in range(len(NAMES)):
        counts[i] = int(((nearest == i) & (mind < DIST)).sum())
    order = counts.argsort()[::-1]
    return [(NAMES[i], int(counts[i])) for i in order if counts[i] >= MINPIX][:3]

def main():
    shot = cv2.imread(sys.argv[1] if len(sys.argv) > 1 else "shot.png.jpg")
    if shot is None: print("스크린샷 못 읽음"); return
    H, W = shot.shape[:2]; print(f"스크린샷 {W}x{H}\n")
    x0, x1 = int(W*TCOL_X0), int(W*TCOL_X1); band = (ROW_BOT-ROW_TOP)/N
    for s in range(N):
        ry0 = ROW_TOP + band*s
        cy0 = int(H*(ry0 + band*BAND_Y0)); cy1 = int(H*(ry0 + band*BAND_Y1))
        reg = shot[cy0:cy1, x0:x1]
        cv2.imwrite(os.path.join(DBG, f"treg{s+1}.png"), reg)
        res = classify(reg)
        combo = " / ".join(f"{t}({c})" for t, c in res[:2]) if res else "(못 읽음)"
        print(f"[{s+1}번칸] 타입추정: {combo}")
        if len(res) > 2:
            print(f"        (그다음: {res[2][0]}({res[2][1]}))")
    print("\ntreg1~6.png 로 타입아이콘 영역이 제대로 잡혔는지 확인하세요.")

if __name__ == "__main__":
    main()
