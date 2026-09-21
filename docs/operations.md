# Эксплуатация

## Размещение

Сервер: Ubuntu 24.04, `valerochka@62.84.122.55`, 2 GB RAM. Каталог `/opt/valerochkagym`.
Nginx/Certbot существовали до backend; отдельный серверный блок API сохранён и обновлён.
Конфигурация прежнего server block сохраняется в `/opt/valerochkagym/nginx-before-backend.*`.
Соседний контейнер MTProto не изменяется.

```bash
ssh valerochka@62.84.122.55
cd /opt/valerochkagym
sudo docker compose --env-file .env -f compose.production.yaml ps
curl --fail https://api.valerochkagym.tech/health
sudo docker compose --env-file .env -f compose.production.yaml logs --tail=100 backend
```

Секреты создаёт bootstrap в root-only `.env` (0600): случайный пароль PostgreSQL и
TOKEN_PEPPER. Не выводить содержимое файла в логи/чат и не коммитить. Контейнер backend
не имеет root-прав, файловая система read-only, временные файлы в tmpfs; БД не публикует порт.
Приложение доступно на host loopback 18080, снаружи — только Nginx HTTPS.
Readiness проверяет БД. Наружные `/actuator/*` закрыты; `/health` проксируется к readiness.
Публичного диагностического endpoint с environment или содержимым БД нет.

Для повторного размещения на таком же подготовленном сервере скопировать `infra/` и
`scripts/`, выполнить `sudo bash scripts/bootstrap.sh`, загрузить образ и запустить Compose.
После успешного readiness — `sudo bash scripts/activate-proxy.sh`. Существующий `.env`
bootstrap не перезаписывает. Он не устанавливает Docker, Nginx или сертификаты за владельца.

Обычный CD переносит `infra/nginx.conf` и перед публикацией проверяет управляемые маршруты
`/.well-known/assetlinks.json` и `/r/<token>`. На общем с веб-клиентом домене deploy заменяет
только отмеченный блок этих маршрутов перед SPA fallback, проверяет `nginx -t`, перезагружает
Nginx и выполняет smoke-check. При любой ошибке прежний server block восстанавливается.

## Почта

При первоначальном развёртывании bootstrap задаёт `MAIL_ENABLED=false`: регистрация,
восстановление пароля и удаление аккаунта с кодом возвращают `503 mail_unavailable`.
Это выключатель отправки, а не проблема Android. Проверять актуальное значение нужно
на сервере; `/health` проверяет БД, но не доставку писем.

### Через GitHub CD (рекомендуется)

В репозитории GitHub открыть **Settings → Environments → production**.
Создать **Environment secrets** (каждое значение отдельно, без обрамляющих кавычек):

| Secret | Значение |
|---|---|
| `MAIL_FROM` | Подтверждённый провайдером email отправителя |
| `SMTP_HOST` | Адрес SMTP-сервера |
| `SMTP_USERNAME` | SMTP-логин |
| `SMTP_PASSWORD` | SMTP-пароль / пароль приложения |

Там же в **Environment variables**:

| Variable | Значение |
|---|---|
| `MAIL_ENABLED` | `true` — применять SMTP из GitHub; `false` — отключить почту |
| `SMTP_SECURITY` | `starttls` (по умолчанию) или `ssl` |
| `SMTP_PORT` | Необязательно: по умолчанию 587 для STARTTLS, 465 для SSL |

Если `MAIL_ENABLED` не задана, CD оставляет текущие почтовые настройки сервера без изменений.
Если она `true`, все четыре secrets обязательны: неполная конфигурация прерывает деплой
до подключения к серверу. Режим без шифрования не поддерживается; SMTP_AUTH включается автоматически.

После попадания workflow и скриптов в `main` выполнить **Actions → Backend CI/CD → Run workflow**
для `main` или дождаться следующего деплоя кода. Изменение secret само по себе не запускает CD.
Так же обновляется пароль SMTP: заменить secret и запустить workflow заново.
`DEPLOY_ENABLED=true` и существующие SSH-secrets по-прежнему нужны.

Secrets доступны только job `deploy` с environment `production`, не сборке Docker и не PR.
Workflow читает их через env, проверяет и передаёт временный JSON-файл по SCP в закрытый
`incoming/`. Серверный helper под блокировкой деплоя атомарно обновляет только почтовые
ключи `.env` (0600), сохраняя DATABASE_PASSWORD, TOKEN_PEPPER и остальные настройки.
Значения не исполняются как shell-код; спецсимволы пароля экранируются для Compose.
Временные файлы удаляются после выполнения; на сервере секреты остаются в рабочем `.env`.
Если запуск нового контейнера или health-check провалится, возвращаются прежний образ и
прежний `.env`. Проверка `/health` не проверяет доставку письма — её нужно проверить отдельно.

