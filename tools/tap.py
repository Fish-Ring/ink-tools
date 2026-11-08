#!/usr/bin/env python
"""截图定位助手：从 PNG 找文字带，输出物理坐标，点击用。

用法：
  python tools/tap.py shot            # 抓图 + 列出所有文字带
  python tools/tap.py find <关键字>   # 找出关键字所在文字带（需要 PIL，缺省则退化）
"""
import struct, zlib, subprocess, sys, os

ADB = "D:/Code/Environment/Android/Sdk/platform-tools/adb.exe"
RAW = "C:/tmp/_tap.raw"
PNG = "C:/tmp/_tap.png"


def shell(*args):
    env = dict(os.environ)
    env["MSYS_NO_PATHCONV"] = "1"
    return subprocess.run([ADB] + list(args), capture_output=True, text=True, env=env)


def grab_raw():
    shell("shell", "screencap", "/sdcard/_tap.raw")
    shell("pull", "/sdcard/_tap.raw", RAW)
    with open(RAW, "rb") as f:
        d = f.read()
    w, h, _ = struct.unpack("<III", d[:12])
    hdr = 16 if len(d) == w * h * 4 + 16 else 12
    return d[hdr:], w, h


def grab_png():
    shell("shell", "screencap", "-p", "/sdcard/_tap.png")
    shell("pull", "/sdcard/_tap.png", PNG)
    return decode_png(PNG)


def decode_png(path):
    d = open(path, "rb").read()
    pos, idat = 8, b""
    W = H = ct = None
    while pos < len(d):
        ln = struct.unpack(">I", d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b"IHDR":
            W, H, bd, ct = struct.unpack(">IIBB", data[:10])
        elif typ == b"IDAT":
            idat += data
        elif typ == b"IEND":
            break
    bpp = {2: 3, 6: 4}.get(ct, 4)
    raw = zlib.decompress(idat)
    stride = W * bpp
    rows, prev, off = [], bytearray(stride), 0
    for y in range(H):
        fl = raw[off]; off += 1
        line = bytearray(raw[off:off + stride]); off += stride
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if fl == 1: line[i] = (line[i] + a) & 255
            elif fl == 2: line[i] = (line[i] + b) & 255
            elif fl == 3: line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif fl == 4:
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        rows.append(line); prev = line
    return rows, W, bpp


def bands(rows, W, bpp, y0=0, y1=None, x0=0, x1=None):
    """列出文字带（连续有暗像素的行）。"""
    if y1 is None: y1 = len(rows)
    if x1 is None: x1 = W
    out, start, prev_y = [], None, None
    for y in range(y0, min(y1, len(rows))):
        line = rows[y]
        cnt = 0
        xs = []
        for x in range(x0, x1):
            if line[x * bpp] < 100:
                cnt += 1; xs.append(x)
        if cnt >= 2:
            if start is None:
                start = y; sx = min(xs); ex = max(xs)
            else:
                sx = min(sx, min(xs)); ex = max(ex, max(xs))
        else:
            if start is not None:
                out.append((start, y - 1, sx, ex)); start = None
    if start is not None:
        out.append((start, min(y1, len(rows)) - 1, sx, ex))
    return out


def tap(x, y):
    shell("shell", "input", "tap", str(int(x)), str(int(y)))
    print("tap", int(x), int(y))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "shot"
    if cmd == "tap":
        tap(sys.argv[2], sys.argv[3])
    elif cmd == "shot":
        rows, W, bpp = grab_png()
        print("size", W, len(rows))
        for a, b, x0, x1 in bands(rows, W, bpp):
            print("  y %d-%d (中 %d)  x %d-%d" % (a, b, (a + b) // 2, x0, x1))
