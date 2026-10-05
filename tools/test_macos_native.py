"""Non-executable Mach-O parser fixtures; no replacement native library is produced."""
import contextlib
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
from verify_macos_native import verify, inspect, MAIN, ALLOCATOR


def command(kind, text):
    # Independent static load-command fixture authoring, never native execution.
    minimum = 12 if kind == 0x8000001C else 24
    data = text.encode() + b'\0'
    size = (minimum + len(data) + 7) // 8 * 8
    return struct.pack('<3I', kind, size, minimum) + bytes(minimum - 12) + data + bytes(size - minimum - len(data))


def image(name, dependencies=(), architecture='arm64', rpath=True):
    commands = [command(0xD, '@rpath/' + name)]
    commands += [command(0xC, value) for value in dependencies]
    if rpath:
        commands.append(command(0x8000001C, '@loader_path'))
    body = b''.join(commands)
    return struct.pack('<8I', 0xFEEDFACF, 0x0100000C if architecture == 'arm64' else 0x01000007,
                       0, 6, len(commands), len(body), 0, 0) + body


class MacosClosureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='static-macho-fixture-')
        self.path = Path(self.directory.name)
        self.addCleanup(self.directory.cleanup)

    def put(self, dependency=ALLOCATOR, architecture='arm64', rpath=True):
        (self.path / MAIN).write_bytes(image(MAIN, ['@rpath/' + dependency, '/usr/lib/libSystem.B.dylib'], architecture, rpath))
        (self.path / ALLOCATOR).write_bytes(image(ALLOCATOR, ['/usr/lib/libSystem.B.dylib'], architecture))

    def test_closed_arm64_and_x86_classifier_and_arch_detect(self):
        for architecture in ('arm64', 'x86_64'):
            self.put(architecture=architecture)
            self.assertTrue(verify(self.path, architecture)['staticDependencyClosure'])
            for prefix in ('native/', 'macos-' + architecture + '/native/'):
                jar = self.path / 'fixture.jar'
                with zipfile.ZipFile(jar, 'w') as archive:
                    for name in (MAIN, ALLOCATOR):
                        archive.writestr(prefix + name, (self.path / name).read_bytes())
                result = verify(jar, architecture)
                self.assertEqual(result['nativeLibraries'], sorted([MAIN, ALLOCATOR]))
                self.assertFalse(result['nativeExecution'])

    def test_missing_allocator_refuses_before_native_loading(self):
        self.put(); (self.path / ALLOCATOR).unlink()
        with self.assertRaisesRegex(ValueError, 'Missing required.*mimalloc'):
            verify(self.path, 'arm64')

    def test_actual_two_dot_dependency_refuses_even_with_one_dot_sibling(self):
        self.put(dependency='libdatachannel_mimalloc..dylib')
        with self.assertRaisesRegex(ValueError, 'Unclosed.*mimalloc'):
            verify(self.path, 'arm64')

    def test_mixed_architecture_refuses(self):
        self.put(); (self.path / ALLOCATOR).write_bytes(image(ALLOCATOR, architecture='x86_64'))
        with self.assertRaisesRegex(ValueError, 'MH_DYLIB'):
            verify(self.path, 'arm64')

    def test_wrong_allocator_install_id_refuses(self):
        self.put(); (self.path / ALLOCATOR).write_bytes(image('libdatachannel_mimalloc..dylib'))
        with self.assertRaisesRegex(ValueError, 'Noncanonical'):
            verify(self.path, 'arm64')

    def test_missing_loader_relative_search_path_refuses(self):
        self.put(rpath=False)
        with self.assertRaisesRegex(ValueError, 'lacks @loader_path'):
            verify(self.path, 'arm64')

    def test_truncated_bad_command_and_unterminated_string_refuse(self):
        original = image(MAIN)
        for bad in (original[:31], original[:-1], original[:36] + struct.pack('<I', 7) + original[40:], image(MAIN, rpath=False)[:56] + b'X' * (len(image(MAIN, rpath=False)) - 56)):
            with self.assertRaises(ValueError):
                inspect(bad, 'arm64')

    def test_duplicate_jar_resource_refuses(self):
        self.put(); jar = self.path / 'duplicate.jar'
        with contextlib.redirect_stderr(io.StringIO()), zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('native/' + MAIN, (self.path / MAIN).read_bytes())
            archive.writestr('native/' + MAIN, (self.path / MAIN).read_bytes())
            archive.writestr('native/' + ALLOCATOR, (self.path / ALLOCATOR).read_bytes())
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            verify(jar, 'arm64')


if __name__ == '__main__':
    unittest.main(verbosity=2)
