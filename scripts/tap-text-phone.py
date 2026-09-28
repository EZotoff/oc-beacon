#!/usr/bin/env python3
"""tap-text-phone.py — dump→正则匹配节点→100ms 按压注入（0ms input tap 会被列表 scrollable 丢弃）"""
import re, subprocess, sys, os
ADB = os.path.expanduser("~/android-platform-tools/platform-tools/adb")
SERIAL = os.environ.get("SERIAL", "adb-e69a99d8-yzT17Y (2)._adb-tls-connect._tcp")
ENV = dict(os.environ, ANDROID_ADB_SERVER_PORT="5038")
def sh(*args, timeout=40):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout, env=ENV)
def dump():
    sh("shell", "rm", "-f", "/sdcard/window_dump.xml")
    r = sh("shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
    if "dumped" not in (r.stdout or ""): return None
    c = sh("shell", "cat", "/sdcard/window_dump.xml")
    return c.stdout if c.returncode == 0 else None
def find_bounds(xml, pattern, use_desc=False, last=False):
    attr = "content-desc" if use_desc else "text"
    hits = []
    for m in re.finditer(r"<node[^>]*/?>", xml):
        node = m.group(0)
        am = re.search(attr + r'="([^"]*)"', node)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if am and bm and re.search(pattern, am.group(1)):
            x1, y1, x2, y2 = map(int, bm.groups())
            if x2 > x1 and y2 > y1:
                hits.append(((x1+x2)//2, (y1+y2)//2))
                if not last: return hits[0]
    return hits[-1] if hits else None
def main():
    pattern = sys.argv[1]
    use_desc = "--desc" in sys.argv
    last = "--last" in sys.argv
    idx_from_last = 1
    if "--index" in sys.argv: idx_from_last = int(sys.argv[sys.argv.index("--index") + 1])
    xml = dump()
    if not xml: print("DUMP_FAIL"); return 2
    if idx_from_last > 1:
        hits = []
        attr = "content-desc" if use_desc else "text"
        for m in re.finditer(r"<node[^>]*/?>", xml):
            node = m.group(0)
            am = re.search(attr + r'="([^"]*)"', node)
            bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if am and bm and re.search(pattern, am.group(1)):
                x1, y1, x2, y2 = map(int, bm.groups())
                if x2 > x1 and y2 > y1: hits.append(((x1+x2)//2, (y1+y2)//2))
        if len(hits) < idx_from_last: print("NOT_FOUND"); return 1
        pos = hits[-idx_from_last]
    else:
        pos = find_bounds(xml, pattern, use_desc, last)
    if not pos: print("NOT_FOUND"); return 1
    x, y = pos
    sh("shell", "input", "swipe", str(x), str(y), str(x), str(y), "100")
    print(f"tapped {x} {y}")
    return 0
if __name__ == "__main__": sys.exit(main())