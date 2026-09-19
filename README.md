# ValerochkaGym Backend

Kotlin / Spring Boot 4.1.1 / PostgreSQL 17 / Liquibase. API для Android:
Google и email/пароль, сессии устройств, упражнения, залы, программы, тренировки,
замеры и расписание. Sheets не используется. Excel-экспорт — после MVP.

## Локальный запуск

Требуются JDK 21 и Docker с Compose.

```bash
docker compose up -d
cp .env.example .env
set -a
source .env
set +a
./gradlew bootRun
```

API: `http://localhost:8080/v1`. Тестовая почта Mailpit: `http://localhost:8025`.
Коды подтверждения действуют 10 минут; пароль — от 12 до 128 символов.

```bash
./gradlew check bootJar
docker build -t valerochkagym-backend:mvp .
```

`check` включает форматирование и HTTP-тесты с PostgreSQL/Testcontainers и Liquibase.
Google JWT проверяются локальными RSA-ключами; письма перехватывает тестовый адаптер.
Проверяется также реальный JSON-снимок, сформированный Android-кодом.
На локальной Colima несовместимый Ryuk нужно отключить (JUnit закрывает PostgreSQL):

```bash
DOCKER_HOST=unix:///Users/valerochka1337/.colima/default/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true ./gradlew check
```

В GitHub CI используется стандартный Docker без этого исключения.

## API и безопасность

[OpenAPI](docs/openapi.json), [протокол данных](docs/api.md).
Актуальная OpenAPI-схема — `/v3/api-docs` с Bearer-токеном, Swagger UI выключен по умолчанию.

- `/v1/auth/register`, `/v1/auth/login`, `/v1/auth/verify`, `/v1/auth/verify/request`.
- `/v1/auth/password/request`, `/v1/auth/password/reset`.
- `/v1/auth/google/nonce`, `/v1/auth/google`, `/v1/auth/refresh`.
- `/v1/me`, `/v1/me/google`, `/v1/me/delete-code`, `DELETE /v1/me`.
- `/v1/sessions`, `DELETE /v1/sessions/{id}`, `/v1/logout`, `/v1/logout-all`.
- `GET/POST /v1/sync`, `/v1/sync/changes`, `/v1/records/{kind}[/{id}]`.

Случайные access-токены живут 15 минут, refresh-сессия — 30 дней от входа.
Refresh вращается; повтор использованного токена отзывает сессию. В БД — HMAC-хеши
токенов/кодов и Argon2id-хеши паролей; `TOKEN_PEPPER` хранится отдельно от БД.
Google идентифицируется по `sub`, совпадение email не объединяет аккаунты.
Связывание способов входа требует уже авторизованной сессии.

## Production и CI/CD

[Инструкция эксплуатации](docs/operations.md).

