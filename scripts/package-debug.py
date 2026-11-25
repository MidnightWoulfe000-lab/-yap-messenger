"""Package an already verified APK and corresponding source, including Cargo vendor.

Run from the repository root. Android SDK, JDK, Gradle and Rust toolchains remain
external build prerequisites; dependency versions and build scripts are included.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import zipfile
import shutil
import struct

parser = argparse.ArgumentParser()
parser.add_argument("--vendor-root", type=pathlib.Path, required=True)
parser.add_argument("--output", type=pathlib.Path, required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
apk = output / "yap-0.2.0-debug.apk"
shutil.copy2(root / "android/app/build/outputs/apk/debug/app-debug.apk", apk)
with zipfile.ZipFile(apk) as binary, apk.open("rb") as raw:
    for abi in ("arm64-v8a", "x86_64"):
        entry = binary.getinfo(f"lib/{abi}/libyap_android.so")
        assert entry.compress_type == zipfile.ZIP_STORED
        raw.seek(entry.header_offset + 26)
        name_length, extra_length = struct.unpack("<HH", raw.read(4))
        assert (entry.header_offset + 30 + name_length + extra_length) % 16384 == 0, "Native APK entries must align to 16 KiB"
archive = output / "yap-0.2.0-debug-source.zip"
files = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root).decode().split("\0")
files = sorted(set(name for name in files if name and (root / name).is_file()))
manifest = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in files}
prefix = "yap-0.2.0-debug-source/"
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=6, strict_timestamps=False) as bundle:
    for name in files:
        if name == "android/gradlew" or name.endswith(".sh"):
            info = zipfile.ZipInfo(prefix + name)
            info.create_system = 3
            info.external_attr = 0o100755 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(info, (root / name).read_bytes())
        else:
            bundle.write(root / name, prefix + name)
    for source in sorted((args.vendor_root / "vendor").rglob("*")):
        if source.is_file():
            bundle.write(source, prefix + source.relative_to(args.vendor_root).as_posix())
    config = (args.vendor_root / ".cargo/config.toml").read_text(encoding="utf-8-sig")
    config = config.replace(str((args.vendor_root / "vendor").resolve()).replace("\\", "/"), "vendor")
    bundle.writestr(prefix + ".cargo/config.toml", config)
    bundle.write(root / "experiments/signal-mesh/LICENSE", prefix + "LICENSE-AGPL-3.0")
    bundle.writestr(prefix + "source-sha256.json", json.dumps(manifest, indent=2))
    bundle.writestr(prefix + "SOURCE-BUILD.txt", "Build instructions: android/README.md. Vendored Rust sources and .cargo/config.toml permit locked Cargo dependency resolution without GitHub/crates.io. Install the documented Rust/JDK/Android SDK/NDK/protoc tools separately. Gradle obtains pinned Android dependencies from their repositories. The combined native endpoint uses AGPL-3.0-only; retain all dependency copyright and licence notices. Public localhost TLS fixtures are test-only. No production secrets or debug signing private key are included.\n")
with zipfile.ZipFile(archive) as bundle:
    assert bundle.testzip() is None
    assert prefix + "endpoint/src/engine.rs" in bundle.namelist()
    assert prefix + "vendor/libsignal-protocol-0.1.0/src/lib.rs" in bundle.namelist()
hashes = []
for artifact in (apk, archive):
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    hashes.append(f"{digest}  {artifact.name}")
    print(f"{artifact.name}: {artifact.stat().st_size:,} bytes; SHA-256 {digest}")
(output / "SHA256SUMS.txt").write_text("\n".join(hashes) + "\n", encoding="utf-8")
