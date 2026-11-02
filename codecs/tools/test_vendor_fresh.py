import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('vendor_guard',Path(__file__).with_name('ensure-vendor-fresh.py'))
guard=importlib.util.module_from_spec(spec);spec.loader.exec_module(guard)

class VendorFreshTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)/'codecs';self.vendor=self.root/'vendor/rawler-0.7.2';self.vendor.mkdir(parents=True)
        (self.vendor/'Cargo.toml').write_text('[package]\nname="rawler"\n');self.source=self.vendor/'source.rs';self.source.write_text('first')
        self.target=Path(self.temp.name)/'cache'
    def tearDown(self):self.temp.cleanup()
    def test_changed_bytes_clean_release_once_then_stable_reuses(self):
        with patch.object(guard.subprocess,'run') as run:
            self.assertEqual(0,guard.ensure(self.root,self.target,'aarch64-linux-android','release'))
            self.assertIn('--release',run.call_args.args[0]);self.assertIn('aarch64-linux-android',run.call_args.args[0]);self.assertEqual(1,run.call_count)
            self.assertEqual(0,guard.ensure(self.root,self.target,'aarch64-linux-android','release'));self.assertEqual(1,run.call_count)
            self.source.write_text('second');guard.ensure(self.root,self.target,'aarch64-linux-android','release');self.assertEqual(2,run.call_count)
    def test_dev_and_targets_are_independent(self):
        with patch.object(guard.subprocess,'run') as run:
            guard.ensure(self.root,self.target,'host','dev');self.assertNotIn('--release',run.call_args.args[0]);self.assertNotIn('--target',run.call_args.args[0])
            guard.ensure(self.root,self.target,'host','release');guard.ensure(self.root,self.target,'x86_64-linux-android','release');self.assertEqual(3,run.call_count)
    def test_check_does_not_mutate_and_failed_clean_never_stamps(self):
        with patch.object(guard.subprocess,'run') as run:
            self.assertEqual(2,guard.ensure(self.root,self.target,'host','release',True));run.assert_not_called();self.assertFalse(self.target.exists())
            run.side_effect=subprocess.CalledProcessError(1,['cargo'])
            with self.assertRaises(subprocess.CalledProcessError):guard.ensure(self.root,self.target,'host','release')
            self.assertFalse(self.target.exists())
    def test_checksum_file_and_symlink_are_not_ignored(self):
        before=guard.fingerprint(self.vendor);(self.vendor/'.cargo-checksum.json').write_text('{}');self.assertNotEqual(before,guard.fingerprint(self.vendor))
        (self.vendor/'link').symlink_to(self.source)
        with self.assertRaises(RuntimeError):guard.fingerprint(self.vendor)

if __name__=='__main__':unittest.main()
