#!/usr/bin/env python3
"""Independently check SBS captures produced by the vr2sbs SBS diagnostic.

Usage:
    python tools/analyze_sbs_capture.py <capture.png> [<capture.png> ...]

Each capture is split at width/2 into the left-eye and right-eye halves.
The tool reports the mean absolute Laplacian of the luma channel for both
halves and their ratio (right/left). A sharpness collapse of only the right
half shows up as a ratio far below 1.

The script also writes "<capture>_compare.png" next to each input, containing
the centred crop of the left half and the same crop of the right half side by
side, so the two halves can be inspected visually.

The source-normalized ratio in the app's report.txt additionally divides out
real left/right differences in the source picture; this tool is a raw check.
Only Python's standard library is used.
"""

import struct
import sys
import zlib

SAMPLE_STEP = 2
EDGE_MARGIN = 2


def read_png(path):
    """Return (width, height, channels, pixel_bytes) for an 8-bit RGB/RGBA PNG."""
    with open(path, "rb") as handle:
        data = handle.read()

    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("%s is not a PNG file" % path)

    offset = 8
    width = height = bit_depth = color_type = interlace = None
    compressed = bytearray()

    while offset + 8 <= len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        chunk_type = data[offset + 4:offset + 8]
        chunk_data = data[offset + 8:offset + 8 + length]
        offset += 12 + length

        if chunk_type == b"IHDR":
            (width, height, bit_depth, color_type,
             compression, filter_method, interlace) = struct.unpack(">IIBBBBB", chunk_data)
            if compression != 0 or filter_method != 0:
                raise ValueError("Unsupported PNG compression/filter method")
            if interlace != 0:
                raise ValueError("Interlaced PNG is not supported")
            if bit_depth != 8 or color_type not in (2, 6):
                raise ValueError("Only 8-bit RGB/RGBA PNG is supported")
        elif chunk_type == b"IDAT":
            compressed += chunk_data
        elif chunk_type == b"IEND":
            break

    if width is None:
        raise ValueError("PNG has no IHDR chunk")

    channels = 4 if color_type == 6 else 3
    raw = zlib.decompress(bytes(compressed))
    stride = width * channels
    expected = height * (stride + 1)
    if len(raw) != expected:
        raise ValueError("Unexpected PNG data size: %d != %d" % (len(raw), expected))

    out = bytearray(stride * height)
    previous = bytearray(stride)
    pos = 0
    for y in range(height):
        filter_type = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride

        if filter_type == 0:
            pass
        elif filter_type == 1:  # Sub
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:  # Average
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:  # Paeth
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = previous[i]
                c = previous[i - channels] if i >= channels else 0
                p = a + b - c
                pa = abs(p - a)
                pb = abs(p - b)
                pc = abs(p - c)
                if pa <= pb and pa <= pc:
                    predictor = a
                elif pb <= pc:
                    predictor = b
                else:
                    predictor = c
                line[i] = (line[i] + predictor) & 0xFF
        else:
            raise ValueError("Unknown PNG filter type %d on row %d" % (filter_type, y))

        out[y * stride:(y + 1) * stride] = line
        previous = line

    return width, height, channels, out


def write_png(path, width, height, rgba):
    if len(rgba) != width * height * 4:
        raise ValueError("RGBA buffer has the wrong size")

    raw = bytearray()
    row_bytes = width * 4
    for y in range(height):
        raw.append(0)
        raw += rgba[y * row_bytes:(y + 1) * row_bytes]

    def chunk(name, payload):
        body = name + payload
        return (struct.pack(">I", len(payload)) + body +
                struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    with open(path, "wb") as handle:
        handle.write(b"\x89PNG\r\n\x1a\n")
        handle.write(chunk(b"IHDR", ihdr))
        handle.write(chunk(b"IDAT", zlib.compress(bytes(raw), 6)))
        handle.write(chunk(b"IEND", b""))


def luma_at(pixels, width, channels, x, y):
    i = (y * width + x) * channels
    r = pixels[i]
    g = pixels[i + 1]
    b = pixels[i + 2]
    return (77 * r + 150 * g + 29 * b) >> 8


def sharpness(pixels, width, channels, x0, x1, height):
    """Mean absolute Laplacian of luma inside [x0, x1) x [0, height)."""
    if x1 - x0 <= 2 * EDGE_MARGIN or height <= 2 * EDGE_MARGIN:
        return 0.0

    total = 0
    count = 0
    y = EDGE_MARGIN
    while y < height - EDGE_MARGIN:
        x = x0 + EDGE_MARGIN
        while x < x1 - EDGE_MARGIN:
            center = luma_at(pixels, width, channels, x, y)
            laplacian = (4 * center
                         - luma_at(pixels, width, channels, x, y - 1)
                         - luma_at(pixels, width, channels, x, y + 1)
                         - luma_at(pixels, width, channels, x - 1, y)
                         - luma_at(pixels, width, channels, x + 1, y))
            total += abs(laplacian)
            count += 1
            x += SAMPLE_STEP
        y += SAMPLE_STEP
    return total / count if count else 0.0


def save_compare(path, width, height, channels, pixels):
    half = width // 2
    crop_w = max(1, half // 2)
    crop_h = max(1, height // 2)
    x0 = (half - crop_w) // 2
    y0 = (height - crop_h) // 2

    out_w = crop_w * 2
    rgba = bytearray(out_w * crop_h * 4)
    for y in range(crop_h):
        for x in range(crop_w):
            for side in (0, 1):
                src_x = (0 if side == 0 else half) + x0 + x
                src_y = y0 + y
                s = (src_y * width + src_x) * channels
                d = (y * out_w + side * crop_w + x) * 4
                rgba[d] = pixels[s]
                rgba[d + 1] = pixels[s + 1]
                rgba[d + 2] = pixels[s + 2]
                rgba[d + 3] = pixels[s + 3] if channels == 4 else 255
    write_png(path, out_w, crop_h, rgba)


def verdict(ratio):
    if ratio <= 0.0:
        return "INCONCLUSIVE"
    if ratio >= 0.90:
        return "OK: both halves keep similar detail"
    if ratio >= 0.75:
        return "MILD: right half slightly softer"
    if ratio >= 0.55:
        return "WARNING: right half noticeably softer"
    return "SEVERE: right half far softer (consistent with ~half resolution)"


def analyze(path):
    width, height, channels, pixels = read_png(path)
    half = width // 2
    left = sharpness(pixels, width, channels, 0, half, height)
    right = sharpness(pixels, width, channels, half, width, height)
    ratio = (right / left) if left > 0 else 0.0

    print("%s" % path)
    print("  size          : %dx%d (%d channels)" % (width, height, channels))
    print("  left  eye     : %.3f" % left)
    print("  right eye     : %.3f" % right)
    print("  R/L ratio     : %.4f" % ratio)
    print("  verdict       : %s" % verdict(ratio))

    compare_path = path + "_compare.png"
    save_compare(compare_path, width, height, channels, pixels)
    print("  comparison    : %s" % compare_path)
    print()


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    for path in argv[1:]:
        analyze(path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