Новый helper работает через уже установленный `install-and-deploy.sh`; переустановка sudo-entrypoint
не нужна. Для helper требуется Python 3 (штатно доступен на используемом Ubuntu 24.04).

Справка: [GitHub Environment secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).

### Ручная настройка на сервере

1. Подключить SMTP у почтового провайдера. Получить хост, порт, логин и SMTP-пароль
   (или пароль приложения, если этого требует провайдер). Подтвердить адрес отправителя
   или домен `valerochkagym.tech`; добавить выданные провайдером SPF/DKIM-записи и
   настроить DMARC по его инструкции. `MAIL_FROM` должен быть разрешённым отправителем.
2. На сервере открыть `sudoedit /opt/valerochkagym/.env` и заполнить реальные значения:

   ```dotenv
   MAIL_ENABLED=true
   MAIL_FROM=noreply@valerochkagym.tech
   SMTP_HOST=smtp.your-provider.example
   SMTP_PORT=587
   SMTP_USERNAME=your-smtp-login
   SMTP_PASSWORD='your-smtp-password'
   SMTP_AUTH=true
   SMTP_TLS=true
   SMTP_SSL=false
   ```

   Для STARTTLS/587: `SMTP_TLS=true`, `SMTP_SSL=false`.
   Для implicit TLS/465: `SMTP_PORT=465`, `SMTP_TLS=false`, `SMTP_SSL=true`
   (поддержка `SMTP_SSL` добавлена в этой версии; сначала обновить образ).
   Не включать оба режима одновременно. Остальные значения даёт провайдер.
   Внутри контейнера `localhost` — сам backend, а не почтовый сервер на VPS.
3. Пересоздать контейнер, чтобы он прочитал изменённое окружение. Обычный `restart`
   не применяет изменения `.env`:

   ```bash
   cd /opt/valerochkagym
   sudo docker compose --env-file .env -f compose.production.yaml up -d --no-deps --force-recreate backend
   sudo docker compose --env-file .env -f compose.production.yaml logs --since=10m backend
   ```

4. Зарегистрировать свой тестовый email в приложении, дождаться письма, ввести код,
   затем войти с паролем. Проверить повторную отправку и восстановление пароля.
   Пароль — 12–128 символов, код — 8 цифр, срок — 10 минут. Если отправка при регистрации
   завершается ошибкой, транзакция откатывается; регистрацию можно повторить.

Если получен `503 mail_unavailable`, проверить включение отправки и настройки SMTP.
При ошибке SMTP backend пишет только класс ошибки без адреса, кода и секретов:
`MailAuthenticationException` указывает на авторизацию, `MailSendException` — на
подключение или отказ при отправке. Точную причину смотреть в журнале провайдера;
проверить доступ VPS к нужному SMTP-порту. Не включать JavaMail debug с письмами в production.

Если API ответил успешно, SMTP принял письмо, но доставка ещё не гарантирована:
проверить delivery/bounce-статус у провайдера, подтверждение отправителя, SPF/DKIM,
папку «Спам» и ограничения тестового режима провайдера на получателей.

Локальный `compose.yaml` запускает Mailpit: письма доступны на `http://localhost:8025`.
Этот конфиг не пересылает их во внешние почтовые ящики. Не использовать локальную
`.env.example` как готовую production-конфигурацию.

