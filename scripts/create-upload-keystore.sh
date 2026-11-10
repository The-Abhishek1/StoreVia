#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

python3 - <<'PY'
from pathlib import Path
import os
import shutil
import subprocess
import sys

properties_file = Path("keystore.properties")
if not properties_file.is_file():
    print("Create keystore.properties from keystore.properties.example first.", file=sys.stderr)
    raise SystemExit(1)

properties = {}
for line in properties_file.read_text().splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    properties[key.strip()] = value.strip()

required = ("storeFile", "storePassword", "keyAlias", "keyPassword")
missing = [key for key in required if not properties.get(key)]
if missing:
    print("Fill in all signing values in keystore.properties first.", file=sys.stderr)
    raise SystemExit(1)
if shutil.which("keytool") is None:
    print("keytool was not found. Install a JDK and try again.", file=sys.stderr)
    raise SystemExit(1)

keystore_path = Path(properties["storeFile"])
if not keystore_path.is_absolute():
    keystore_path = Path.cwd() / keystore_path
if keystore_path.exists():
    print(f"Refusing to overwrite existing keystore: {keystore_path.name}", file=sys.stderr)
    raise SystemExit(1)

env = os.environ.copy()
env["STOREVIA_KEYSTORE_PASSWORD"] = properties["storePassword"]
env["STOREVIA_KEY_PASSWORD"] = properties["keyPassword"]
command = [
    "keytool", "-genkeypair", "-v",
    "-keystore", str(keystore_path),
    "-alias", properties["keyAlias"],
    "-keyalg", "RSA",
    "-keysize", "2048",
    "-validity", "10000",
    "-dname", "CN=Storevia, O=Storevia, C=IN",
    "-storepass:env", "STOREVIA_KEYSTORE_PASSWORD",
    "-keypass:env", "STOREVIA_KEY_PASSWORD",
]
result = subprocess.run(command, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
if result.returncode:
    print("keytool could not create the upload key. Check your JDK and signing properties.", file=sys.stderr)
    raise SystemExit(result.returncode)
print(f"Created upload keystore: {keystore_path.name}")
PY
