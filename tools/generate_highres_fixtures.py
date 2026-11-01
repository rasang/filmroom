#!/usr/bin/env python3
"""生成本地12/名义50MP高熵测试图片，不读取任何用户照片。"""
import argparse
import json
import pathlib
import random
import struct
import time
import zlib


def chunk(stream, kind, data):
    stream.write(struct.pack(">I", len(data)))
    stream.write(kind)
    stream.write(data)
    stream.write(struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))


def make_png(root, width, height, channels, name):
    start = time.monotonic()
    path = root / name
    rng = random.Random(20261030 + channels)
    with path.open("wb") as stream:
        stream.write(b"\x89PNG\r\n\x1a\n")
        chunk(stream, b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2 if channels == 3 else 6, 0, 0, 0))
        chunk(stream, b"sRGB", b"\x00")
        compressor = zlib.compressobj(6)
        pending = bytearray()
        for _ in range(height):
            pending.extend(compressor.compress(b"\x00" + rng.randbytes(width * channels)))
            while len(pending) >= 65536:
                chunk(stream, b"IDAT", bytes(pending[:65536]))
                del pending[:65536]
        pending.extend(compressor.flush())
        while pending:
            chunk(stream, b"IDAT", bytes(pending[:65536]))
            del pending[:65536]
        chunk(stream, b"IEND", b"")
    return {"filename": path.name, "width": width, "height": height, "pixels": width * height,
            "channels": channels, "encoded_bytes": path.stat().st_size,
            "synthetic": True, "elapsed_seconds": round(time.monotonic() - start, 3)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=pathlib.Path, help="输出目录；不要指向用户照片目录")
    parser.add_argument("--orientations-only", action="store_true", help="仅补充12MP八方向四色JPEG")
    args = parser.parse_args()
    root = args.destination.resolve()
    root.mkdir(parents=True, exist_ok=True)
    try:
        from PIL import Image
    except ImportError as error:
        raise SystemExit("缺少Pillow。请在独立Python环境安装Pillow==13.0.0，然后重试。") from error
    records = []
    for spec in ([] if args.orientations_only else [(4000, 3000, 3, "synthetic-noise-12mp-rgb.png"),
                 (8192, 6144, 3, "synthetic-noise-50mp-rgb.png"),
                 (8192, 6144, 4, "synthetic-noise-50mp-rgba.png")]):
        result = make_png(root, *spec)
        records.append(result)
        print("已生成合成PNG：" + json.dumps(result, ensure_ascii=False), flush=True)
    for size in ([] if args.orientations_only else ["12mp", "50mp"]):
        for orientation in ([1] if size == "12mp" else [1, 6]):
            start = time.monotonic()
            target = root / f"synthetic-noise-{size}-orientation{orientation}.jpg"
            with Image.open(root / f"synthetic-noise-{size}-rgb.png") as image:
                exif = Image.Exif()
                exif[274] = orientation
                image.save(target, "JPEG", quality=90, subsampling=0, exif=exif)
                width, height = image.size
            result = {"filename": target.name, "width": width, "height": height,
                      "exif_orientation": orientation,
                      "oriented_width": height if orientation >= 5 else width,
                      "oriented_height": width if orientation >= 5 else height,
                      "encoded_bytes": target.stat().st_size, "synthetic": True,
                      "elapsed_seconds": round(time.monotonic() - start, 3)}
            records.append(result)
            print("已生成合成JPEG：" + json.dumps(result, ensure_ascii=False), flush=True)
    from PIL import ImageDraw
    quadrants = Image.new("RGB", (4000, 3000))
    draw = ImageDraw.Draw(quadrants)
    for rect, color in [((0, 0, 1999, 1499), (210, 50, 20)), ((2000, 0, 3999, 1499), (30, 170, 60)),
                        ((0, 1500, 1999, 2999), (25, 70, 210)), ((2000, 1500, 3999, 2999), (220, 180, 35))]:
        draw.rectangle(rect, fill=color)
    for orientation in range(1, 9):
        target = root / f"synthetic-quadrants-12mp-orientation{orientation}.jpg"
        exif = Image.Exif()
        exif[274] = orientation
        quadrants.save(target, "JPEG", quality=100, subsampling=0, exif=exif)
        result = {"filename": target.name, "width": 4000, "height": 3000, "exif_orientation": orientation,
                  "oriented_width": 3000 if orientation >= 5 else 4000,
                  "oriented_height": 4000 if orientation >= 5 else 3000,
                  "encoded_bytes": target.stat().st_size, "synthetic": True,
                  "purpose": "仅用于方向像素核对，不能替代高熵输入边界"}
        records.append(result)
        print("已生成八方向JPEG：" + json.dumps(result, ensure_ascii=False), flush=True)
    quadrants.close()
    manifest_name = "orientation-fixture-manifest.json" if args.orientations_only else "fixture-manifest.json"
    (root / manifest_name).write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("完成。8192×6144准确为50,331,648像素（名义50MP）。这些图片不是用户照片。")


if __name__ == "__main__":
    main()