Справка: [Spring Boot mail](https://docs.spring.io/spring-boot/reference/io/email.html),
[Mailpit и пересылка](https://mailpit.axllent.org/docs/configuration/smtp-relay/).

## Backup и восстановление

```bash
sudo /opt/valerochkagym/backup.sh
sudo /opt/valerochkagym/verify-restore.sh backups/gym-YYYYMMDDTHHMMSS.dump
sudo systemctl list-timers valerochkagym-backup.timer
sudo journalctl -u valerochkagym-backup.service --since yesterday
```

Ежедневно около 03:30 UTC создаётся `pg_dump -Fc`, сначала `.partial`, затем атомарный rename.
Локальные файлы хранятся 14 дней. Проверка restore создаёт отдельную временную БД,
восстанавливает все объекты с `--exit-on-error`, проверяет таблицы и удаляет тестовую БД.
Production-БД этим скриптом не перезаписывается.

Для внешней копии установить/настроить rclone и задать `GYM_BACKUP_REMOTE` в root-only
`/opt/valerochkagym/backup.env`. Пример: `GYM_BACKUP_REMOTE=s3:gym-backups/production`.
Настроить lifecycle внешнего хранилища отдельно. Пока off-host destination не предоставлен,
локальный backup не защищает от потери самого VPS. Цель RPO — до 24 часов после настройки
внешних ежедневных копий. Время полного восстановления сервера зависит от провайдера и данных.

Для аварийного восстановления: остановить backend, сохранить текущую БД отдельно,
восстановить выбранный backup в новую БД, проверить его и переключить `DATABASE_URL`.
Использовать совместимый со схемой образ. TOKEN_PEPPER также нужен для действующих
сессий; его восстановить из защищённой копии конфигурации либо сменить и потребовать новый вход.
Не запускать `pg_restore --clean` непосредственно на живой production-БД.

## Обновление и откат

CI собирает один JAR после тестов и публикует образ по SHA коммита. Deploy использует
`ghcr.io/valerochka1337/valerochkagymbackend@sha256:...`, а не изменяемый `latest`.
`deploy.sh` сериализует обновления через flock, скачивает образ, делает backup перед
миграциями, устанавливает проверенный browser-web artifact, запускает Compose с ожиданием
health и проверяет HTTPS. При ошибке backend возвращает предыдущий образ. Старый image
digest также сохраняется в `previous-image`.

Browser Web продолжает использовать существующий `api.valerochkagym.tech`, его DNS и
Certbot-сертификат. `install-browser-web.sh` распаковывает проверенный release в отдельный каталог
и атомарно переключает `/srv/yarumo-web/current`; затем deploy обновляет только управляемые
маршруты существующего Nginx server block и выполняет локальные HTTPS-smoke проверки. При ошибке
восстанавливаются предыдущие web-symlink, Nginx-конфигурация и совместимый backend image.

Миграции Liquibase только вперёд и обратно совместимые: сначала добавить новое поле,
обновить клиентов/код и лишь в отдельном обслуживании удалить старое. Автоматический откат
приложения не отменяет DDL и не восстанавливает данные. Разрушительные миграции не должны
попадать в обычный pipeline без отдельного плана backup/restore.

Первый запуск сделан загруженным вручную образом `valerochkagym-backend:mvp` из локально
проверенного JAR. После включения GitHub CD следующие запуски будут использовать GHCR digest.

## Включение GitHub CI/CD

1. Опубликовать и согласовать обе feature-ветки. В backend защитить main обязательным
   статусом `Backend checks`; в Android использовать существующий Android CI.
2. После отдельного разрешения создать выделенный SSH-ключ Actions и добавить его к
   серверному пользователю с ограничениями `restrict`. Этот пользователь имеет sudo:
   ключ нужно считать привилегированным production-секретом и держать только в environment.
3. Создать environment `production`, secrets `DEPLOY_SSH_KEY` и `DEPLOY_KNOWN_HOSTS`
   (проверенные host keys). Установить repository variable `DEPLOY_ENABLED=true`.
4. Main pipeline: check → publish GHCR → SCP конфигурации в incoming → SSH deploy helper.
   Временный GITHUB_TOKEN с packages:read подаётся на stdin SSH; одноразовый Docker config
   удаляется после deploy. Не требуется постоянный GHCR PAT на сервере.
5. Проверить первый реальный workflow, его тестовые отчёты и `/health` после deploy.

7 сентября 2026 года после явного согласия владельца создан отдельный ED25519-ключ
с `restrict` для существующего пользователя `valerochka`; его SSH- и sudo-доступ проверен.
Существующие ключи и sudo-конфигурация сохранены. Secrets установлены в environment
`production`, разрешающем только ветку `main`; `DEPLOY_ENABLED=true`. Временная локальная
приватная копия ключа удалена. Fingerprint публичного ключа:
`SHA256:Sr5yGY0K8X81RjbXxhwcLJB9oEN4nAyR48Sb1SVTluI`.

Опубликованы [backend PR #1](https://github.com/Valerochka1337/ValerochkaGymBackend/pull/1)
и [Android PR #37](https://github.com/Valerochka1337/ValerochkaGym/pull/37).
Первый backend CI успешно выполнил проверки, тесты, сборку JAR и Docker-образа:
[run 34153542540](https://github.com/Valerochka1337/ValerochkaGymBackend/actions/runs/34153542540).
Main защищена обязательным `Backend checks`, актуальной базой и изменениями через PR.
PR не публикует образ и не получает production-secrets. Первый полный CD-прогон
с публикацией GHCR и обновлением сервера состоится после слияния backend PR в main.

## Наблюдение

Контейнерные логи ограничены 3 × 10 MB на контейнер. Таймер Certbot продлевает существующий
сертификат. Health можно проверять любым внешним uptime-сервисом; сервис и получатель
уведомлений пока не выбраны. Ошибки backup видны в systemd journal. Полноценный внешний
мониторинг и off-host backup требуют предоставления соответствующих настроек.

### Диагностика AI в админке

`GET /admin/api/ai-diagnostics` доступен только через административную cookie-сессию;
пользовательский bearer-токен доступа не даёт. Ответы не кэшируются.

Админка показывает БД, агрегаты очереди, до 200 запусков за 24 часа и до 64 последних
событий каждого запуска. `droppedEvents` явно показывает усечение. `process` содержит
случайный ID процесса, время запуска и commit SHA из `BUILD_REVISION` образа (либо `unknown`
при локальной сборке). ID диагностического запуска не является ID запроса или аккаунта.

События содержат только фиксированные `site`, `reason`, `tool`, номер раунда, путь поля
из схемы и ограниченные числа `actual/minimum/maximum`. Длительности плана — секунды,
размеры — байты; события `ROUND_BUDGET` и `TOOL_CALL_BUDGET` содержат настроенный лимит
в `actual`. Например, `DURATION_TOO_SHORT: actual=45 minimum=2160 maximum=2700`
показывает конкретное нарушение. `UNKNOWN_PATTERN/UNKNOWN_CANDIDATE` отличаются от
ошибки формата аргументов. `FINALIZATION_MISSING` означает отсутствие успешной проверки
плана, `FINAL_PLAN_MISMATCH` — отличие финального плана от проверенного.

`TOOL_COMPLETED` означает только возврат ответа инструментом. `PLAN_REJECTED` и предшествующая
причина показывают отказ предметной проверки; `PLAN_ACCEPTED` — успешную проверку.
Если модель исправила план, весь запуск остаётся `SUCCESS`, а история отказа сохраняется.
Правила генерации, лимиты и публичные коды ошибок этим наблюдением не меняются.

По завершении попытки сервис пишет одну строку `AI_DIAGNOSTIC {json}` в stdout. Она содержит
ID запуска/процесса, commit SHA, итог, счётчики и те же ограниченные события. В ней нет
названия модели, текста исключений, стека, имён тренировок, exerciseId, переписки, промптов,
тел ответов, аргументов инструментов, аккаунтов и секретов. Административная память всё
ещё очищается при перезапуске, а Docker json-file — при удалении контейнера. Это не
долговременный аудит. Не перезапускайте сервер между воспроизведением и сбором отчёта.

### Проверка инфраструктуры и журналов

Ручной GitHub workflow `Production diagnostics` запускается только с `main` и production
секретами. Параметр `hours` ограничен значениями `2`, `6`, `24` (по умолчанию 6).
Workflow ничего не развёртывает и не перезапускает. Он читает:

- readiness, состояния backend/PostgreSQL, OOMKilled, restart count, время старта,
  image ID и commit SHA без вывода environment;
- доступную память/swap, load average и свободное место на файловой системе `/opt/valerochkagym`;
- до 5000 строк текущих Docker-логов каждого сервиса за выбранное окно и последние 20
  структурных AI-запусков; неизвестные поля и значения отбрасываются повторно;
- текущие Nginx access/error logs и ротации `.1`, `.2.gz`, `.3.gz`: фиксированные группы
  маршрутов, HTTP-статусы, ошибки по минутам UTC, таймауты/обрывы upstream;
- до 2000 строк kernel journal: количество упоминаний OOM, без исходного текста.

Для каждой ротации отмечается доступность/усечение. Предел чтения — 32 MiB на файл,
включая распакованные gzip. Команды имеют таймаут 15 секунд. На стороне GitHub остаётся
только обезличенный JSON; IP, query strings, resource IDs и исходные логи не передаются.
Nginx error log без часового пояса трактуется в часовом поясе сервера.

Ограничения: Nginx может вернуть HTTP 200 для опроса фоновой задачи, внутри которой
состояние FAILED — HTTP-статусы не заменяют события планировщика. `restart_count` относится
только к текущему контейнеру и сбрасывается при его замене. `query_ok=false`, отсутствующая
ротация и чистая выборка не доказывают отсутствие исторического сбоя. Удалённый контейнер
и незаписанные старым кодом причины восстановить этим workflow нельзя.

Порядок диагностики: развернуть проверенную версию, повторить генерацию, сохранить время
и отчёт админки, затем запустить workflow на том же временном окне. Сопоставлять `runId`,
commit SHA, время старта контейнера и события. Для точной диагностики Android USB не требуется.

## Перенос общего каталога

Для перехода используйте отдельную [инструкцию каталога](catalog-transition.md).
Liquibase создаёт структуру с выключенной активацией; `scripts/migrate-catalog.sh check`
проверяет перенос, `apply` выполняет его атомарно после backup и подготовки Android.
