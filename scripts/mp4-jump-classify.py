import cv2, sys, numpy as np
cap = cv2.VideoCapture(sys.argv[1])
prev = None; idx = 0; shift_jumps = []; score_jumps = 0
while True:
    ok, frame = cap.read()
    if not ok: break
    idx += 1
    if idx % 2: continue
    g = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    h, w = g.shape
    band = g[:, w//2-20:w//2+20].astype(np.int32)
    cur = band[::2, ::4].sum(axis=1).astype(np.int64)
    if prev is not None:
        n = len(cur); best_shift, best_score = 0, -1e18
        for sh in range(-600, 600, 2):
            i = np.arange(200, n-200, 4); j = i + sh//2
            m = (j >= 0) & (j < n)
            if m.sum() < 50: continue
            score = -np.abs(cur[i[m]] - prev[j[m]]).mean()
            if score > best_score: best_score, best_shift = score, sh
        if abs(best_shift) > 480: shift_jumps.append((idx//2, best_shift))
        elif best_score < -28: score_jumps += 1
    prev = cur
print("%s: 位移型突跳 %d %s | score型 %d" % (sys.argv[1].split("/")[-1], len(shift_jumps), shift_jumps[:4], score_jumps))