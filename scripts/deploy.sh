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
browser_web_root=${GYM_BROWSER_WEB_ROOT:-/srv/yarumo-web}
browser_current=$browser_web_root/current
browser_old_current=$(readlink -f "$browser_current" 2>/dev/null || true)
browser_installed=false
restore_browser_web() {
  if [[ "$browser_installed" != true ]]; then return 0; fi
  if [[ -n "$browser_old_current" ]]; then
    ln -sfn "$browser_old_current" "$browser_current.rollback"
    python3 - "$browser_current.rollback" "$browser_current" <<'PY'
import os
import sys
os.replace(sys.argv[1], sys.argv[2])
PY
  else
    rm -f "$browser_current"
  fi
  browser_installed=false
}
cleanup() {
  local status=$?
  if [[ "$status" != 0 ]]; then
    restore_browser_web || true
    if [[ -f "$env_backup" ]]; then cp "$env_backup" .env; fi
  fi
  rm -f "$env_backup" "$browser_current.rollback" incoming/smtp.json incoming/ai.json incoming/ai-encryption.json incoming/nginx.conf incoming/install-nginx-routes.py incoming/browser-web.tar.gz incoming/install-browser-web.sh
}
trap cleanup EXIT
compose=(docker compose --env-file .env -f compose.production.yaml)
old_image=$(sed -n 's/^BACKEND_IMAGE=//p' .env)
docker pull "$new_image"
if [[ -f incoming/admin-role.sh ]]; then install -m 0755 incoming/admin-role.sh admin-role.sh; fi
if [[ -f incoming/admin-password.sh ]]; then install -m 0755 incoming/admin-password.sh admin-password.sh; fi
if "${compose[@]}" ps --status running --services | grep -qx postgres; then ./backup.sh; fi
install_browser_web() {
  local present=0
  [[ -f incoming/browser-web.tar.gz ]] && ((present+=1))
  [[ -f incoming/install-browser-web.sh ]] && ((present+=1))
  if [[ "$present" == 0 ]]; then return 0; fi
  [[ "$present" == 2 ]] || { echo 'Browser web deployment files are incomplete' >&2; return 1; }
  install -m 0755 incoming/install-browser-web.sh install-browser-web.sh
  ./install-browser-web.sh incoming/browser-web.tar.gz
  browser_installed=true
  rm -f incoming/browser-web.tar.gz incoming/install-browser-web.sh
}
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
  local target backup candidate headers share_headers body share_body listen_address_file smoke_address
  local asset_status share_status root_status attempt smoke_ready=false
  local effective_config targets_file config canonical probe strategy selected_strategy
  local managed_count share_location_count marker_count
  local host=api.valerochkagym.tech
  local origin="https://$host"
  local -a nginx_targets=()
  local -a smoke_curl=()
  if [[ ! -f incoming/nginx.conf && ! -f incoming/install-nginx-routes.py ]]; then return 0; fi
  [[ -f incoming/nginx.conf && -f incoming/install-nginx-routes.py ]] || {
    echo 'Nginx deployment files are missing' >&2
    return 1
  }
  effective_config=$(mktemp nginx.effective.XXXXXX)
  targets_file=$(mktemp nginx.targets.XXXXXX)
  if ! nginx -T > "$effective_config"; then
    rm -f "$effective_config" "$targets_file"
    return 1
  fi
  for strategy in spa api; do
    : > "$targets_file"
    while IFS= read -r config; do
      canonical=$(readlink -f "$config" 2>/dev/null || true)
      [[ -n "$canonical" && -f "$canonical" ]] || continue
      probe=$(mktemp nginx.probe.XXXXXX)
      if python3 incoming/install-nginx-routes.py --server-strategy "$strategy" \
        "$canonical" incoming/nginx.conf "$probe" 2>/dev/null; then
        printf '%s\n' "$canonical" >> "$targets_file"
      fi
      rm -f "$probe"
    done < <(sed -n 's/^# configuration file \(.*\):$/\1/p' "$effective_config")
    mapfile -t nginx_targets < <(sort -u "$targets_file")
    printf 'Nginx route candidates using %s: %s\n' "$strategy" "${#nginx_targets[@]}"
    if [[ ${#nginx_targets[@]} == 1 ]]; then
      selected_strategy=$strategy
      break
    fi
    if [[ ${#nginx_targets[@]} -gt 1 ]]; then break; fi
  done
  if [[ ${#nginx_targets[@]} != 1 ]]; then
    echo "Expected one active IPv4 HTTPS config for $host; found ${#nginx_targets[@]}." >&2
    echo 'Loaded config files mentioning the API host:' >&2
    while IFS= read -r config; do
      canonical=$(readlink -f "$config" 2>/dev/null || true)
      if [[ -f "$canonical" ]] && grep -q "$host" "$canonical"; then
        printf '  %s -> %s\n' "$config" "$canonical" >&2
      fi
    done < <(sed -n 's/^# configuration file \(.*\):$/\1/p' "$effective_config")
    rm -f "$effective_config" "$targets_file"
    return 1
  fi
  target=${nginx_targets[0]}
  printf 'Installing App Links routes into active config: %s (%s)\n' "$target" "$selected_strategy"
  rm -f "$effective_config" "$targets_file"
  backup=$(mktemp nginx.rollback.XXXXXX)
  candidate=$(mktemp nginx.candidate.XXXXXX)
  headers=$(mktemp nginx.headers.XXXXXX)
  share_headers=$(mktemp nginx.share-headers.XXXXXX)
  body=$(mktemp nginx.body.XXXXXX)
  share_body=$(mktemp nginx.share-body.XXXXXX)
  listen_address_file=$(mktemp nginx.listen-address.XXXXXX)
  cp "$target" "$backup"
  restore_nginx() {
    cp "$backup" "$target"
    nginx -t && systemctl reload nginx
  }
  if ! python3 incoming/install-nginx-routes.py \
       --listen-address-output "$listen_address_file" \
       --server-strategy "$selected_strategy" \
       "$target" incoming/nginx.conf "$candidate" ||
     ! install -m 0644 "$candidate" "$target" ||
     ! nginx -t ||
     ! systemctl reload nginx; then
    echo 'Nginx route installation failed; restoring the previous server block.' >&2
    if ! restore_nginx; then echo 'Nginx rollback also failed' >&2; fi
    rm -f "$backup" "$candidate" "$headers" "$share_headers" "$body" "$share_body" "$listen_address_file"
    return 1
  fi
  smoke_address=$(cat "$listen_address_file")
  if [[ ! "$smoke_address" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    echo 'Nginx route installer returned an invalid listen address; restoring the previous server block.' >&2
    if ! restore_nginx; then echo 'Nginx rollback also failed' >&2; fi
    rm -f "$backup" "$candidate" "$headers" "$share_headers" "$body" "$share_body" "$listen_address_file"
    return 1
  fi
  printf 'Checking App Links routes through selected listener: %s:443\n' "$smoke_address"
  smoke_curl=(
    curl --silent --show-error
    --noproxy '*'
    --resolve "$host:443:$smoke_address"
    --connect-timeout 5 --max-time 15
    --retry 3 --retry-delay 1 --retry-connrefused
  )
  # Verify the Nginx instance we just reloaded. Public DNS can be cached or routed through
  # another edge, which must not make an otherwise valid on-host deployment roll back.
  # `systemctl reload` only signals the Nginx master. Old workers can still accept new
  # connections for a brief window, so wait until requests observe the new route table.
  for attempt in {1..15}; do
    asset_status=$("${smoke_curl[@]}" --dump-header "$headers" --output "$body" --write-out '%{http_code}' "$origin/.well-known/assetlinks.json" || true)
    share_status=$("${smoke_curl[@]}" --dump-header "$share_headers" --output "$share_body" --write-out '%{http_code}' "$origin/r/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" || true)
    root_status=$("${smoke_curl[@]}" --output /dev/null --write-out '%{http_code}' "$origin/" || true)
    if [[ "$asset_status" == 200 && "$root_status" == 200 ]] &&
       grep -Eiq '^content-type:[[:space:]]*application/json' "$headers" &&
       grep -q 'com.valerochka1337.valerochkagym' "$body" &&
       grep -q 'delegate_permission/common.handle_all_urls' "$body" &&
       grep -Eiq '^x-yarumo-route:[[:space:]]*browser-trial' "$share_headers" &&
       grep -Fq '<title>Yarumo coach</title>' "$share_body"; then
      smoke_ready=true
      break
    fi
    if [[ "$attempt" != 15 ]]; then sleep 1; fi
  done
  printf 'Nginx smoke: assetlinks=%s share=%s root=%s\n' "$asset_status" "$share_status" "$root_status"
  if [[ "$smoke_ready" != true ]]; then
    echo 'Nginx App Links smoke check failed; restoring the previous server block.' >&2
    echo 'Routine-share response headers:' >&2
    sed -n '1,30p' "$share_headers" >&2
    effective_config=$(mktemp nginx.failed-effective.XXXXXX)
    if nginx -T > "$effective_config" 2>/dev/null; then
      managed_count=$(grep -Fc '# BEGIN MANAGED ROUTINE SHARE ROUTES' "$effective_config" || true)
      share_location_count=$(grep -Ec 'location[[:space:]]+\^~[[:space:]]+/r/' "$effective_config" || true)
      marker_count=$(grep -Fc 'add_header X-Yarumo-Route browser-trial always' "$effective_config" || true)
      printf 'Loaded Nginx route counts: managed=%s share_location=%s marker=%s\n' \
        "$managed_count" "$share_location_count" "$marker_count" >&2
    else
      echo 'Unable to inspect the loaded Nginx configuration.' >&2
    fi
    rm -f "$effective_config"
    if ! restore_nginx; then echo 'Nginx rollback also failed' >&2; fi
    rm -f "$backup" "$candidate" "$headers" "$share_headers" "$body" "$share_body" "$listen_address_file"
    return 1
  fi
  install -m 0644 "$target" nginx.conf
  rm -f "$backup" "$candidate" "$headers" "$share_headers" "$body" "$share_body" "$listen_address_file"
}
install_browser_web
set_image "$new_image"
if ! "${compose[@]}" up -d --wait --wait-timeout 180 ||
   ! curl --fail --silent --retry 5 --retry-delay 3 https://api.valerochkagym.tech/health ||
   ! install_nginx_routes; then
  echo 'Deployment failed; restoring the previous application image and environment (database migrations are not reversed).' >&2
  restore_browser_web
  mv "$env_backup" .env
  if [[ -n "$old_image" ]]; then "${compose[@]}" up -d --wait --wait-timeout 180; fi
  exit 1
fi
printf '%s\n' "$old_image" > previous-image
