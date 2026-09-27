#!/usr/bin/env python3
import re, subprocess, os
ADB = os.path.expanduser("~/android-platform-tools/platform-tools/adb")
S = os.environ.get("SERIAL", "adb-e69a99d8-yzT17Y (2)._adb-tls-connect._tcp")
env = dict(os.environ, ANDROID_ADB_SERVER_PORT="5038")
def run(*a): return subprocess.run([ADB, "-s", S, *a], capture_output=True, text=True, env=env)
run("shell", "uiautomator", "dump", "/sdcard/wd.xml")
xml = run("shell", "cat", "/sdcard/wd.xml").stdout
print("== text ==")
for m in re.finditer(r'text="([^"]{1,80})"[^>]*bounds="(\[[0-9,\[\]]+\])"', xml):
    t = m.group(1).strip()
    if t: print(t[:70], " ", m.group(2))
print("== desc ==")
for m in re.finditer(r'content-desc="([^"]{1,60})"[^>]*bounds="(\[[0-9,\[\]]+\])"', xml):
    d = m.group(1).strip()
    if d: print(d[:60], " ", m.group(2))