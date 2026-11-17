#!/usr/bin/env bash
# Nightly price import on Vulcan, started by pokemon-cards-prices.timer (see deploy.sh).
# R2 credentials are read from Vault at run time and only live in this process's environment. Without them
# the importer still runs and writes the files locally, so price history keeps accumulating.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)

vault_addr=${VAULT_ADDR:-https://vault.fabian-duerkop.de}
token_file=${VAULT_TOKEN_FILE:-$HOME/.config/vault/apps-token}
secret_path=${PRICES_VAULT_PATH:-secret/data/services/pokemon-cards}

if [[ -r $token_file ]]; then
  # The header comes from a file descriptor, so the token never appears in the process list.
  if secret=$(curl -fsS --max-time 20 -H @<(printf 'X-Vault-Token: %s\n' "$(<"$token_file")") \
      "$vault_addr/v1/$secret_path" 2>/dev/null); then
    # Keys carry the ENV names (R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET). Values are
    # single-quoted for the shell, so `$` or backticks in a secret stay literal.
    eval "$(node -e '
      const d = JSON.parse(require("fs").readFileSync(0, "utf8")).data.data;
      const q = (v) => "'"'"'" + String(v).replaceAll("'"'"'", "'"'"'\\'"'"''"'"'") + "'"'"'";
      for (const k of ["R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET"])
        if (d[k]) console.log(`export ${k}=${q(d[k])}`);
    ' <<<"$secret")"
  else
    echo "vault: no R2 credentials at $secret_path, publishing locally only" >&2
  fi
fi

exec node --disable-warning=ExperimentalWarning "$here/importer.mjs" "$@"
