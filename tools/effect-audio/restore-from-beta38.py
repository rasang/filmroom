"""Restore private build inputs from the verified beta38 APK; do not publish extracted tracks."""
import hashlib
import importlib.util
import pathlib
import sys
import zipfile

APK_SHA256 = "7cb03a8d95c26838a624f6747fdcd048969c976f781d5a2fa855a46912f68c91"
apk = pathlib.Path(sys.argv[1])
if hashlib.sha256(apk.read_bytes()).hexdigest() != APK_SHA256:
    raise ValueError("Not the verified beta38 APK")
root = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("verify_package", pathlib.Path(__file__).with_name("verify-package.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
destination = root / "app/src/main/assets/effect_audio"
verifier.verify(apk, destination / "SHA256SUMS")
with zipfile.ZipFile(apk) as archive:
    for name in verifier.NAMES:
        output = destination / name
        data = archive.read("assets/effect_audio/" + name)
        if output.exists() and output.read_bytes() != data:
            raise ValueError(f"Refusing to overwrite different local input: {name}")
    for name in verifier.NAMES:
        (destination / name).write_bytes(archive.read("assets/effect_audio/" + name))
print("Restored 3 verified private inputs; keep extracted audio out of public repositories.")
