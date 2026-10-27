#!/usr/bin/env python3
"""Regression checks for the Windows export formats emitted by GNU objdump."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


VERIFIER = Path(__file__).resolve().parents[1] / "jni/cmake/verify_private_dependencies.cmake"


@unittest.skipIf(os.name == "nt", "The objdump fixture is a POSIX executable")
class PrivateDependenciesTest(unittest.TestCase):
    def verify(self, headers, filename="binding.dll", platform=""):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            library = root / filename
            library.touch()
            (root / "headers.txt").write_text(headers)
            inspector = root / "objdump"
            inspector.write_text('#!/bin/sh\ncat "$(dirname "$0")/headers.txt"\n')
            inspector.chmod(0o755)
            return subprocess.run(
                [shutil.which("cmake"), f"-DINPUT_FILE={library}",
                 f"-DOBJDUMP_TOOL={inspector}", f"-DNM_TOOL={inspector}",
                 f"-DPLATFORM={platform}", "-DSTATIC_ARCHIVE=ON", "-P", str(VERIFIER)],
                capture_output=True, text=True,
            )

    def test_accepts_original_export_table(self):
        result = self.verify("[Ordinal/Name Pointer] Table\n\t[   0] JNI_OnLoad\n")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_accepts_export_table_with_ordinal_and_hint_columns(self):
        result = self.verify(
            "[Ordinal/Name Pointer] Table -- Ordinal Base 1\r\n"
            "\t          Ordinal   Hint Name\r\n"
            "\t[   0] +base[   1]  0000 JNI_OnLoad\r\n"
            "\t[   1] +base[   2]  0001 Java_example_nativeMethod\r\n"
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_public_crypto_in_new_table(self):
        result = self.verify(
            "[Ordinal/Name Pointer] Table -- Ordinal Base 1\n"
            "\t Ordinal Hint Name\n"
            "\t[   0] +base[   1]  0000 JNI_OnLoad\n"
            "\t[   1] +base[   2]  0001 SSL_CTX_new\n"
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("SSL_CTX_new", result.stderr)

    def test_rejects_shared_crypto(self):
        result = self.verify("DLL Name: libcrypto-3-x64.dll\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("shared OpenSSL", result.stderr)

    def test_rejects_shared_allocator(self):
        result = self.verify("DLL Name: libdatachannel_mimalloc.dll\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("shared OpenSSL or mimalloc", result.stderr)

    def test_rejects_public_allocator(self):
        for symbol in ("mi_malloc", "libdatachannel_mi_free", "malloc", "free"):
            result = self.verify("[Ordinal/Name Pointer] Table\n\t[   0] JNI_OnLoad\n\t[   1] " + symbol + "\n")
            self.assertNotEqual(0, result.returncode)
            self.assertIn(symbol, result.stderr)

    def test_rejects_missing_exports(self):
        result = self.verify("[Ordinal/Name Pointer] Table\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("No JNI exports", result.stderr)

    def test_rejects_initial_exec_tls_in_jni(self):
        result = self.verify("Dynamic Section:\n  FLAGS STATIC_TLS\n", "binding.so")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("dlopen-compatible thread-local storage", result.stderr)

    def test_rejects_common_storage_in_static_ios_archive(self):
        result = self.verify(
            "00000000 (__TEXT,__text) external _JNI_OnLoad\n"
            "00000008 (common) (alignment 2^3) private external __mi_numa_node_count\n",
            "binding.a", "iOS")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("materialize common definitions", result.stderr)

    def test_accepts_local_storage_in_static_ios_archive(self):
        result = self.verify(
            "00000000 (__TEXT,__text) external _JNI_OnLoad\n"
            "00000008 (__DATA,__bss) non-external _sctp_lock\n",
            "binding.a", "iOS")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_even_local_mimalloc_in_ios(self):
        result = self.verify(
            "00000000 (__TEXT,__text) external _JNI_OnLoad\n"
            "00000008 (__TEXT,__text) non-external _mi_malloc\n",
            "binding.a", "iOS")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("platform allocator", result.stderr)

    def test_rejects_shared_desktop_cpp_runtime(self):
        result = self.verify("Dynamic Section:\n NEEDED libstdc++.so.6\n", "binding.so", "Linux")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("embed its C++ runtime", result.stderr)

    def test_rejects_windows_crt_heap_imports(self):
        for symbol in ("malloc", "calloc", "realloc", "free", "_strdup"):
            result = self.verify("Import Tables:\n  00000000  0000 " + symbol + "\n")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("resolve C heap requests", result.stderr)

    def test_rejects_local_mimalloc_in_android(self):
        result = self.verify("00000000 T JNI_OnLoad\n00000008 t mi_malloc\n", "binding.so", "Android")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("platform allocator", result.stderr)

    def test_accepts_ios_platform_allocator_imports(self):
        result = self.verify(
            "00000000 (__TEXT,__text) external _JNI_OnLoad\n"
            "                 (undefined) external _malloc\n"
            "                 (undefined) external _free\n",
            "binding.a", "iOS")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_mimalloc_references_in_ios(self):
        result = self.verify(
            "00000000 (__TEXT,__text) external _JNI_OnLoad\n"
            "                 (undefined) external _mi_malloc\n",
            "binding.a", "iOS")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("platform allocator", result.stderr)


if __name__ == "__main__":
    unittest.main()
