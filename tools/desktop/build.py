#!/usr/bin/env python3
"""Build a private Apple Silicon distribution. Never loads the developer's .env."""

import argparse
import hashlib
import json
import os
import plistlib
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERSION = "0.1.5"


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def output(*args):
    return subprocess.check_output([str(a) for a in args], text=True).strip()


def macho(path):
    return path.is_file() and path.open("rb").read(4) in (
        b"\xcf\xfa\xed\xfe",
        b"\xca\xfe\xba\xbe",
        b"\xca\xfe\xba\xbf",
    )


def dependencies(path):
    return [
        line.strip().split(" (")[0]
        for line in output("/usr/bin/otool", "-L", path).splitlines()[1:]
    ]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--google-client", required=True, type=Path)
    p.add_argument(
        "--jdk",
        type=Path,
        default=Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"),
    )
    p.add_argument(
        "--postgres", type=Path, default=Path("/opt/homebrew/opt/postgresql@16")
    )
    p.add_argument(
        "--skip-maven", action="store_true", help="Use the already built JAR"
    )
    p.add_argument("--app-only", action="store_true")
    a = p.parse_args()
    if output("/usr/bin/uname", "-m") != "arm64":
        p.error("Build on an Apple Silicon Mac.")
    # Validate without printing private client configuration.
    client = json.loads(a.google_client.read_text()).get("installed", {})
    if not all(
        isinstance(client.get(k), str) and client[k]
        for k in ("client_id", "client_secret")
    ):
        p.error("Expected the installed Google Desktop client JSON, not a web client.")
    if not a.skip_maven:
        env = dict(os.environ, JAVA_HOME=str(a.jdk))
        run("mvn", "-q", "-DskipTests", "package", cwd=ROOT, env=env)
    jar = ROOT / "target/tidymail-0.1.0.jar"
    build_id = hashlib.sha256(jar.read_bytes()).hexdigest()
    destination = ROOT / "desktop-dist"
    destination.mkdir(mode=0o700, exist_ok=True)
    stage = destination / "stage"
    if stage.exists():
        shutil.rmtree(stage)
    contents = stage / "TidyMail.app/Contents"
    resources = contents / "Resources"
    executable = contents / "MacOS"
    executable.mkdir(parents=True)
    resources.mkdir()
    shutil.copy2(jar, resources / "tidymail.jar")
    (resources / "google-desktop.json").write_text(
        json.dumps(
            {
                "installed": {
                    "client_id": client["client_id"],
                    "client_secret": client["client_secret"],
                }
            }
        )
    )
    (resources / "build-id").write_text(build_id)
    # The Java runtime includes its legal directory; no Java installation is needed.
    run(
        a.jdk / "bin/jlink",
        "--add-modules",
        "java.se,jdk.crypto.ec,jdk.unsupported,jdk.management,jdk.naming.dns",
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--output",
        resources / "runtime",
    )
    pg = resources / "postgres"
    config = a.postgres / "bin/pg_config"
    source_paths = {
        key: Path(output(config, option))
        for key, option in (
            ("bin", "--bindir"),
            ("share", "--sharedir"),
            ("lib", "--pkglibdir"),
        )
    }
    common_prefix = Path(
        os.path.commonpath([str(path) for path in source_paths.values()])
    )
    if common_prefix == Path("/"):
        raise RuntimeError(
            "PostgreSQL configured paths have no relocatable common prefix."
        )
    layout = {
        key: str(path.relative_to(common_prefix)) for key, path in source_paths.items()
    }
    # Homebrew compiles BINDIR under Cellar but SHAREDIR/PKGLIBDIR under opt.
    # PostgreSQL relocation compares those relative suffixes. Preserve that tree
    # under our private bundle root, rather than falling back to installed files.
    (resources / "postgres-layout.json").write_text(json.dumps(layout))
    (pg / layout["bin"]).mkdir(parents=True)
    for name in (
        "postgres",
        "initdb",
        "pg_ctl",
        "createdb",
        "psql",
        "pg_dump",
        "pg_restore",
        "pg_isready",
    ):
        shutil.copy2(source_paths["bin"] / name, pg / layout["bin"] / name)
    shutil.copytree(source_paths["share"], pg / layout["share"])
    (pg / layout["lib"]).mkdir(parents=True)
    for lib in source_paths["lib"].glob("*.dylib"):
        if lib.name.startswith(("plperl", "plpython", "llvmjit")):
            continue
        shutil.copy2(lib, pg / layout["lib"] / lib.name)
    vendor = resources / "vendor-libs"
    vendor.mkdir()
    queue = [f for f in resources.rglob("*") if macho(f)]
    origins = {}
    seen = set()
    used_formulae = {a.postgres.resolve()}
    while queue:
        binary = queue.pop()
        if binary in seen:
            continue
        seen.add(binary)
        run("/bin/chmod", "u+w", binary)
        for dep in dependencies(binary):
            if dep.startswith(("/usr/lib/", "/System/Library/")):
                continue
            if dep.startswith("@loader_path/"):
                suffix = dep.removeprefix("@loader_path/")
                if (binary.parent / suffix).exists():
                    continue
                original = origins.get(binary, binary).parent / suffix
            elif dep.startswith("@"):
                continue
            else:
                original = Path(dep)
            if not original.exists():
                raise RuntimeError("Missing dynamic library: " + dep)
            target = vendor / original.name
            if (
                target.exists()
                and hashlib.sha256(target.read_bytes()).digest()
                != hashlib.sha256(original.read_bytes()).digest()
            ):
                # Existing copies may already have their load commands rewritten.
                if original.resolve() not in copied:
                    raise RuntimeError(
                        "Conflicting dynamic library names: " + original.name
                    )
            if not target.exists():
                shutil.copy2(original, target)
                copied.add(original.resolve())
                origins[target] = original.resolve()
                queue.append(target)
                resolved = original.resolve()
                if "/Cellar/" in str(resolved):
                    idx = resolved.parts.index("Cellar")
                    used_formulae.add(Path(*resolved.parts[: idx + 3]))
            relative = os.path.relpath(target, binary.parent)
            run(
                "/usr/bin/install_name_tool",
                "-change",
                dep,
                "@loader_path/" + relative,
                binary,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
        # Remove Homebrew search paths after all external references are relocated.
        commands = output("/usr/bin/otool", "-l", binary)
        for search_path in re.findall(
            r"cmd LC_RPATH\n\s*cmdsize \d+\n\s*path (.*?) \(offset", commands
        ):
            if search_path.startswith(("/opt/", "/Users/", "/usr/local/")):
                run(
                    "/usr/bin/install_name_tool",
                    "-delete_rpath",
                    search_path,
                    binary,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.PIPE,
                )
        if binary.suffix == ".dylib":
            run(
                "/usr/bin/install_name_tool",
                "-id",
                "@loader_path/" + binary.name,
                binary,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
    # Ensure no Homebrew/external library is needed and every Mach-O supports macOS 15.
    for binary in seen:
        arches = output("/usr/bin/lipo", "-archs", binary).split()
        if "arm64" not in arches:
            raise RuntimeError(
                "Non-Apple-Silicon component: " + str(binary.relative_to(resources))
            )
        for dep in dependencies(binary):
            if (
                dep.startswith("@loader_path/")
                and not (binary.parent / dep.removeprefix("@loader_path/")).exists()
            ):
                raise RuntimeError("Unresolved bundled library: " + dep)
            if not dep.startswith(("/usr/lib/", "/System/Library/", "@")):
                raise RuntimeError("Unbundled dependency: " + dep)
        load_commands = output("/usr/bin/otool", "-l", "-arch", "arm64", binary)
        versions = re.findall(
            r"^\s*minos\s+(\d+)\.(\d+)", load_commands, flags=re.MULTILINE
        )
        versions += re.findall(
            r"cmd LC_VERSION_MIN_MACOSX\n\s*cmdsize \d+\n\s*version (\d+)\.(\d+)",
            load_commands,
        )
        if any((int(major), int(minor)) > (15, 0) for major, minor in versions):
            raise RuntimeError(
                "Component requires macOS newer than 15: "
                + str(binary.relative_to(resources))
            )
        run(
            "/usr/bin/codesign",
            "--force",
            "--sign",
            "-",
            binary,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
    legal = resources / "ThirdPartyNotices"
    legal.mkdir()
    for formula in sorted(used_formulae):
        dest = legal / (formula.parent.name + "-" + formula.name)
        dest.mkdir()
        for pattern in (
            "COPYING*",
            "LICENSE*",
            "COPYRIGHT*",
            "NOTICE*",
            "*GPL*",
            "license*",
        ):
            for source in formula.glob(pattern):
                if source.is_file():
                    shutil.copy2(source, dest / source.name)
        for source in (formula / ".brew").glob("*.rb"):
            shutil.copy2(source, dest / source.name)
        receipt = formula / "INSTALL_RECEIPT.json"
        if receipt.exists():
            # Formula source identity, build options, and upstream license metadata.
            shutil.copy2(receipt, dest / receipt.name)
    shutil.copy2(ROOT / "LICENSE", resources / "LICENSE")
    # Ship corresponding TidyMail source, including the local packaging changes.
    files = set(output("git", "-C", ROOT, "ls-files").splitlines())
    files.update(
        str(f.relative_to(ROOT))
        for f in (ROOT / "tools/desktop").rglob("*")
        if f.is_file() and "__pycache__" not in str(f)
    )
    files.update(
        [
            "MAC-DISTRIBUTION.md",
            "src/main/java/com/tidymail/security/DesktopLoopbackFilter.java",
            "src/main/java/com/tidymail/mail/gmail/GmailSyncReadException.java",
            "src/main/java/com/tidymail/mail/gmail/GmailSyncReadProgress.java",
            "docs/GMAIL-SYNC-RELIABILITY.md",
        ]
    )
    with zipfile.ZipFile(
        resources / "TidyMail-source.zip", "w", zipfile.ZIP_DEFLATED
    ) as z:
        for name in sorted(files):
            f = ROOT / name
            if f.is_file() and not name.startswith(
                (".env", "config/", ".local-", "target/")
            ):
                z.write(f, "TidyMail/" + name)
    run(
        "/usr/bin/xcrun",
        "swiftc",
        "-O",
        "-target",
        "arm64-apple-macos15.0",
        "-module-cache-path",
        destination / "swift-cache",
        ROOT / "tools/desktop/Launcher.swift",
        ROOT / "tools/desktop/UninstallSupport.swift",
        "-o",
        executable / "TidyMail",
        "-framework",
        "AppKit",
        "-framework",
        "Security",
        "-framework",
        "CryptoKit",
    )
    helpers = contents / "Helpers"
    helpers.mkdir()
    run(
        "/usr/bin/xcrun",
        "swiftc",
        "-O",
        "-target",
        "arm64-apple-macos15.0",
        "-module-cache-path",
        destination / "swift-cache",
        ROOT / "tools/desktop/UninstallHelper.swift",
        ROOT / "tools/desktop/UninstallSupport.swift",
        "-o",
        helpers / "TidyMail Uninstaller",
        "-framework",
        "AppKit",
        "-framework",
        "Security",
        "-framework",
        "CryptoKit",
    )
    run(
        "/usr/bin/codesign",
        "--force",
        "--sign",
        "-",
        helpers / "TidyMail Uninstaller",
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    info = {
        "CFBundleIdentifier": "work.tidymail.desktop",
        "CFBundleName": "TidyMail",
        "CFBundleDisplayName": "TidyMail",
        "CFBundleExecutable": "TidyMail",
        "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": VERSION,
        "CFBundleVersion": VERSION,
        "TidyMailBuild": build_id,
        "LSMinimumSystemVersion": "15.0",
        "NSHighResolutionCapable": True,
        "LSMultipleInstancesProhibited": True,
    }
    (contents / "Info.plist").write_bytes(plistlib.dumps(info))
    run(
        "/usr/bin/codesign",
        "--force",
        "--sign",
        "-",
        stage / "TidyMail.app",
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    shutil.copy2(ROOT / "tools/desktop/INSTALL.txt", stage / "READ ME FIRST.txt")
    (stage / "Applications").symlink_to("/Applications")
    if not a.app_only:
        run(
            "/bin/sh", ROOT / "tools/desktop/verify-isolated.sh", stage / "TidyMail.app"
        )
        dmg = destination / f"TidyMail-AppleSilicon-{VERSION}.dmg"
        if dmg.exists():
            dmg.unlink()
        run(
            "/usr/bin/hdiutil",
            "create",
            "-volname",
            "TidyMail",
            "-srcfolder",
            stage,
            "-ov",
            "-format",
            "UDZO",
            dmg,
        )
        print("Installer:", dmg)
    print("Application:", stage / "TidyMail.app")
    print("Build:", build_id[:12], "(ad-hoc signed, not Apple notarized)")


copied = set()
if __name__ == "__main__":
    main()
