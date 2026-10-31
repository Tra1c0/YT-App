#!/usr/bin/env python3
"""YT: checks a raw screenshot (adb exec-out screencap, without -p) for the sound wave right
below YT's floating player.

Usage: wave-check.py <screen.raw> <x> <y> <width> <video_height> <strip> [<old_x> <old_y>]
       wave-check.py --png <screen.raw> <out.png>   (a PNG of the raw screenshot, to publish)
  x, y, width: the floating window on the screen (its top-left is the video's top-left)
  video_height, strip: the video's height and the wave's strip below it, in px
  old_x, old_y: where the window was before (no wave may be left there)

Prints what it measured and exits 1 when the wave is missing below the video, has a gap to
the video's edge, or is still at the old place. Pure Python: no image library needed."""
import struct
import sys
import zlib


def load(path):
    data = open(path, "rb").read()
    w = int.from_bytes(data[0:4], "little")
    h = int.from_bytes(data[4:8], "little")
    header = len(data) - w * h * 4
    if header not in (12, 16) or w <= 0 or h <= 0:
        raise SystemExit("not a raw screenshot: %s (size %d, %dx%d)" % (path, len(data), w, h))
    return w, h, data[header:]


def wave_like(r, g, b):
    # the wave's blue, violet and pink, on whatever lies behind it
    blue = b > 150 and b > g + 40 and b > r + 20
    pinkish = r > 150 and b > 110 and g < r - 40
    return blue or pinkish


def share(px, w, x0, y0, x1, y1):
    count = total = 0
    for y in range(max(0, y0), y1):
        row = y * w * 4
        for x in range(max(0, x0), x1, 2):
            i = row + x * 4
            total += 1
            if wave_like(px[i], px[i + 1], px[i + 2]):
                count += 1
    return count / total if total else 0.0


def first_wave_row(px, w, h, x, y_from, y_to):
    for y in range(max(0, y_from), min(h, y_to)):
        i = (y * w + x) * 4
        if wave_like(px[i], px[i + 1], px[i + 2]):
            return y
    return None


def png(path, out):
    w, h, px = load(path)
    rows = b"".join(b"\x00" + bytes(px[y * w * 4:(y + 1) * w * 4]) for y in range(h))

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    with open(out, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(rows, 6)))
        f.write(chunk(b"IEND", b""))


def main():
    a = sys.argv[1:]
    if a and a[0] == "--png":
        png(a[1], a[2])
        return
    if len(a) < 6:
        raise SystemExit(__doc__)
    path = a[0]
    x, y, width, video, strip = (int(v) for v in a[1:6])
    old = (int(a[6]), int(a[7])) if len(a) >= 8 else None
    w, h, px = load(path)
    bottom = y + video
    middle = share(px, w, x + width * 3 // 10, bottom, x + width * 7 // 10, min(h, bottom + strip))
    inside = share(px, w, x + width // 10, y + video // 4, x + width * 9 // 10, bottom - video // 4)
    # the first wave pixel below the video's edge, across the middle half
    rows = [first_wave_row(px, w, h, x + width * k // 8, bottom - 2, bottom + strip) for k in (3, 4, 5)]
    gaps = [r - bottom for r in rows if r is not None]
    ok = True
    print("wave below the video: %.1f%% of the strip's middle; inside the video %.1f%%" % (100 * middle, 100 * inside))
    print("first wave row below the edge (px): %s" % gaps)
    if middle < 0.04:
        print("FAIL: no wave below the video")
        ok = False
    if not gaps or max(gaps) > max(3, strip // 4):
        print("FAIL: a gap between the video and the wave")
        ok = False
    if old is not None:
        ox, oy = old
        far = abs(ox - x) > width or abs(oy - y) > video + strip
        if far:
            left_behind = share(px, w, ox + width * 3 // 10, oy + video, ox + width * 7 // 10, min(h, oy + video + strip))
            print("at the old place: %.1f%%" % (100 * left_behind))
            if left_behind > 0.5 * middle and left_behind > 0.04:
                print("FAIL: wave left behind at the old place")
                ok = False
    print("PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
