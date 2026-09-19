#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Separate persistent secrets for the isolated playground, never copy production .env.
if [[ ! -f .env.playground ]]; then
  (umask 077
    printf 'TOKEN_PEPPER=%s\nAI_SETTINGS_ENCRYPTION_KEY=%s\n' "$(openssl rand -hex 32)" "$(openssl rand -base64 32)" > .env.playground
  )
fi
set -a
source .env.playground
set +a
# Prevent accidental migration of inherited production AI settings.
unset AI_ENABLED AI_PROVIDER AI_API_KEY AI_BASE_URL AI_TEXT_MODEL AI_VISION_MODEL AI_COACH_MODEL AI_COACH_MODELS
export SPRING_PROFILES_ACTIVE=playground
export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=18081 SERVER_FORWARD_HEADERS_STRATEGY=none
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:15433/gym_playground
export SPRING_DATASOURCE_USERNAME=gym SPRING_DATASOURCE_PASSWORD=local-playground-only
export MAIL_ENABLED=false
docker compose -f infra/compose.playground.yaml up -d --wait
printf '\nCoach playground: http://localhost:18081/dev/coach/\n\n'
exec ./gradlew bootRun
