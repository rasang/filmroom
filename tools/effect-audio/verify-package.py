"""Validate final (including repacked/signed) APK audio against approved private inputs."""
import hashlib
import pathlib
import sys
import zipfile

NAMES = ("light_table.m4a", "optical_leak.m4a", "optical_leak_slow.m4a")


def verify(apk, manifest):
    expected = dict(line.split()[::-1] for line in pathlib.Path(manifest).read_text().splitlines() if line.strip())
    if set(expected) != set(NAMES):
        raise ValueError("Incomplete effect audio checksum manifest")
    with zipfile.ZipFile(apk) as archive:
        for name in NAMES:
            info = archive.getinfo("assets/effect_audio/" + name)
            if info.compress_type != zipfile.ZIP_STORED:
                raise ValueError(f"{name}: compressed audio cannot be opened with AssetManager.openFd")
            data = archive.read(info)
            if hashlib.sha256(data).hexdigest() != expected[name]:
                raise ValueError(f"{name}: audio checksum mismatch")
    return len(NAMES)


if __name__ == "__main__":
    manifest = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/assets/effect_audio/SHA256SUMS"
    print(f"{verify(sys.argv[1], manifest)} packaged audio assets verified")
