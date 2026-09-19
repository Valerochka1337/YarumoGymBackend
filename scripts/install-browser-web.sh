#!/usr/bin/env bash
set -euo pipefail
[[ $(id -u) == 0 ]] || { echo 'Run with sudo' >&2; exit 1; }

archive=${1:?Pass the browser web archive}
web_root=${GYM_BROWSER_WEB_ROOT:-/srv/yarumo-web}
release_root=$web_root/releases
current=$web_root/current
[[ -f "$archive" ]] || { echo 'Browser web archive is missing' >&2; exit 1; }

install -d -m 0755 "$web_root" "$release_root"
release=$(mktemp -d "$release_root/release.XXXXXX")
old_current=$(readlink -f "$current" 2>/dev/null || true)
replace_path() {
  python3 - "$1" "$2" <<'PY'
import os
import sys
os.replace(sys.argv[1], sys.argv[2])
PY
}

rollback() {
  local status=$?
  if [[ "$status" != 0 ]]; then
    echo 'Browser web deployment failed; restoring the previous release.' >&2
    if [[ -n "$old_current" ]]; then
      ln -sfn "$old_current" "$current.rollback"
      replace_path "$current.rollback" "$current"
    else
      rm -f "$current"
    fi
    rm -rf "$release"
  fi
  rm -f "$current.rollback"
}
trap rollback EXIT

while IFS= read -r entry; do
  [[ "$entry" != /* && "$entry" != .. && "$entry" != ../* && "$entry" != */../* ]] || {
    echo 'Browser web archive contains an unsafe path' >&2
    exit 1
  }
done < <(tar -tzf "$archive")
tar -xzf "$archive" --no-same-owner --no-same-permissions -C "$release"
[[ -f "$release/index.html" && -f "$release/sw.js" && -f "$release/manifest.webmanifest" ]] || {
  echo 'Browser web archive is incomplete' >&2
  exit 1
}
grep -Fq '<title>Yarumo coach</title>' "$release/index.html"
chmod -R u=rwX,go=rX "$release"
ln -sfn "$release" "$current.next"
replace_path "$current.next" "$current"
[[ $(readlink -f "$current") == "$release" ]]

trap - EXIT
printf 'Browser web release installed at %s\n' "$release"
