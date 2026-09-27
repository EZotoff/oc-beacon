#!/usr/bin/env python3
"""mp4-frame-jump.py — cv2 直读 mp4 帧序列 + 中列带 1D 互相关突跳检测
（替代 ffmpeg 抽帧 + scripts/frame-jump-analyze.py 两步；判定阈值同源：|shift|>480px）
用法: venv/bin/python mp4-frame-jump.py <rec.mp4>
"""
import cv2, sys, numpy as np
path = sys.argv[1]
cap = cv2.VideoCapture(path)
fps = cap.get(cv2.CAP_PROP_FPS) or 24
prev = None
idx = 0
jumps = 0
print("帧 | 时间(s) | 平移(px) | 判定")
while True:
    ok, frame = cap.read()
    if not ok: break
    idx += 1
    if idx % 2:  # 隔帧采样（~12fps 等效）
        continue
    g = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    h, w = g.shape
    x0 = w // 2 - 20
    band = g[:, x0:x0+40].astype(np.int32)
    cur = band[::2, ::4].sum(axis=1).astype(np.int64)
    if prev is not None:
        n = len(cur)
        best_shift, best_score = 0, -1e18
        for sh in range(-600, 600, 2):
            i = np.arange(200, n - 200, 4)
            j = i + sh // 2
            m = (j >= 0) & (j < n)
            if m.sum() < 50: continue
            score = -np.abs(cur[i[m]] - prev[j[m]]).mean()
            if score > best_score: best_score, best_shift = score, sh
        jump = abs(best_shift) > 480 or best_score < -28
        if jump: jumps += 1
        if jump or idx % 24 == 0:
            print("%5d | %6.2f | %5d | %s" % (idx, idx / fps / 2, best_shift, "<<< 突跳" if jump else ""))
    prev = cur
print("总帧 %d（采样 %d）| 突跳帧: %d" % (idx, idx // 2, jumps))