#!/usr/bin/env python3
"""Validate the thin Mach-O dependency graph before packaging a Mac classifier.

Static archive validation only: this does not execute or certify native code.
"""
import argparse
import json
from pathlib import Path
import struct
import zipfile

CPUS = {'arm64': 0x0100000C, 'x86_64': 0x01000007}
MAIN = 'libdatachannel-java.dylib'
ALLOCATOR = 'libdatachannel_mimalloc.dylib'
MAX_IMAGE = 32 * 1024 * 1024


def inspect(data, architecture):
    if not 32 <= len(data) <= MAX_IMAGE:
        raise ValueError('Mach-O image size')
    magic, cpu, subtype, kind, count, command_bytes, flags, reserved = struct.unpack_from('<8I', data)
    if magic != 0xFEEDFACF or cpu != CPUS[architecture] or kind != 6:
        raise ValueError('Expected thin64-bit MH_DYLIB for ' + architecture)
    if count > 4096 or command_bytes > len(data) - 32:
        raise ValueError('Mach-O command bounds')
    end = 32 + command_bytes
    offset = 32
    dependencies, paths, identifiers = [], [], []
    for unused in range(count):
        if offset + 8 > end:
            raise ValueError('Truncated Mach-O command')
        command, size = struct.unpack_from('<2I', data, offset)
        if size < 8 or size % 8 or size > end - offset:
            raise ValueError('Invalid Mach-O command size')
        if command in (0xD, 0xC, 0x80000018, 0x8000001F, 0x80000023, 0x8000001C):
            minimum = 12 if command == 0x8000001C else 24
            if size < minimum:
                raise ValueError('Truncated dylib/path command')
            start = struct.unpack_from('<I', data, offset + 8)[0]
            if not minimum <= start < size:
                raise ValueError('Mach-O string offset')
            raw = data[offset + start:offset + size]
            terminal = raw.find(b'\0')
            if terminal < 0:
                raise ValueError('Unterminated Mach-O string')
            value = raw[:terminal].decode('utf-8', errors='strict')
            if not value or '\n' in value or '\r' in value:
                raise ValueError('Invalid Mach-O dependency text')
            if command == 0xD:
                identifiers.append(value)
            elif command == 0x8000001C:
                paths.append(value)
            else:
                dependencies.append(value)
        offset += size
    if offset != end or len(identifiers) != 1:
        raise ValueError('Mach-O command size or install ID mismatch')
    return {'id': identifiers[0], 'dependencies': dependencies, 'rpaths': paths}


def images(path, architecture):
    if path.is_dir():
        result = {}
        for item in path.iterdir():
            if item.name.endswith('.dylib'):
                if item.is_symlink() or not item.is_file() or item.stat().st_size > MAX_IMAGE:
                    raise ValueError('Invalid staged dylib: ' + item.name)
                result[item.name] = item.read_bytes()
        return result
    with zipfile.ZipFile(path) as archive:
        result = {}
        prefix = 'macos-' + architecture + '/native/'
        if not any(item.filename.startswith(prefix) for item in archive.infolist()):
            prefix = 'native/'
        for item in archive.infolist():
            if item.filename.startswith(prefix) and item.filename.endswith('.dylib'):
                name = item.filename.removeprefix(prefix)
                if '/' in name or name in result or not 32 <= item.file_size <= MAX_IMAGE:
                    raise ValueError('Invalid or duplicate classifier native resource')
                result[name] = archive.read(item)
        return result


def verify(path, architecture):
    binaries = images(Path(path), architecture)
    for required in (MAIN, ALLOCATOR):
        if required not in binaries:
            raise ValueError('Missing required Mac native dependency: ' + required)
    decoded = {name: inspect(data, architecture) for name, data in binaries.items()}
    for name, metadata in decoded.items():
        if metadata['id'] != '@rpath/' + name:
            raise ValueError('Noncanonical Mac install name: ' + name + ' -> ' + metadata['id'])
        for dependency in metadata['dependencies']:
            if dependency.startswith(('/usr/lib/', '/System/Library/Frameworks/')):
                continue
            if not dependency.startswith(('@rpath/', '@loader_path/')):
                raise ValueError('Nonportable Mac dependency: ' + dependency)
            sibling = dependency.split('/', 1)[1]
            if '/' in sibling or sibling not in decoded:
                raise ValueError('Unclosed Mac native dependency: ' + dependency)
            if dependency.startswith('@rpath/') and '@loader_path' not in metadata['rpaths']:
                raise ValueError('Bundled @rpath dependency lacks @loader_path: ' + name)
    if '@rpath/' + ALLOCATOR not in decoded[MAIN]['dependencies']:
        raise ValueError('JNI must bind the canonical private allocator')
    return {'architecture': architecture, 'nativeLibraries': sorted(decoded), 'staticDependencyClosure': True,
            'nativeExecution': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('architecture', choices=CPUS)
    parser.add_argument('path', type=Path, help='Staged native directory or final classifier JAR')
    arguments = parser.parse_args()
    try:
        print(json.dumps(verify(arguments.path, arguments.architecture)))
    except (OSError, ValueError, zipfile.BadZipFile, struct.error, UnicodeError) as problem:
        parser.exit(1, str(problem) + '\n')


if __name__ == '__main__':
    main()
