#!/usr/bin/env python3
"""Regression checks for export boundaries and private-data detection; no device needed."""
from pathlib import Path
import tempfile
import unittest
import public_snapshot as snapshot


class SnapshotTest(unittest.TestCase):
    def test_patterns_detect_synthetic_contamination_without_echoing_values(self):
        samples = [
            ('Li' + 'ci', 'personal name'),
            ('Li' + 'cínia', 'personal name'),
            ('/Users/' + 'bright' + 'blu', 'personal path'),
            ('bright' + 'blu', 'owner identifier'),
            ('sample' + '@' + 'example.invalid', 'email'),
            ('this' + '@' + 'example.invalid', 'email'),
            ('+'.join(['', '000']) + ' ' + ' '.join(['000'] * 3), 'phone'),
            ('-'.join(['000', '000', '0000']), 'phone'),
            ('0' * 10, 'phone'),
            ('ghp_' + 'a' * 36, 'token'),
            ('api_key = "' + 'a' * 25 + '"', 'credential assignment'),
        ]
        for value, category in samples:
            with self.subTest(category=category):
                # Bare digit strings are tested as source literals.
                content = '"' + value + '"'
                self.assertIn(category, snapshot.scan(Path('fixture.kt'), content.encode(), []))
        self.assertIn('private app', snapshot.scan(Path('fixture.kt'), b'org.example.local', ['org.example.local']))
        self.assertIn('key container', snapshot.scan(Path('asset.bin'), bytes.fromhex('feedfeed'), []))

    def test_namespace_and_kotlin_syntax_are_not_addresses(self):
        self.assertEqual([], snapshot.scan(Path('com/brightblu/parc/Test.kt'),
                         b'package com.brightblu.parc\nthis' + b'@' + b'asStore.settings', []))
        self.assertIn('owner identifier', snapshot.scan(Path('fixture'),
                      ('com.' + 'bright' + 'blu.parcel').encode(), []))

    def test_exclusions(self):
        for name in ('app/build/out', 'core/data/.kotlin/cache', 'app/debug.keystore',
                     'private/input.json', 'reports/image.png', 'keys/secret',
                     'app/src/local.properties', 'app/src/image.APK', 'PROJECT.md'):
            if name == 'PROJECT.md':
                self.assertNotIn(Path(name), snapshot.export_files(snapshot.ROOT))
            else:
                self.assertTrue(snapshot.excluded(Path(name)), name)

    def test_new_repo_rejects_contamination_symlinks_and_old_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            public = root / 'public'
            snapshot.create(public, [])
            snapshot.verify(public, [])
            self.assertEqual('Parc', snapshot.git(public, 'config', '--local', 'user.name'))
            with self.assertRaises(ValueError):
                snapshot.create(public, [])
            leak = public / 'app/src/leak.txt'
            leak.write_text('sample' + '@' + 'example.invalid')
            snapshot.git(public, 'add', '--all')
            with self.assertRaisesRegex(ValueError, 'email'):
                snapshot.verify(public, [])
            snapshot.git(public, 'reset', '--quiet', 'HEAD', '--', 'app/src/leak.txt')
            leak.unlink()
            leak.symlink_to(root / 'absent')
            with self.assertRaisesRegex(ValueError, 'Symlink'):
                snapshot.verify(public, [])
            leak.unlink()
            snapshot.git(public, 'commit', '--quiet', '--allow-empty', '-m', 'Extra commit')
            with self.assertRaisesRegex(ValueError, 'exactly one commit'):
                snapshot.verify(public, [])

    def test_source_symlink_is_never_followed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for module in (*snapshot.MODULES, 'gradle'):
                (root / module).mkdir(parents=True)
            (root / 'app/src').symlink_to(root / 'outside')
            with self.assertRaisesRegex(ValueError, 'Symlink'):
                list(snapshot.export_files(root))

    def test_every_public_document_is_exported_once(self):
        self.assertEqual(
            ('README', 'LICENSE', 'PRIVACY', 'TRADEMARK', 'CONTRIBUTING', 'CLA',
             'CLA-SIGNATURES', 'NOTICE'),
            snapshot.DOCUMENTS,
        )
        exported = [path.as_posix() for path in snapshot.export_files(snapshot.ROOT)]
        for stem in snapshot.DOCUMENTS:
            matches = [path for path in exported
                       if path == stem or path.startswith(stem + '.')]
            self.assertEqual(1, len(matches), stem)


if __name__ == '__main__':
    unittest.main()
