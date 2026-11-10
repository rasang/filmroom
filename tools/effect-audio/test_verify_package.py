import hashlib
import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("verify_package", pathlib.Path(__file__).with_name("verify-package.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PackageAudioTest(unittest.TestCase):
    def check_package(self, missing=False, compressed=False, corrupt=False):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            expected = b"approved audio fixture"
            (root / "SHA256SUMS").write_text("".join(
                hashlib.sha256(expected).hexdigest() + "  " + name + "\n" for name in module.NAMES))
            with zipfile.ZipFile(root / "test.apk", "w") as archive:
                for i, name in enumerate(module.NAMES):
                    if missing and i == 0:
                        continue
                    archive.writestr("assets/effect_audio/" + name,
                                     b"wrong" if corrupt and i == 0 else expected,
                                     compress_type=zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED)
            return module.verify(root / "test.apk", root / "SHA256SUMS")

    def test_complete(self):
        self.assertEqual(self.check_package(), 3)

    def test_missing(self):
        with self.assertRaises(KeyError):
            self.check_package(missing=True)

    def test_compressed(self):
        with self.assertRaises(ValueError):
            self.check_package(compressed=True)

    def test_corrupt(self):
        with self.assertRaises(ValueError):
            self.check_package(corrupt=True)


if __name__ == "__main__":
    unittest.main()