- Ubuntu 24.04, `valerochka@62.84.122.55`, `/opt/valerochkagym`.
- Существующий Nginx/Certbot → `127.0.0.1:18080` → backend → закрытый PostgreSQL.
- Readiness: `https://api.valerochkagym.tech/health`.
- Backend: 768 MB, PostgreSQL: 384 MB; постоянный Docker volume.
- Backup ежедневно, локальное хранение 14 дней; off-host копия настраивается отдельно.
- SMTP пока не предоставлен: в production `MAIL_ENABLED=false`. Google Web client ID
  настроен; настоящий Google-вход проверяется владельцем со своим аккаунтом.
  Почту можно подключить через secrets окружения GitHub `production`: `MAIL_FROM`,
  `SMTP_HOST`, `SMTP_USERNAME`, `SMTP_PASSWORD` и variable `MAIL_ENABLED=true`
  ([настройка CD](docs/operations.md#через-github-cd-рекомендуется)).

[Workflow](.github/workflows/backend.yml): PR → проверки и Docker build; main → проверки →
GHCR → deploy по digest → health-check. При неудаче возвращается предыдущий совместимый
образ; миграции БД автоматически не откатываются. Документация проходит лёгкий статус
без JVM и тестов. Для CD нужны `DEPLOY_ENABLED=true` и environment `production` с
`DEPLOY_SSH_KEY`/`DEPLOY_KNOWN_HOSTS`. Временный GITHUB_TOKEN передаётся по SSH stdin
для GHCR; Docker credentials удаляются после deploy.

CI/CD настроен и проверен реальным деплоем из main. Production environment доступен
только main; SSH-ключ хранится в GitHub environment secrets.

## Android и границы MVP

Ветка `feat/backend-integration`, рабочая копия `/private/tmp/ValerochkaGym-backend-integration`.
Версия 1.3.18 (26), Room v15. Room и очередь сохраняют работу без интернета; точный пакет
записывается в outbox перед HTTP, подтверждённые версии — в baseline. Токены зашифрованы
Android Keystore и лежат в `noBackupFilesDir`. В Android с исправлением смены аккаунта вход с другим email автоматически очищает
кэш предыдущего пользователя и загружает историю выбранного аккаунта. Старые локальные
данные без аккаунта не переносятся. Ручная очистка приложения не требуется.
Remote sync отложен до окончания активной тренировки, чтобы сохранить ID подходов,
используемые foreground-сервисом. Calendar и AI остаются в Android.

PostgreSQL хранит отдельные JSONB-агрегаты с UUID и revision; принадлежность, ссылки,
поля и предметные правила проверяются сервером в транзакции. Начальный клиент читает
полный снимок; API также предоставляет постраничные изменения. Лимиты: 1000 изменений
и 10 MB на запрос, 20000 записей с tombstone и 16 MB содержимого на аккаунт.
Tombstone и идемпотентные операции хранятся до удаления аккаунта.
Нет тренерских ролей, MFA, высокой доступности, Sheets,
серверного Calendar/AI и Excel-экспорта. Один VPS допускает краткий простой при обновлении.

## Админка

Веб-интерфейс /admin/: сводка, пользователи, упражнения, залы, программы, тренировки,
замеры, расписание и журнал действий. Упражнения и залы можно создавать и редактировать
с проверкой конфликтов и синхронизацией с Android. Права выдаются оператором сервера
подтверждённому аккаунту; вход — по отдельному логину и паролю администратора. [Настройка и эксплуатация](docs/admin.md).

Тесты интерфейса: npm ci --ignore-scripts && npm test (Node.js 24, только для разработки/CI).

## Стандартный каталог

Backend использует Spring Data JPA/Hibernate; схема управляется Liquibase,
`ddl-auto=validate`, Open Session in View отключён. Слои: `controller`, `service`,
`repository`, `utils`, отдельно `config` и `security`. JSONB сохраняет агрегаты,
PostgreSQL upsert и атомарные операции изолированы в репозиториях.

[Контракт каталога, админка и порядок перехода Android](docs/catalog-transition.md).
Переход выключен до явного запуска команды переноса.

## Серверные AI-черновики (default off)

AI exercise/InBody доступен только через авторизованный backend. Провайдер, API-ключ,
модели текста, изображений и тренера настраиваются в **Админка → ИИ** и хранятся в PostgreSQL.
Изменения применяются к новым запросам без перезапуска. Поддерживается OpenAI-совместимый
Chat Completions API с HTTPS URL (origin или `/v1`). Модель текста должна поддерживать
strict JSON schema, vision — JPEG image input, модель тренера — function tools.

В GitHub Environment `production` задаётся secret `AI_SETTINGS_ENCRYPTION_KEY`: Base64 от 32 случайных байт
(`openssl rand -base64 32`). API-ключ хранится с AES-256-GCM и уникальным случайным nonce.
Ключ шифрования нужно хранить отдельно от БД и резервировать отдельно; его потеря сделает
сохранённые креденшелы недоступными. Нельзя менять его без перешифрования или повторного
ввода API-ключа. Не добавляйте его в git. Workflow доставляет secret отдельным временным JSON-файлом
с закрытыми правами; deploy атомарно записывает его в серверный `.env`, откуда его получает
Production Compose. Доступ к серверу для этого не нужен. Payload удаляется на runner и сервере,
значение не выводится в логи. Отсутствующий или некорректный secret останавливает доставку.
Повторный деплой с тем же ключом безопасен; замена существующего ключа другим запрещена,
чтобы не потерять доступ к зашифрованным данным. Ключ сохраняется и при откате приложения,
поскольку миграция БД при откате не отменяется.

При первом запуске существующая валидная конфигурация `AI_*` автоматически переносится
в БД, если настройки ещё не редактировались. Для переноса заранее задайте ключ шифрования:
при включённой старой конфигурации без него запуск остановится с понятной ошибкой.
После успешного переноса удалите старые `AI_*` провайдера/моделей из `.env` и GitHub
Secrets/Variables. CD больше не доставляет настройки провайдера и не перезаписывает их.
Без старой конфигурации ИИ изначально выключен, настройки вводятся в админке.

Контракт и ошибки — [docs/api.md](docs/api.md), frozen DTO —
[src/test/resources/ai-contract-v1.json](src/test/resources/ai-contract-v1.json).
Тестовый путь: targeted `AiIntegrationTest`, `AiActionServiceTest`,
`HttpOpenAiChatCompletionsProviderTest` и `python3 -m unittest discover -s scripts/tests`.


### Live Coach

Модель тренера по умолчанию и список разрешённых моделей задаются в разделе «ИИ».
Если модель тренера пуста, используется модель текста. Android выбирает модель только
из этого списка. См. [API contract](vibe/live-coach-plan.md).

### Локальный Coach Playground

`./scripts/coach-playground.sh` → http://localhost:18081/dev/coach/.
Тренировка, чат с настоящей моделью, подтверждение предложений, сценарии и диагностика.
Отдельная локальная БД, автоматический тестовый вход; ключ и модель задаются в интерфейсе.
[Запуск и сценарии проверки](docs/coach-playground.md).
