#!/usr/bin/env bash
set -euo pipefail
cd "${GYM_DEPLOY_DIR:-/opt/valerochkagym}"
new_image="${1:?Pass an immutable image reference}"
[[ "$new_image" =~ ^ghcr.io/valerochka1337/valerochkagymbackend@sha256:[a-f0-9]{64}$ ]] || { echo 'Expected the backend GHCR image digest' >&2; exit 2; }
exec 9>.deploy.lock
flock -n 9 || { echo 'Deployment already running' >&2; exit 1; }
umask 077
env_backup=$(mktemp .env.rollback.XXXXXX)
cp .env "$env_backup"
cleanup() {
  local status=$?
  if [[ "$status" != 0 && -f "$env_backup" ]]; then cp "$env_backup" .env; fi
  rm -f "$env_backup" incoming/smtp.json incoming/ai.json incoming/ai-encryption.json incoming/nginx.conf incoming/install-nginx-routes.py
}
trap cleanup EXIT
compose=(docker compose --env-file .env -f compose.production.yaml)
old_image=$(sed -n 's/^BACKEND_IMAGE=//p' .env)
docker pull "$new_image"
if [[ -f incoming/admin-role.sh ]]; then install -m 0755 incoming/admin-role.sh admin-role.sh; fi
if [[ -f incoming/admin-password.sh ]]; then install -m 0755 incoming/admin-password.sh admin-password.sh; fi
if "${compose[@]}" ps --status running --services | grep -qx postgres; then ./backup.sh; fi
if [[ -f incoming/ai-encryption.json ]]; then
  install -m 0755 incoming/ai-encryption-config.py ai-encryption-config.py
  # Database migrations survive an application rollback, so retain a newly installed key too.
  python3 ai-encryption-config.py apply incoming/ai-encryption.json "$env_backup"
  python3 ai-encryption-config.py apply incoming/ai-encryption.json .env
  rm -f incoming/ai-encryption.json
fi
if [[ -f incoming/smtp.json ]]; then
  install -m 0755 incoming/smtp-config.py smtp-config.py
  python3 smtp-config.py apply incoming/smtp.json .env
  rm -f incoming/smtp.json
fi
set_image() {
  local value="$1"
  sed '/^BACKEND_IMAGE=/d' .env > .env.next
  printf 'BACKEND_IMAGE=%s\n' "$value" >> .env.next
  mv .env.next .env
}
install_nginx_routes() {
  local target backup candidate headers share_headers body asset_status share_status root_status
  local host=api.valerochkagym.tech
  local origin="https://$host"
  local -a smoke_curl=(
    curl --silent --show-error
    --noproxy '*'
    --resolve "$host:443:127.0.0.1"
    --connect-timeout 5 --max-time 15
    --retry 3 --retry-delay 1 --retry-connrefused
  )
  if [[ ! -f incoming/nginx.conf && ! -f incoming/install-nginx-routes.py ]]; then return 0; fi
  [[ -f incoming/nginx.conf && -f incoming/install-nginx-routes.py ]] || {
    echo 'Nginx deployment files are missing' >&2
    return 1
  }
  target=$(readlink -f /etc/nginx/sites-enabled/api.valerochkagym.tech)
  [[ -f "$target" ]] || {
    echo 'Installed api.valerochkagym.tech server block was not found' >&2
    return 1
  }
  backup=$(mktemp nginx.rollback.XXXXXX)
  candidate=$(mktemp nginx.candidate.XXXXXX)
  headers=$(mktemp nginx.headers.XXXXXX)
  share_headers=$(mktemp nginx.share-headers.XXXXXX)
  body=$(mktemp nginx.body.XXXXXX)
  cp "$target" "$backup"
  restore_nginx() {
    cp "$backup" "$target"
    nginx -t && systemctl reload nginx
  }
  if ! python3 incoming/install-nginx-routes.py "$target" incoming/nginx.conf "$candidate" ||
     ! install -m 0644 "$candidate" "$target" ||
     ! nginx -t ||
     ! systemctl reload nginx; then
    echo 'Nginx route installation failed; restoring the previous server block.' >&2
    if ! restore_nginx; then echo 'Nginx rollback also failed' >&2; fi
    rm -f "$backup" "$candidate" "$headers" "$share_headers" "$body"
    return 1
  fi
  # Verify the Nginx instance we just reloaded. Public DNS can be cached or routed through
  # another edge, which must not make an otherwise valid on-host deployment roll back.
  asset_status=$("${smoke_curl[@]}" --dump-header "$headers" --output "$body" --write-out '%{http_code}' "$origin/.well-known/assetlinks.json" || true)
  share_status=$("${smoke_curl[@]}" --dump-header "$share_headers" --output /dev/null --write-out '%{http_code}' "$origin/r/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" || true)
  root_status=$("${smoke_curl[@]}" --output /dev/null --write-out '%{http_code}' "$origin/" || true)
  printf 'Nginx smoke: assetlinks=%s share=%s root=%s\n' "$asset_status" "$share_status" "$root_status"
  if [[ "$asset_status" != 200 || "$root_status" != 200 ]] ||
     ! grep -Eiq '^content-type:[[:space:]]*application/json' "$headers" ||
     ! grep -q 'com.valerochka1337.valerochkagym' "$body" ||
     ! grep -q 'delegate_permission/common.handle_all_urls' "$body" ||
     ! grep -Eiq '^x-yarumo-route:[[:space:]]*routine-share' "$share_headers"; then
    echo 'Nginx App Links smoke check failed; restoring the previous server block.' >&2
    if ! restore_nginx; then echo 'Nginx rollback also failed' >&2; fi
    rm -f "$backup" "$candidate" "$headers" "$share_headers" "$body"
    return 1
  fi
  install -m 0644 "$target" nginx.conf
  rm -f "$backup" "$candidate" "$headers" "$share_headers" "$body"
}
set_image "$new_image"
if ! "${compose[@]}" up -d --wait --wait-timeout 180 ||
   ! curl --fail --silent --retry 5 --retry-delay 3 https://api.valerochkagym.tech/health ||
   ! install_nginx_routes; then
  echo 'Deployment failed; restoring the previous application image and environment (database migrations are not reversed).' >&2
  mv "$env_backup" .env
  if [[ -n "$old_image" ]]; then "${compose[@]}" up -d --wait --wait-timeout 180; fi
  exit 1
fi
printf '%s\n' "$old_image" > previous-image
