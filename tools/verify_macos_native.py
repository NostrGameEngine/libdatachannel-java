#!/usr/bin/env python3
"""Validate the thin Mach-O dependency graph before packaging a Mac classifier.

Static binary inspection only: this does not execute or certify native code.
"""
import argparse
import json
from pathlib import Path
import struct
import zipfile

CPUS = {'arm64': 0x0100000C, 'x86_64': 0x01000007}
MAIN = 'libdatachannel-java.dylib'
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
    symbol_table = None
    for unused in range(count):
        if offset + 8 > end:
            raise ValueError('Truncated Mach-O command')
        command, size = struct.unpack_from('<2I', data, offset)
        if size < 8 or size % 8 or size > end - offset:
            raise ValueError('Invalid Mach-O command size')
        if command == 0x2:
            if size != 24 or symbol_table is not None:
                raise ValueError('Invalid Mach-O symbol table command')
            symbol_table = struct.unpack_from('<4I', data, offset + 8)
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
    exports = []
    if symbol_table is not None:
        symbols, count, strings, string_bytes = symbol_table
        if symbols < end or count > (len(data) - symbols) // 16 or strings < end or string_bytes > len(data) - strings:
            raise ValueError('Mach-O symbol table bounds')
        for index in range(count):
            name, kind, section, description, value = struct.unpack_from('<IBBHQ', data, symbols + index * 16)
            if kind & 0xE0 or not kind & 1 or kind & 0x10 or kind & 0x0E == 0:
                continue  # Debug, undefined and private-external symbols are not public exports.
            if name >= string_bytes:
                raise ValueError('Mach-O symbol name bounds')
            finish = data.find(b'\0', strings + name, strings + string_bytes)
            if finish < 0:
                raise ValueError('Unterminated Mach-O symbol name')
            exports.append(data[strings + name:finish].decode('utf-8', errors='strict'))
    return {'id': identifiers[0], 'dependencies': dependencies, 'rpaths': paths, 'exports': exports}


def images(path, architecture):
    if path.is_dir():
        result = {}
        for item in path.iterdir():
            if item.name == MAIN:
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


def prebuilt_images(jni_path, selected_files):
    """Apply the packaging JNI alias to exactly the files selected by Gradle."""
    if jni_path.is_symlink() or not jni_path.is_file():
        raise ValueError('Invalid selected prebuilt JNI')
    jni_source = jni_path.resolve()
    result = {}
    selected_jni = False
    for item in map(Path, selected_files):
        is_jni = item.resolve() == jni_source
        name = MAIN if is_jni else item.name
        if not is_jni and not name.endswith('.dylib'):
            continue
        if item.is_symlink() or not item.is_file() or item.stat().st_size > MAX_IMAGE:
            raise ValueError('Invalid selected prebuilt library: ' + item.name)
        if name in result:
            raise ValueError('Duplicate prebuilt native resource: ' + name)
        result[name] = item.read_bytes()
        selected_jni |= is_jni
    if not selected_jni:
        raise ValueError('Selected prebuilt JNI is not packaged')
    return result


def verify(path, architecture, selected_files=None):
    binaries = (images(Path(path), architecture) if selected_files is None else
                prebuilt_images(Path(path), selected_files))
    if MAIN not in binaries:
        raise ValueError('Missing required Mac JNI library')
    if set(binaries) != {MAIN}:
        raise ValueError('Unexpected shared native dependency in Mac package')
    decoded = {name: inspect(data, architecture) for name, data in binaries.items()}
    exports = decoded[MAIN]['exports']
    if not exports:
        raise ValueError('Missing public JNI symbol metadata')
    for symbol in exports:
        if not (symbol.startswith('_Java_') or symbol in ('_JNI_OnLoad', '_JNI_OnUnload', '_get_jni_env_from_jvm')):
            raise ValueError('Non-JNI public export: ' + symbol)
    for name, metadata in decoded.items():
        if metadata['id'] != '@rpath/' + name:
            raise ValueError('Noncanonical Mac install name: ' + name + ' -> ' + metadata['id'])
        for dependency in metadata['dependencies']:
            if 'libc++' in dependency.rsplit('/', 1)[-1]:
                raise ValueError('Desktop JNI must embed its C++ runtime: ' + dependency)
            if any(name in dependency.rsplit('/', 1)[-1].lower() for name in ('libssl', 'libcrypto', 'mimalloc')):
                raise ValueError('OpenSSL and mimalloc must be private: ' + dependency)
            if dependency.startswith(('/usr/lib/', '/System/Library/Frameworks/')):
                continue
            if not dependency.startswith(('@rpath/', '@loader_path/')):
                raise ValueError('Nonportable Mac dependency: ' + dependency)
            sibling = dependency.split('/', 1)[1]
            if '/' in sibling or sibling not in decoded:
                raise ValueError('Unclosed Mac native dependency: ' + dependency)
            if dependency.startswith('@rpath/') and '@loader_path' not in metadata['rpaths']:
                raise ValueError('Bundled @rpath dependency lacks @loader_path: ' + name)
    return {'architecture': architecture, 'nativeLibraries': sorted(decoded), 'staticDependencyClosure': True, 'privateCrypto': True, 'privateAllocator': True,
            'nativeExecution': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('architecture', choices=CPUS)
    parser.add_argument('path', type=Path, help='Staged native directory, final classifier JAR or selected prebuilt JNI')
    parser.add_argument('--prebuilt', nargs='*', type=Path, metavar='FILE',
                        help='Exact packaging file selection; alias the selected JNI to its canonical resource name')
    arguments = parser.parse_args()
    try:
        print(json.dumps(verify(arguments.path, arguments.architecture, arguments.prebuilt)))
    except (OSError, ValueError, zipfile.BadZipFile, struct.error, UnicodeError) as problem:
        parser.exit(1, str(problem) + '\n')


if __name__ == '__main__':
    main()
