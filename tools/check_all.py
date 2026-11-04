import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def run(command, cwd=ROOT, optional=False):
    print("$ " + " ".join(command), flush=True)
    result = subprocess.run(command, cwd=cwd)
    if result.returncode != 0:
        if optional:
            print(f"AVISO: fallo opcional (codigo {result.returncode})", flush=True)
        else:
            raise SystemExit(result.returncode)
    return result.returncode


def main():
    run([sys.executable, "tools/protocol_v1.py", "--selftest"])
    run([sys.executable, "tools/check_doc.py"])
    run([sys.executable, "tools/contract_v1.py", "--test"])

    if shutil.which("cargo"):
        run(["cargo", "fmt", "--all", "--", "--check"], ROOT / "daemon", optional=True)
        run(["cargo", "clippy", "--all-targets", "--", "-D", "warnings"], ROOT / "daemon")
        run(["cargo", "test", "--all-targets"], ROOT / "daemon")
    else:
        print("SKIP rust: cargo no encontrado", flush=True)

    gradle = shutil.which("gradle")
    if gradle:
        run([gradle, "--no-daemon", "test"], ROOT / "android" / "contracts")
        run([gradle, "--no-daemon", "test"], ROOT / "android" / "ui-core")
        run([gradle, "--no-daemon", "test"], ROOT / "android" / "session")
        if os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"):
            run(
                [gradle, "--no-daemon", "testDebugUnitTest"],
                ROOT / "android" / "transport",
            )
            run([gradle, "--no-daemon", "assembleDebug"], ROOT / "android" / "app")
        else:
            print("SKIP android/transport: Android SDK no encontrado", flush=True)
            print("SKIP android/app: Android SDK no encontrado", flush=True)
    else:
        print("SKIP android: gradle no encontrado", flush=True)

    print("OK: todas las comprobaciones disponibles pasaron", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
