#!/usr/bin/env python3
"""Package the binding and its initialized native submodules with exact file hashes."""

import argparse
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path


def git(directory, *arguments):
    return subprocess.check_output(["git", "-C", str(directory), *arguments])


def repositories(root, relative=Path(".")):
    directory = root / relative
    actual = Path(git(directory, "rev-parse", "--show-toplevel").decode().strip()).resolve()
    if actual != directory.resolve():
        raise ValueError("Native submodule is not initialized: " + str(relative))
    yield relative, directory
    modules = directory / ".gitmodules"
    if modules.exists():
        result = subprocess.run(["git", "config", "--file", str(modules), "--get-regexp",
                                 r"submodule\..*\.path"], capture_output=True, text=True, check=False)
        if result.returncode not in (0, 1):
            raise ValueError("Cannot read submodule paths in " + str(modules))
        for line in result.stdout.splitlines():
            yield from repositories(root, relative / line.split(None, 1)[1])


def package(root, output, require_clean=False):
    root = root.resolve()
    contents = {}
    revisions = {}
    for relative, directory in repositories(root):
        status = git(directory, "status", "--porcelain=v1", "--untracked-files=normal").decode()
        if require_clean and status:
            raise ValueError("Release source must be clean and committed: " + str(relative))
        revisions[relative.as_posix()] = {
            "commit": git(directory, "rev-parse", "HEAD").decode().strip(),
            "modified": bool(status),
        }
        names = set(git(directory, "ls-files", "--cached", "-z").decode().split("\0"))
        if relative == Path("."):
            # Include new binding sources without collecting unrelated local files.
            source_roots = ("src/", "jni/src/", "jni/tests/", "jni/cmake/", "tools/", "licenses/",
                            "conventions/src/", "arch-detect/src/", "android/src/", "ios/src/", "example/")
            source_files = {"SOURCE-BUILD.txt", "SOURCE-NOTICES.txt", "build.gradle.kts",
                            "settings.gradle.kts", "gradle.properties"}
            others = git(directory, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0")
            names.update(name for name in others if name.startswith(source_roots) or name in source_files)
        for name in names:
            if not name:
                continue
            source = directory / name
            if source.is_dir():
                continue
            if not source.resolve().is_relative_to(root):
                raise ValueError("Source link escapes the checkout: " + str(source))
            if not source.is_file():
                # Tracked deletions are represented by absence in the working source.
                continue
            contents[(relative / name).as_posix()] = source
    generated = root / "jni/generated"
    if generated.is_dir():
        for source in generated.rglob("*"):
            if source.is_file():
                contents[source.relative_to(root).as_posix()] = source
    manifest = {"repositories": revisions, "sha256": {}}
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, source in sorted(contents.items()):
                data = source.read_bytes()
                manifest["sha256"][name] = hashlib.sha256(data).hexdigest()
                entry = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = (0o100755 if source.stat().st_mode & 0o111 else 0o100644) << 16
                archive.writestr(entry, data)
            archive.writestr(zipfile.ZipInfo("SOURCE-REVISIONS.json", (2020, 1, 1, 0, 0, 0)),
                             json.dumps(manifest, sort_keys=True, indent=2) + "\n")
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return manifest


def verify_clean(root):
    for relative, directory in repositories(root.resolve()):
        if git(directory, "status", "--porcelain=v1", "--untracked-files=normal").strip():
            raise ValueError("Release source must be clean and committed: " + str(relative))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-clean", action="store_true")
    parser.add_argument("--check-clean", action="store_true")
    args = parser.parse_args()
    if args.check_clean:
        verify_clean(args.root)
        print("Release sources are clean and initialized")
    else:
        if args.output is None:
            parser.error("--output is required when creating an archive")
        result = package(args.root, args.output, args.require_clean)
        print("Packaged", len(result["sha256"]), "source files from", len(result["repositories"]), "repositories")
