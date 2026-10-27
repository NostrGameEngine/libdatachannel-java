#!/usr/bin/env python3
"""Exercise heap import rejection, including a real foreign-heap shared library."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from audit_allocator_imports import forbidden, inspect, symbols_from_nm, symbols_from_pe


class AllocatorAuditTest(unittest.TestCase):
    def test_elf_versions_and_cpp_operators(self):
        imports = symbols_from_nm(" U malloc@GLIBC_2.2.5\n U _Znwm@GLIBCXX_3.4\n U _ZdlPv\n U freeaddrinfo\n")
        self.assertEqual(["_ZdlPv", "_Znwm", "malloc"], forbidden(imports))

    def test_macho_formatting_and_exception_storage(self):
        imports = symbols_from_nm("_asprintf_l\n___cxa_allocate_exception\n_freeifaddrs\n", macho=True)
        self.assertEqual(["__cxa_allocate_exception", "asprintf_l"], forbidden(imports))

    def test_pe_does_not_confuse_private_exports_with_imports(self):
        headers = ("The Import Tables\n DLL Name: msvcrt.dll\n  1234 0000 _aligned_malloc\n  2345 0001 HeapFree\n"
                   "The Export Tables\n[Ordinal/Name Pointer] Table\n  [ 0] malloc\n")
        self.assertEqual(["_aligned_malloc"], forbidden(symbols_from_pe(headers)))

    def test_llvm_pe_hint_name_columns_and_exports(self):
        headers = ("The Import Tables:\n DLL Name: msvcrt.dll\n Hint/Ord Name\n"
                   " 1025 malloc\n  932 free\nExport Table:\n"
                   " 1 0a000 foreign_heap\n 2 0a010 not_an_import\n")
        self.assertEqual(["free", "malloc"], forbidden(symbols_from_pe(headers)))
        self.assertEqual(["malloc", "free"], symbols_from_pe(headers))

    def test_gnu_pe_ordinal_and_hint_columns(self):
        headers = ("The Import Tables\n DLL Name: msvcrt.dll\n"
                   " vma: Ordinal Hint Member-Name Bound-To\n"
                   " 00a7ba00 <none> 001f malloc\n"
                   " 00a7ba08 <none> 00c2 free\n"
                   " 00a7b014 00a7b108 00000000 00000000 00a7d4bc 00a7ba50\n"
                   "The Export Tables\n")
        self.assertEqual(["malloc", "free"], symbols_from_pe(headers))
        self.assertEqual(["free", "malloc"], forbidden(symbols_from_pe(headers)))

    def test_export_only_output_is_not_mistaken_for_imports(self):
        headers = "Export Table:\n 1 0a000 malloc\n 2 0a010 free\n"
        self.assertEqual([], symbols_from_pe(headers))

    def test_actual_foreign_heap_library_is_rejected(self):
        compiler = shutil.which("gcc" if os.name == "nt" else "cc")
        self.assertIsNotNone(compiler, "A native compiler is required for the negative control")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "foreign.c"
            source.write_text("#include <stdlib.h>\n"
                              "#if defined(_WIN32)\n__declspec(dllexport)\n#endif\n"
                              "void* foreign_heap(size_t size) { return malloc(size); }\n")
            suffix = ".dll" if os.name == "nt" else ".dylib" if sys.platform == "darwin" else ".so"
            binary = root / ("foreign" + suffix)
            subprocess.run([compiler, "-dynamiclib" if sys.platform == "darwin" else "-shared",
                            "-O0", str(source), "-o", str(binary)], check=True)
            try:
                result = inspect(binary, mode="normal")
            except ValueError as failure:
                self.assertRegex(str(failure), "External heap entry points:.*malloc")
            else:
                self.fail("Foreign heap library was accepted: " + str(result))


if __name__ == "__main__":
    unittest.main()
