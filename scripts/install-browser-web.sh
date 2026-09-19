#!/usr/bin/env bash
set -euo pipefail
[[ $(id -u) == 0 ]] || { echo 'Run with sudo' >&2; exit 1; }

archive=${1:?Pass the browser web archive}
nginx_source=${2:?Pass the browser web Nginx config}
host=app.valerochkagym.tech
api_host=api.valerochkagym.tech
deploy_root=${GYM_DEPLOY_DIR:-/opt/valerochkagym}
web_root=${GYM_BROWSER_WEB_ROOT:-/srv/yarumo-web}
release_root=$web_root/releases
current=$web_root/current
nginx_available=${GYM_NGINX_AVAILABLE:-/etc/nginx/sites-available}
nginx_enabled=${GYM_NGINX_ENABLED:-/etc/nginx/sites-enabled}
letsencrypt_root=${GYM_LETSENCRYPT_ROOT:-/etc/letsencrypt/live}
target=$nginx_available/$host
enabled=$nginx_enabled/$host

[[ -f "$archive" && -f "$nginx_source" ]] || { echo 'Browser web deployment files are missing' >&2; exit 1; }
grep -Fq 'server_name app.valerochkagym.tech' "$nginx_source"
grep -Fq 'root /srv/yarumo-web/current' "$nginx_source"
grep -Fq '/etc/letsencrypt/live/app.valerochkagym.tech/fullchain.pem' "$nginx_source"

app_addresses=()
while IFS= read -r address; do app_addresses+=("$address"); done < <(getent ahostsv4 "$host" | awk '{print $1}' | sort -u)
api_addresses=()
while IFS= read -r address; do api_addresses+=("$address"); done < <(getent ahostsv4 "$api_host" | awk '{print $1}' | sort -u)
[[ ${#app_addresses[@]} -gt 0 && ${#api_addresses[@]} -gt 0 ]] || {
  echo "DNS for $host or $api_host is unavailable" >&2
  exit 1
}
dns_matches=false
for app_address in "${app_addresses[@]}"; do
  for api_address in "${api_addresses[@]}"; do
    if [[ "$app_address" == "$api_address" ]]; then dns_matches=true; fi
  done
done
[[ "$dns_matches" == true ]] || {
  echo "$host must resolve to the same production address as $api_host" >&2
  exit 1
}

install -d -m 0755 "$web_root" "$release_root"
install -d -m 0755 "$nginx_available" "$nginx_enabled" "$deploy_root"
release=$(mktemp -d "$release_root/release.XXXXXX")
target_backup=$(mktemp "$deploy_root/browser-nginx.rollback.XXXXXX")
target_existed=false
enabled_created=false
old_current=$(readlink "$current" 2>/dev/null || true)
if [[ -f "$target" ]]; then cp "$target" "$target_backup"; target_existed=true; fi
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
    echo 'Browser web deployment failed; restoring the previous release and Nginx config.' >&2
    if [[ -n "$old_current" ]]; then
      ln -sfn "$old_current" "$current.rollback"
      replace_path "$current.rollback" "$current"
    else
      rm -f "$current"
    fi
    if [[ "$target_existed" == true ]]; then cp "$target_backup" "$target"; else rm -f "$target"; fi
    if [[ "$enabled_created" == true ]]; then rm -f "$enabled"; fi
    nginx -t && systemctl reload nginx || true
    rm -rf "$release"
  fi
  rm -f "$target_backup" "$current.rollback"
}
trap rollback EXIT

while IFS= read -r entry; do
  [[ "$entry" != /* && "$entry" != .. && "$entry" != ../* && "$entry" != */../* ]] || {
    echo 'Browser web archive contains an unsafe path' >&2
    exit 1
  }
done < <(tar -tzf "$archive")
tar -xzf "$archive" --no-same-owner --no-same-permissions -C "$release"
[[ -f "$release/index.html" && -f "$release/sw.js" ]] || {
  echo 'Browser web archive is incomplete' >&2
  exit 1
}
chmod -R u=rwX,go=rX "$release"
ln -sfn "$release" "$current.next"
replace_path "$current.next" "$current"

if [[ ! -e "$enabled" && ! -L "$enabled" ]]; then
  ln -s "$target" "$enabled"
  enabled_created=true
fi
read -r enabled_target canonical_target < <(python3 - "$enabled" "$target" <<'PY'
import os
import sys
print(os.path.realpath(sys.argv[1]), os.path.realpath(sys.argv[2]))
PY
)
[[ "$enabled_target" == "$canonical_target" ]] || {
  echo "$enabled points to an unexpected config" >&2
  exit 1
}

certificate=$letsencrypt_root/$host/fullchain.pem
if [[ ! -f "$certificate" ]]; then
  bootstrap=$(mktemp "$deploy_root/browser-nginx.bootstrap.XXXXXX")
  cat > "$bootstrap" <<'NGINX'
server {
  listen 80;
  listen [::]:80;
  server_name app.valerochkagym.tech;
  root /srv/yarumo-web/current;
  location /.well-known/acme-challenge/ { try_files $uri =404; }
  location / { return 503; }
}
NGINX
  install -m 0644 "$bootstrap" "$target"
  rm -f "$bootstrap"
  nginx -t
  systemctl reload nginx
  certbot certonly --webroot --webroot-path "$current" --domains "$host" \
    --non-interactive --agree-tos --keep-until-expiring
fi

install -m 0644 "$nginx_source" "$target"
nginx -t
systemctl reload nginx

origin=https://$host
curl_local=(curl --silent --show-error --noproxy '*' --resolve "$host:443:127.0.0.1" --connect-timeout 5 --max-time 15)
smoke_dir=$(mktemp -d "$deploy_root/browser-smoke.XXXXXX")
page_status=$("${curl_local[@]}" --output "$smoke_dir/page" --write-out '%{http_code}' "$origin/r/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
worker_status=$("${curl_local[@]}" --output "$smoke_dir/worker" --write-out '%{http_code}' "$origin/sw.js")
csrf_status=$("${curl_local[@]}" --header "Origin: $origin" --output "$smoke_dir/csrf" --write-out '%{http_code}' "$origin/v1/web/auth/csrf")
[[ "$page_status" == 200 && "$worker_status" == 200 && "$csrf_status" == 200 ]]
grep -Fq 'Yarumo — пробная тренировка' "$smoke_dir/page"
grep -Fq 'yarumo-shell-v1' "$smoke_dir/worker"
rm -rf "$smoke_dir"

trap - EXIT
rm -f "$target_backup"
printf 'Browser web release installed at %s\n' "$release"
