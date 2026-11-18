#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds the signed release AAB (#88) with the upload key from HashiCorp Vault (KV v2).
# The keystore lives in a private temp dir only while Gradle runs and is deleted afterwards.
#
#   VAULT_ADDR=https://vault.example VAULT_TOKEN=… ./release-bundle.sh [extra Gradle args]
#
# Secret (default path secret/services/pokemon-cards, override with VAULT_SECRET_PATH):
#   UPLOAD_KEYSTORE_B64, UPLOAD_KEYSTORE_PASSWORD, UPLOAD_KEY_ALIAS, UPLOAD_KEY_PASSWORD
# Output: app/build/outputs/bundle/release/app-release.aab
set -euo pipefail
cd "$(dirname "$0")"

: "${VAULT_ADDR:?set VAULT_ADDR}"
: "${VAULT_TOKEN:?set VAULT_TOKEN}"
secret_path="${VAULT_SECRET_PATH:-secret/services/pokemon-cards}"
mount="${secret_path%%/*}"
rest="${secret_path#*/}"

# mktemp -d creates the directory mode 0700, so only this user can read the key inside it.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsS -H "X-Vault-Token: $VAULT_TOKEN" "${VAULT_ADDR%/}/v1/$mount/data/$rest" -o "$tmp/secret.json"
# Writes the keystore and prints shell-quoted exports; no value reaches the terminal.
exports="$(python3 -I - "$tmp" <<'EOF'
import base64, json, shlex, sys
tmp = sys.argv[1]
data = json.load(open(f"{tmp}/secret.json"))["data"]["data"]
missing = [k for k in ("UPLOAD_KEYSTORE_B64", "UPLOAD_KEYSTORE_PASSWORD", "UPLOAD_KEY_ALIAS", "UPLOAD_KEY_PASSWORD") if not data.get(k)]
if missing:
    sys.exit("missing in Vault: " + ", ".join(missing))
with open(f"{tmp}/upload.jks", "wb") as f:
    f.write(base64.b64decode(data["UPLOAD_KEYSTORE_B64"]))
for key, value in {
    "UPLOAD_KEYSTORE_FILE": f"{tmp}/upload.jks",
    "UPLOAD_KEYSTORE_PASSWORD": data["UPLOAD_KEYSTORE_PASSWORD"],
    "UPLOAD_KEY_ALIAS": data["UPLOAD_KEY_ALIAS"],
    "UPLOAD_KEY_PASSWORD": data["UPLOAD_KEY_PASSWORD"],
}.items():
    print(f"export {key}={shlex.quote(value)}")
EOF
)"
rm -f "$tmp/secret.json"
eval "$exports"

./gradlew :app:bundleRelease "$@"

aab=app/build/outputs/bundle/release/app-release.aab
echo "Signed: $aab"
keytool -printcert -jarfile "$aab" | grep -E 'Owner:|SHA256:'
