#!/usr/bin/env bash
# Builds the importer into one file and installs it on this machine (Vulcan) as a systemd user timer.
#   importer/deploy.sh          build, install, enable the timer
#   importer/deploy.sh --run    the same, then start one run now
# Data (prices.db, files/) lives in ~/.local/share/pokemon-cards-prices and survives redeploys.
set -euo pipefail
cd "$(dirname "$0")/.."

# The unit pins the node binary this script runs with, so it must be the one in .nvmrc.
want=$(cat .nvmrc)
have=$(node -p 'process.versions.node.split(".")[0]')
if (( have < want )); then echo "node $have found, need $want: run 'nvm use' in backend/" >&2; exit 1; fi

app=$HOME/.local/share/pokemon-cards-prices/app
units=$HOME/.config/systemd/user

pnpm run importer:build
mkdir -p "$app" "$units"
install -m 644 dist/importer.mjs dist/schema.sql "$app/"
install -m 755 importer/run-nightly.sh "$app/"
node_dir=$(dirname "$(command -v node)")
sed "s|@NODE_DIR@|$node_dir|" importer/systemd/pokemon-cards-prices.service >"$units/pokemon-cards-prices.service"
install -m 644 importer/systemd/pokemon-cards-prices.timer "$units/"

systemctl --user daemon-reload
systemctl --user enable --now pokemon-cards-prices.timer
systemctl --user list-timers pokemon-cards-prices.timer --no-pager
if [[ ${1:-} == --run ]]; then systemctl --user start --no-block pokemon-cards-prices.service; fi
