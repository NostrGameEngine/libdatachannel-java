"""Non-executable Mach-O parser fixtures; no replacement native library is produced."""
import contextlib
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
from verify_macos_native import verify, inspect, MAIN


def command(kind, text):
    # Independent static load-command fixture authoring, never native execution.
    minimum = 12 if kind == 0x8000001C else 24
    data = text.encode() + b'\0'
    size = (minimum + len(data) + 7) // 8 * 8
    return struct.pack('<3I', kind, size, minimum) + bytes(minimum - 12) + data + bytes(size - minimum - len(data))


def image(name, dependencies=(), architecture='arm64', rpath=True, exports=('_JNI_OnLoad',)):
    commands = [command(0xD, '@rpath/' + name)]
    commands += [command(0xC, value) for value in dependencies]
    if rpath:
        commands.append(command(0x8000001C, '@loader_path'))
    symbol_offset = 32 + sum(map(len, commands)) + 24
    strings = b'\0'
    symbols = b''
    for symbol in exports:
        symbols += struct.pack('<IBBHQ', len(strings), 0xF, 1, 0, 1)
        strings += symbol.encode() + b'\0'
    commands.append(struct.pack('<6I', 2, 24, symbol_offset, len(exports), symbol_offset + len(symbols), len(strings)))
    body = b''.join(commands)
    return struct.pack('<8I', 0xFEEDFACF, 0x0100000C if architecture == 'arm64' else 0x01000007,
                       0, 6, len(commands), len(body), 0, 0) + body + symbols + strings


class MacosClosureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='static-macho-fixture-')
        self.path = Path(self.directory.name)
        self.addCleanup(self.directory.cleanup)

    def put(self, architecture='arm64', dependencies=(), exports=('_JNI_OnLoad',)):
        (self.path / MAIN).write_bytes(image(MAIN, dependencies, architecture, False, exports))

    def test_self_contained_arm64_and_x86_classifier_and_arch_detect(self):
        for architecture in ('arm64', 'x86_64'):
            self.put(architecture=architecture, dependencies=['/usr/lib/libSystem.B.dylib'])
            for prefix in ('native/', 'macos-' + architecture + '/native/'):
                jar = self.path / 'fixture.jar'
                with zipfile.ZipFile(jar, 'w') as archive:
                    archive.writestr(prefix + MAIN, (self.path / MAIN).read_bytes())
                result = verify(jar, architecture)
                self.assertEqual(result['nativeLibraries'], [MAIN])
                self.assertTrue(result['privateCrypto'])
                self.assertTrue(result['privateAllocator'])
                self.assertFalse(result['nativeExecution'])

    def test_public_dependency_symbols_refuse(self):
        for symbol in ('_SSL_new', '_mi_malloc', '_libdatachannel_mi_free', '_malloc'):
            with self.subTest(symbol=symbol):
                self.put(exports=('_JNI_OnLoad', symbol))
                with self.assertRaisesRegex(ValueError, 'Non-JNI public export'):
                    verify(self.path, 'arm64')

    def test_shared_crypto_and_allocator_dependencies_refuse(self):
        for dependency in ('/usr/lib/libssl.3.dylib', '/usr/lib/libcrypto.3.dylib',
                           '@rpath/libdatachannel_mimalloc.dylib', '@loader_path/libmimalloc.dylib'):
            with self.subTest(dependency=dependency):
                self.put(dependencies=[dependency])
                with self.assertRaisesRegex(ValueError, 'must be private'):
                    verify(self.path, 'arm64')

    def test_missing_export_metadata_refuses(self):
        self.put(exports=())
        with self.assertRaisesRegex(ValueError, 'Missing public JNI'):
            verify(self.path, 'arm64')

    def test_missing_jni_refuses(self):
        with self.assertRaisesRegex(ValueError, 'Missing required Mac JNI'):
            verify(self.path, 'arm64')

    def test_unselected_legacy_sidecars_do_not_affect_staged_jni(self):
        self.put()
        (self.path / 'libdatachannel_mimalloc.dylib').write_bytes(b'unselected legacy artifact')
        self.assertEqual(verify(self.path, 'arm64')['nativeLibraries'], [MAIN])

    def test_legacy_allocator_cannot_be_distributed_in_classifier(self):
        self.put()
        jar = self.path / 'legacy.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('native/' + MAIN, (self.path / MAIN).read_bytes())
            archive.writestr('native/libdatachannel_mimalloc.dylib', image('libdatachannel_mimalloc.dylib'))
        with self.assertRaisesRegex(ValueError, 'Unexpected shared native'):
            verify(jar, 'arm64')

    def test_unclosed_non_system_dependency_refuses(self):
        self.put(dependencies=['@rpath/libother.dylib'])
        with self.assertRaisesRegex(ValueError, 'Unclosed'):
            verify(self.path, 'arm64')

    def test_wrong_architecture_refuses(self):
        self.put(architecture='x86_64')
        with self.assertRaisesRegex(ValueError, 'MH_DYLIB'):
            verify(self.path, 'arm64')

    def test_truncated_bad_command_and_unterminated_string_refuse(self):
        original = image(MAIN)
        for bad in (original[:31], original[:-1], original[:36] + struct.pack('<I', 7) + original[40:], image(MAIN, rpath=False)[:56] + b'X' * (len(image(MAIN, rpath=False)) - 56)):
            with self.assertRaises(ValueError):
                inspect(bad, 'arm64')

    def test_duplicate_jar_resource_refuses(self):
        self.put()
        jar = self.path / 'duplicate.jar'
        with contextlib.redirect_stderr(io.StringIO()), zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('native/' + MAIN, (self.path / MAIN).read_bytes())
            archive.writestr('native/' + MAIN, (self.path / MAIN).read_bytes())
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            verify(jar, 'arm64')

    def test_selected_prebuilt_alias_uses_only_exact_packaged_jni(self):
        selected = self.path / 'jni-arm64.dylib'
        selected.write_bytes(image(MAIN))
        legacy = self.path / 'libdatachannel_mimalloc.dylib'
        legacy.write_bytes(b'unselected old allocator')
        self.assertEqual(verify(selected, 'arm64', [selected])['nativeLibraries'], [MAIN])
        with self.assertRaisesRegex(ValueError, 'Unexpected shared native'):
            verify(selected, 'arm64', [selected, legacy])
        selected.write_bytes(image(selected.name))
        with self.assertRaisesRegex(ValueError, 'Noncanonical'):
            verify(selected, 'arm64', [selected])


if __name__ == '__main__':
    unittest.main(verbosity=2)
