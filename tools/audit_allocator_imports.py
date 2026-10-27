#!/usr/bin/env python3
"""Reject external desktop heap entry points and verify CI instrumentation isolation."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys


HEAP_APIS = {
    "malloc", "calloc", "realloc", "reallocarray", "free", "cfree", "strdup", "strndup",
    "wcsdup", "_strdup", "_wcsdup", "aligned_alloc", "posix_memalign", "memalign", "valloc", "pvalloc",
    "_aligned_malloc", "_aligned_realloc", "_aligned_recalloc", "_aligned_free", "_recalloc", "_expand",
    "asprintf", "vasprintf", "asprintf_l", "vasprintf_l", "__libc_malloc", "__libc_calloc",
    "__libc_realloc", "__libc_free", "__cxa_allocate_exception", "__cxa_free_exception",
    "__cxa_allocate_dependent_exception", "__cxa_free_dependent_exception",
    "malloc_size", "malloc_usable_size", "_msize", "_aligned_msize", "_aligned_offset_malloc",
    "_aligned_offset_realloc", "_aligned_offset_recalloc", "_malloc_base", "_calloc_base",
    "_realloc_base", "_free_base",
}


def symbols_from_nm(output, macho=False):
    symbols = []
    for line in output.splitlines():
        fields = line.split()
        if not fields:
            continue
        symbol = fields[-1].split("@", 1)[0]
        if macho and symbol.startswith("_"):
            symbol = symbol[1:]
        symbols.append(symbol)
    return symbols


def symbols_from_pe(output):
    symbols = []
    in_dll = False
    for line in output.splitlines():
        if re.search(r"(?:The Export Tables|Export Table:|\[Ordinal/Name Pointer\] Table)", line):
            break
        if "DLL Name:" in line:
            in_dll = True
            continue
        if not in_dll:
            continue
        # Recent GNU adds an ordinal column before the hint. Older GNU has
        # address/hint/name, while LLVM has hint/name without an address.
        name = r"([A-Za-z_?][^\s]*)"
        match = re.match(r"^\s+[0-9a-fA-F]+\s+(?:[0-9a-fA-F]+|<none>)\s+[0-9a-fA-F]+\s+" + name, line)
        if not match:
            match = re.match(r"^\s+[0-9a-fA-F]+\s+[0-9a-fA-F]+\s+" + name, line)
        if not match:
            match = re.match(r"^\s+[0-9]+\s+" + name + r"\s*$", line)
        if match:
            symbols.append(match.group(1))
    return symbols


def forbidden(symbols):
    return sorted({symbol for symbol in symbols
                   if symbol in HEAP_APIS or symbol.startswith(("_Zn", "_Zd", "malloc_zone_"))})


def inspect(binary, nm="nm", objdump="objdump", mode="diagnostics"):
    def run(*command):
        return subprocess.run(command, check=True, text=True, capture_output=True).stdout

    if binary.suffix.lower() == ".dll":
        exports = run(objdump, "-p", str(binary))
        imports = symbols_from_pe(exports)
    else:
        macho = binary.suffix == ".dylib"
        imports = symbols_from_nm(run(nm, "-u", str(binary)), macho)
        exports = run(nm, "-gU" if macho else "-D", str(binary))
    if not imports:
        raise ValueError("No imports decoded; refusing an unverified native image")
    escaped = forbidden(imports)
    if escaped:
        raise ValueError("External heap entry points: " + ", ".join(escaped))
    diagnostic = "Java_tel_schich_libdatachannel_NativeAllocatorDiagnosticsTest_" in exports
    if diagnostic != (mode == "diagnostics"):
        raise ValueError("Unexpected allocator diagnostic instrumentation for mode " + mode)
    return {"binary": binary.name, "mode": mode, "externalHeapImports": escaped,
            "imports": sorted(set(imports)), "diagnosticEntryPoints": diagnostic}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    parser.add_argument("--nm", default="nm")
    parser.add_argument("--objdump", default="objdump")
    parser.add_argument("--mode", choices=("diagnostics", "normal"), default="diagnostics")
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    try:
        result = inspect(arguments.binary, arguments.nm, arguments.objdump, arguments.mode)
        encoded = json.dumps(result, indent=2) + "\n"
        if arguments.output:
            arguments.output.parent.mkdir(parents=True, exist_ok=True)
            arguments.output.write_text(encoded, encoding="utf-8")
        print(encoded, end="")
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        print(str(failure), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
