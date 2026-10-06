# #515 E2E 矩阵结果 2026-10-06 20:56:38

### A short-reply × normal
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=1
- fires=[] (期望 0) cached hit/miss=0/0
- neg_d=[]
- **PASS**
### A mixed-stress × normal
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=1
- fires=[] (期望 0) cached hit/miss=0/0
- neg_d=[]
- **PASS**
### A headings-only × normal
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=1
- fires=[] (期望 0) cached hit/miss=0/0
- neg_d=[-3262]
- **PASS**
### A progressive-table × normal
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=1
- fires=[] (期望 0) cached hit/miss=0/0
- neg_d=[-2804]
- **PASS**
### A checklist-quote × normal
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=1
- fires=[] (期望 0) cached hit/miss=0/0
- neg_d=[-3730]
- **PASS**
### A nested-lists × normal
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=1
- fires=[] (期望 0) cached hit/miss=0/0
- neg_d=[-3219]
- **PASS**
### A cjk-table × normal
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=1
- fires=[] (期望 0) cached hit/miss=0/0
- neg_d=[]
- **PASS**
### A wide-table × normal
- crash=False anr=False RESETKEY=0 arm=1 TurnFin=1
- fires=[4191] (期望 0) cached hit/miss=2/0
- neg_d=[-7481, -128, -128, -128]
- **FAIL**
### A eof-fire × normal
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=1
- fires=[] (期望 0) cached hit/miss=0/0
- neg_d=[-4024, -437]
- **PASS**
### A giant-single × normal
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=1
- fires=[] (期望 0) cached hit/miss=0/0
- neg_d=[-5493, -345]
- **PASS**
### A long-paragraphs × normal
- crash=False anr=False RESETKEY=0 arm=1 TurnFin=1
- fires=[4294] (期望 1) cached hit/miss=5/0
- neg_d=[-6314, -2116, -1654, -2116]
- **PASS**
### A code-long × normal
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=0
- fires=[] (期望 None) cached hit/miss=0/0
- neg_d=[]
- **FAIL**
### A multi-section × normal
- crash=False anr=False RESETKEY=0 arm=3 TurnFin=0
- fires=[4062, 8423, 12741] (期望 2) cached hit/miss=6/0
- neg_d=[-6194, -7534, -7446]
- **FAIL**
### B multi-section × fast
- crash=False anr=False RESETKEY=0 arm=3 TurnFin=1
- fires=[4062, 8423, 12741] (期望 2) cached hit/miss=12/0
- neg_d=[-6132, -7534, -7446, -3392, -3700]
- **FAIL**
### B multi-section × bursty
- crash=False anr=False RESETKEY=0 arm=2 TurnFin=0
- fires=[4062, 8423] (期望 2) cached hit/miss=4/0
- neg_d=[-6260, -7534]
- **PASS**
### B multi-section × stall-mid
- crash=False anr=False RESETKEY=0 arm=2 TurnFin=0
- fires=[4062, 8423] (期望 2) cached hit/miss=4/0
- neg_d=[-6292, -7534]
- **PASS**
### B multi-section × megachunk
- crash=False anr=False RESETKEY=0 arm=3 TurnFin=1
- fires=[4062, 8423, 12741] (期望 2) cached hit/miss=12/0
- neg_d=[-6194, -7732, -7512, -3392, -3700]
- **FAIL**
### B multi-section × line
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=1
- fires=[] (期望 2) cached hit/miss=0/0
- neg_d=[-20646, -92, -48]
- **FAIL**
### B eof-fire × abort-mid
- crash=False anr=False RESETKEY=0 arm=0 TurnFin=1
- fires=[] (期望 0) cached hit/miss=0/0
- neg_d=[-2435, -437]
- **PASS**
### C 乱操作风暴 #1
- crash=False anr=False RESETKEY=0 arm=3 TurnFin=1
- fires=[4062, 8423, 12741] (期望 2) cached hit/miss=10/0
- neg_d=[-6194, -7534, -7384, -3634]
- **FAIL**
### C 乱操作风暴 #2
- crash=False anr=False RESETKEY=0 arm=1 TurnFin=0
- fires=[4062] (期望 2) cached hit/miss=3/0
- neg_d=[-6194]
- **FAIL**
