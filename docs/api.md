# Протокол API

Полный список endpoints и моделей: [OpenAPI](openapi.json).
Кроме `/v1/auth/*`, запросы требуют `Authorization: Bearer <accessToken>`.
Владелец определяется только сессией. Ошибки: `{code,message}`.

## Запись и повтор запросов

`POST /v1/sync`: `{operationId,changes:[{kind,id,baseRevision,deleted,payload}]}`.
`operationId` и `id` — UUID, `baseRevision=0` для новой записи; иначе последняя известная
серверная версия объекта. `deleted=true` требует `payload=null`.
Ответ `{revision}` присваивается всем записям пакета. Пакет до 1000 изменений атомарен.

Повтор точного запроса с тем же operationId возвращает прежний результат. Повтор operationId
с другим содержимым — 409. Устаревшая baseRevision — 409 `revision_conflict`, без частичных
изменений. Клиент сохраняет локальную правку, читает актуальные версии и разрешает конфликт.
После изменения payload используется новый operationId. Часы устройства и Android updatedAt
не выбирают победителя конфликта.

## Чтение

- `GET /v1/sync` → `{revision,records:[{kind,id,revision,deleted,payload}]}`.
- `GET /v1/sync/changes?after=0&limit=200` → `{revision,records,nextCursor}`.
- `GET /v1/records/{kind}?offset=0&limit=100` → живые записи.
- `GET /v1/records/{kind}/{id}` → живая запись либо 404.

Пока nextCursor непустой, передавать его в следующий запрос с **тем же after**.
После последней страницы сохранить revision последнего ответа как новый after.
Курсор `(revision,kind,id)` не пропускает записи одного пакета с одинаковой revision.
При параллельном обновлении объект может встретиться повторно в более новой версии.
Это выдача последних состояний, не история каждой промежуточной правки. Tombstone
не очищаются в MVP. Чтение и запись сериализуются блокировкой строки владельца.

## Агрегаты payload

| kind | Поля |
|---|---|
| exercise | name, muscleGroup, type, isCustom, updatedAt, needsMuscleMapReview, equipmentRequirementState, muscles, equipmentIds |
| gym | name, updatedAt, inventoryConfigured, exerciseIds, equipmentIds |
| routine | name, note, updatedAt, gymIds, exercises |
| workout | name, note, routineId, startedAt, finishedAt, gymIds, exercises |
| measurement | measuredAt и nullable-показатели замеров Android |
| schedule | routineId, dateTimeMillis, calendarEventId |

Все ссылки — UUID, время — Unix epoch в миллисекундах. routineId и finishedAt у тренировки
nullable. equipmentRequirementState — KNOWN или UNKNOWN; KNOWN с пустым списком явно
означает отсутствие требований. muscles — `{muscle,contribution}`, contribution 100/50/0.

`routine.exercises`: `{exerciseId,position,restSeconds,plannedSets}`.
`plannedSets`: nullable `{weightKg,reps,durationSec,speedKmh,inclinePct}`.
`workout.exercises`: `{sectionId,exerciseId,position,sets}`.
`sets`: поля plannedSets плюс `{setIndex,isCompleted,completedAt}`.
Повторяющееся упражнение в разных sectionId не объединяется; sectionId уникален в аккаунте.

[Android fixture](../src/test/resources/android-snapshot.json) содержит полный пример
тренировки и nullable-замера. Сервер отклоняет неизвестные поля, нечисловые/бесконечные
значения и некорректные ссылки. Все связанные объекты должны существовать в итоговом
состоянии одного аккаунта; их можно создать одним пакетом. Для программ и активных тренировок
проверяется оснащение выбранных залов; завершённая история от текущего оснащения не зависит.

## Первичный перенос

1. Сохранить SQLite-копию. Нужные записи только из Sheets вернуть в Room старой версией
   приложения до обновления: новый backend к Google Sheets не обращается.
2. Войти, подтвердив сохранение локальных тренировок в этом аккаунте.
3. Клиент сопоставляет UUID, локальную правку, baseline и серверную версию.
4. Непересекающиеся изменения объединяются. При конфликте выбрать локальную или серверную
   версию; до выбора локальные данные сохраняются и могут быть экспортированы.
5. Проверить статус синхронизации и восстановление на чистой установке.

Токены не попадают в SQLite-экспорт. Excel-экспорт не входит в MVP.

## Общий каталог и протокол 2

Публичный `/v1/catalog`, ETag, архивы, `catalogRevision`, ошибки обновления клиента и
каталога описаны в [контракте перехода](catalog-transition.md). Личные sync/records
не включают перенесённые стандартные записи. Актуальная схема — `openapi.json`.

## Локальные календарные планы — capability `calendar-plans`

Возможность включается отдельно от существующего `X-Gym-Sync-Version: 2|3`.
Клиент передаёт `X-Gym-Capabilities: calendar-plans` для `GET/POST /v1/sync`,
`GET /v1/sync/changes` и обоих вариантов `GET /v1/records/*`. Заголовок допускает
список через запятую; сервер возвращает в `X-Gym-Capabilities` только пересечение
с поддержанными возможностями. Поддерживаются `calendar-plans`, `annotated-workout-writes`, `exercise-hint`, `profile`; ответ содержит только запрошенное пересечение.
Неизвестные capability игнорируются. Клиент считает отсутствующий/пустой ответ
отсутствием поддержки и сохраняет неподдерживаемые локальные данные и outbox.

Без принятых дополнительных capabilities сервер показывает только прежние шесть kinds: snapshot,
changes (фильтр **до** пагинации и построения курсора), список records и поиск по ID
одинаково скрывают календарные записи, включая tombstone. Список скрытого kind пуст,
поиск отдельной скрытой записи возвращает 404. Общая revision остаётся revision
аккаунта. POST с любым новым kind без capability возвращает 426 `capability_required`
до записи и до возврата сохранённого результата операции. Точный повтор с capability
возвращает прежнюю revision; прежний `operationId` с другим содержимым даёт 409.
Новый календарь не повышает минимальную версию аккаунта: прежние правила v2/v3,
поля legacy `schedule` и Live Coach сохраняются.

| kind | Поля payload |
|---|---|
| `calendar_plan` | `routineId: UUID`, `startsAtMillis: epoch-ms`, `timeZoneId: IANA ZoneId`, `legacyScheduleId: UUID?` |
| `calendar_rule` | `routineId: UUID`, `isoDay: 1..7`, `localTime: HH:mm`, `timeZoneId: IANA ZoneId`, `startLocalDate: YYYY-MM-DD`, `legacyRuleKey: String?` |
| `calendar_exception` | `ruleId: UUID`, `instanceKey: YYYY-MM-DDTHH:mm[ZoneId]`, `kind: CANCELLED|MOVED`, `movedAtMillis: epoch-ms|null` |

UUID новых календарных записей и ссылочные UUID — канонические строки в нижнем регистре. Неизвестные поля запрещены;
`legacyScheduleId` и `legacyRuleKey` можно опустить или передать null. Непустой ключ
legacy-правила ограничен 1024 символами. Ненулевые legacy-идентификаторы уникальны
среди живых записей соответствующего kind в аккаунте; ссылки на Google не передаются.
`routineId` ссылается на живую личную программу, `ruleId` — на живое правило того же
владельца. Все ссылки и уникальность проверяются в итоговом состоянии атомарного пакета.

`startsAtMillis` — фиксированный instant. Правило хранит локальное недельное время,
начиная с включительной `startLocalDate`; дата старта не обязана совпадать с `isoDay`.
Допустимы реальные ZoneId из базы временных зон, включая `UTC`, но не произвольные
смещения вроде `+03:00`. Новые даты ограничены `1970-01-01`…`2100-12-31` включительно:
instant плана проверяется после перевода в его зону, moved instant — в зону правила.
Дата старта и исходная дата ключа исключения имеют тот же диапазон. Legacy `schedule`
не получает эти ограничения и сохраняет прежний payload.

Ключ исключения содержит **исходные** дату, время и зону правила; дата соответствует
дню недели и не раньше старта. `CANCELLED` требует явного `movedAtMillis: null`, `MOVED`
— целого instant. UUID записи исключения вычисляется Java-совместимым
`UUID.nameUUIDFromBytes` от UTF-8 строки
`ValerochkaGym.calendar-exception:v1:<ruleId>:<instanceKey>`; таким образом одна пара
правило/ключ имеет ровно одну идентичность. DST gap/overlap не меняет исходный ключ.
Разворачивание повторений выполняет клиент: gap — первый допустимый instant после
пропуска, overlap — более раннее смещение. Сервер не создаёт внешние события.

Изменение дня недели, времени, зоны или старта правила требует нового UUID и атомарного
удаления старого правила с его исключениями. Изменение только `routineId` сохраняет
исключения. Удалённое правило нельзя восстановить с прежним UUID; для повторного создания
нужен новый UUID, даже если расписание совпадает. Удаление родителя без удаления живых ссылок отклоняется; все связанные
удаления можно отправить одним пакетом в любом порядке. Программа с живыми планами
или правилами также не удаляется. Завершённые тренировки календарь не изменяет.

Канонический межплатформенный [fixture](../src/test/resources/cal01-sync-contract.json)
проверяется HTTP-набором вместе с изоляцией владельцев, legacy-проекцией и ledger.

## AI drafts v1 — авторизованный серверный AI

`GET /v1/ai/status` возвращает `{schemaVersion:1,availability,actions}`. При неполной либо
выключенной конфигурации availability=`UNCONFIGURED`, actions=[]; иначе `AVAILABLE` и
`EXERCISE_DRAFT`, `INBODY_PHOTO_DRAFT`, `CALENDAR_DRAFT`. Это наличие конфигурации, не проверка live provider.
AI не меняет readiness приложения и не повышает sync protocol/capabilities.

- `POST /v1/ai/exercise-drafts`: `{requestId,expectedRevision,expectedCatalogRevision,description}`.
- `POST /v1/ai/inbody-drafts`: `{requestId,expectedRevision,expectedCatalogRevision,image:{mediaType:"image/jpeg",base64}}`.
- `POST /v1/ai/calendar-drafts`: строгое UTF-8 JSON-тело из
  [контракта calendar AI](../src/test/resources/calendar-ai-contract.json). Оно связывается с
  SHA-256 исходных байтов и `requestId`; успешный повтор возвращает первоначальный PENDING
  proposal без нового provider-вызова. Сервер не передаёт provider веса, историю или health/InBody.
- Ответ: `{requestId,context:{revision,catalogRevision},result}`. Exercise result —
  `{kind:"EXISTING",exerciseId:UUID}` либо `{kind:"NEW",name,type,muscles:[{muscle,contribution}]}`.
  InBody result — `{kind:"INBODY",draft}` с точными nullable полями и пятью сегментами из
  [контракта v1](../src/test/resources/ai-contract-v1.json).

UUID канонические lowercase; revisions неотрицательные. Описание 1…2000, имя 1…200;
типы/мышцы известные, мышцы не повторяются, contribution 0/50/100 и хотя бы один ненулевой.
Числа конечные и неотрицательные; null означает нераспознанное, не ноль. Полностью пустой
InBody draft, неизвестные поля/единицы или неразрешимый exercise UUID отклоняются.
JPEG ≤6MiB decoded / ≤8MiB base64, размеры 1…3072, общий поток запроса ≤10MiB.
Фото проверяется без disk cache/temp files; пользователь подтверждает передачу в Android.

Перед действием клиент завершает sync и передаёт ACK revision и проверенную catalogRevision.
Сервер под короткими catalog→owner locks сверяет revision, собирает только разрешённый контекст,
отпускает транзакцию, вызывает provider, проверяет результат и повторно проверяет обе ревизии
и текущую сессию. Exercise context содержит live owner/public catalog; InBody — только выбранное
фото и extraction schema. История здоровья, заметки и другие владельцы не включаются.
Изменения/архивация/отзыв сессии во время запроса приводят к отказу, а не устаревшему draft.
Ни один AI endpoint не сохраняет records, head, operations, prompt, фото или ответ. requestId —
корреляция попытки, не exactly-once/idempotency promise; автоматических retry нет.

Без конфигурации: 503 `ai_unavailable`; устаревшие revisions: 409 `ai_context_stale`;
контекст >1MiB: 409 `ai_context_too_large`; malformed provider output: 502 `ai_invalid_response`;
45s timeout: 504 `ai_timeout`; заняты два provider slots: 503 `ai_busy`; upstream failure:
503 `ai_unavailable`; некорректное фото: 400 `invalid_image`; byte limit: 413 `payload_too_large`.
Ошибки не содержат upstream body, ключ, модель, URL, prompt или значения здоровья.

Серверный адаптер использует фиксированный HTTPS `/v1/chat/completions`, без redirects,
stream/store/tools/model fallback и `/models`. Запросы используют strict JSON schema,
`n=1`, `max_completion_tokens=2048`; допускается ровно один completed assistant choice без
refusal/tool calls. Connect timeout 5s; response ≤256KiB, весь вызов ≤45s и максимум два
параллельных provider exchange. Отмена пытается остановить HTTP и не обещает отмену обработки
или стоимости у провайдера. Совместимость images/strict schema зависит от операторской модели.
[Create Chat Completion](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create),
[Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).

## Заметки подходов и личные подсказки

`annotated-workout-writes` открывает необязательный `workout.exercises[].sets[].note`:
строка, уже обрезанная по краям, максимум 2000 Unicode code points. Отсутствие означает
пустую строку; null и нестроковые значения запрещены. Прежний `workout.note` сохраняет
лимит 10000 и прежнюю форму. Протокол синхронизации не повышается.

Без capability все GET sync, changes, records list и single удаляют только поле set.note.
Фильтрация и проекция выполняются до построения курсора. POST без capability с новой
непустой заметкой или поверх сохранённой непустой заметки (включая tombstone) возвращает
409 `annotated_workout_requires_capability`; весь пакет откатывается без новой ревизии
и записи операции. Совместимые ресурсы продолжают синхронизироваться.

`exercise-hint` открывает личный kind `exercise_hint`. Его id — канонический lowercase UUID
упражнения; payload строго `{text,updatedAt}`: непустой trimmed text до 2000 Unicode code
points и неотрицательные целые UTC epoch milliseconds. Идентичность подсказки ограничена
авторизованным владельцем, даже для STANDARD упражнения; каталог не меняется. Новая или
изменённая живая подсказка требует собственного либо публичного живого неархивного упражнения.
Уже существующая подсказка сохраняется при последующем удалении упражнения, tombstone разрешён.
Без capability kind скрыт до пагинации; list пуст, single 404. POST с этим kind без capability
возвращает 426 `capability_required` до ledger, включая точный повтор.

Клиент хранит принятые capabilities по владельцу. При отсутствии/понижении ответа он
сбрасывает кэш проекции, сохраняет неподдержанные записи и pending bytes; при первом
принятии выполняет полный refresh, не продолжает старый курсор. Разрешён точный повтор
уже отправленной операции; подтверждение соответствует целому отправленному пакету.


## Базовый профиль — capability `profile`

Один личный `profile` на аутентифицированного владельца. ID и payload `syncId` равны
`UUID.nameUUIDFromBytes(UTF8("ValerochkaGym.profile.v1:" + authenticatedOwnerUuid))`;
оба UUID имеют каноническое lowercase написание. Owner задаётся сессией, поле ownerId
в payload не принимается. Нельзя создать второй profile с альтернативным UUID.

Все 11 полей обязательны: `schemaVersion: 1`, `syncId`, `updatedAt` (неотрицательный int64,
UTC epoch millis), `trainingGoal`, `sex`, `birthDate`, `experienceLevel`,
`plannedSessionsPerWeek`, `preferredSessionDurationMinutes`, `manualConstraints`, `equipmentIds`.
Nullable значения передаются явным JSON null. Goal: STRENGTH/MUSCLE_GAIN/FAT_LOSS/
GENERAL_FITNESS/ENDURANCE/OTHER; sex: FEMALE/MALE/PREFER_NOT_TO_SAY; experience:
BEGINNER/INTERMEDIATE/ADVANCED. Дата — реальная ISO YYYY-MM-DD, 1900-01-01…сегодня UTC.
Частота 1…7, длительность 10…240 минут. Ограничения — trimmed непустая строка до 2000
Unicode code points или null. Equipment — уникальные, сортированные canonical catalog IDs;
пустой список означает отсутствие предпочтения.

`deleted=true` всегда возвращает 400 до ledger/revision, включая клиента без capability.
Очистка — обычное обновление с пустым snapshot. POST profile без capability возвращает
426 capability_required; чужой/альтернативный ID — 400; stale baseRevision — 409
revision_conflict. Клиент принимает актуальный серверный singleton при конфликте, не
создаёт второй профиль. Смешанный пакет атомарен. Все чтения (`/sync`, `/sync/changes`,
`/records/profile`, `/records/profile/{id}`) скрывают профиль без capability до пагинации;
отсутствие record в таком ответе не является удалением. При потере согласованной capability
клиент сохраняет локальный профиль, baseline и outbox до повторного согласования/full refresh.
Legacy v2/v3, measurements, CAL-01 и заметки сохраняют прежний формат.

Exercise AI читает только сохранённый профиль текущего владельца вместе с каталогом под
catalog→head locks и проверкой expectedRevision. Provider получает typed nullable профиль
с возрастом в полных годах, вычисленным сервером на текущую UTC дату, без birthDate,
syncId, ownerId и измерений. Unknown не заполняется догадками. InBody не получает профиль.
После provider call выполняется прежняя повторная проверка ревизии и сессии.
Контракт: `src/test/resources/basic-profile-sync-contract.json`, SHA-256
`1bec288ad8d841efaf645af13ac5ea1cbe2b53c841846589c8101cfe3f524ed6`.

## Ручные медицинские записи: health-ledger-v1

Выделенные `/v1/health-ledger/*` и `/v1/health-ai-disclosure` требуют Bearer и
`X-Gym-Capabilities: health-ledger-v1` до чтения тела. Без capability —
`426 capability_required`. Записи `health_report`, `health_observation`,
`health_restriction` не входят в generic sync/records и не копируют `measurement`.
Точные формы, пределы строк, decimal/date правила и векторы фиксированы в
`src/test/resources/manual-health-contract.json`.

- `POST /v1/health-ledger/operations`: `{operationId,versions,heads}`; максимум 5MiB
  UTF-8,500 версий и500 head intents. Неизменяемые версии получают `serverSequence`
  и отдельный `healthRevision`; APPLIED head CAS также получает `healthRevision`.
  STALE сохраняет принятую версию и возвращает текущую голову. Exact operation
  replay возвращает сохранённые байты результата; иные байты того же operationId
  дают 409 `health_operation_reused`. Неравная immutable version — 409
  `health_version_collision`. Некорректные ссылки — 400 `health_reference_invalid`.
- `GET /v1/health-ledger/snapshot?limit=1..500&pageToken=...` и
  `GET /v1/health-ledger/changes?after=...&limit=1..500&pageToken=...`: общий поток
  immutable version/head events ограничен watermarkH; snapshot использует heads
  из истории наH. Только финальная страница выдаёт `commitCursor`. Page tokens
  живут 24часа; committed cursors не имеют TTL пока история сохранена. Неверный
  владелец, purpose, подпись, утраченное состояние/ключ — 410 `health_cursor_expired`.
- `GET /v1/health-ai-disclosure`: отдельная квитанция; отсутствующая —
  `{revision:0,noticeVersion:0,enabled:false,recordedAtEpochMs:0}`.
- `POST /v1/health-ai-disclosure`: `{operationId,baseRevision,noticeVersion,enabled}`,
  максимум 4096байт, raw-byte replay. Stale base — 409 `consent_revision_conflict`;
  changed-byte reuse — 409 `consent_operation_reused`.

InBody требует `X-Health-AI-Disclosure-Revision` текущей включённой квитанции
noticeVersion 1 до чтения изображения. Проверки повторяются перед/после provider
и на ASYNC dispatch; отзыв даёт 403 `health_ai_consent_required` с отбрасыванием
результата. DB locks не удерживаются через provider HTTP. Exercise AI не зависит
от этого согласия.

Health storage limits: `gym.health.max-bytes=209715200` и
`gym.health.max-versions=100000` по умолчанию. Считаются логические UTF-8 bytes
версий, событий/истории и raw/result operations, без SQL/index overhead.
Проверка всей операции атомарна до выделения событий; exact replay бесплатен.
Превышение — 409 `health_account_limit`. Это storage quota, не AI usage limit.

## Live Coach

Authenticated `GET /v1/ai/coach-models`, `POST /v1/ai/coach-turn` and `POST /v1/ai/coach-turn/stream` provide a bounded stateless tool-calling exchange. Request/response contract, limits and model settings: [Live Coach contract](../vibe/live-coach-plan.md). Workout operations execute only in the Android application after local validation and confirmation. These routes do not require a synced active workout or read health/profile records.

### Background calendar preparation

`POST /v1/ai/calendar-draft-jobs` accepts the existing complete `CalendarDraftRequest`
plus optional `replacesRequestId: UUID?` and `replacesRequestIds: UUID[]` (at most 1000).
The response is HTTP 202 `{requestId,state,errorCode,result}`. `result`, when present,
is the existing typed `CalendarDraftResponse`; no program or calendar event is created.
The exact same request bytes and UUID must be retried after a lost acknowledgement;
changing bytes for a known UUID returns `ai_request_conflict`. A new intentional
calculation uses a new UUID and includes all locally superseded/in-flight ancestors.
Do not truncate that lineage silently. Tombstones prevent reordered delivery from
restoring older work, including cancellation before its POST arrives.

`GET /v1/ai/calendar-draft-jobs/{requestId}` returns the same response envelope.
States are `QUEUED`, `RUNNING`, `READY`, `FAILED`, `SUPERSEDED`, `STALE`, `EXPIRED`.
Only `READY` is current and eligible for the existing explicit proposal approval.
A prior typed result remains available on stale/superseded jobs for viewing; approval
rejects it as `proposal_stale`. Status checks revalidate owner/catalog revision and date.
`DELETE /v1/ai/calendar-draft-jobs/{requestId}` returns 204 and durably supersedes the
job (or tombstones a not-yet-delivered UUID). An offline client must retain an explicit
cancellation for delivery and immediately prevent local application. Calendar preparation
uses the existing training context, including legacy sync measurement weight; it does
not read the separate health ledger and is not tied to its health-only disclosure toggle.

All operations require a live authenticated owner session. Work binds the accepting
session, so revoked/expired sessions fail safely; no access token is stored. The queue
stores validated user intent, lease metadata, and typed validated results only, never
provider prompts, captured context or raw outputs. One execution runs per backend
instance. The scheduler checks every 5 seconds (`gym.calendar-jobs.poll-ms`), recovers
expired 90-second leases after restart and allows at most three executions per job.
A lease fence and proposal/result transaction prohibit late publication and duplicate
proposals. The original synchronous calendar endpoint remains available for old clients.
Clients should use bounded polling/WorkManager backoff; accepting a job does not promise
immediate execution. Past requested dates become `EXPIRED`, never silently rescheduled.

## Промпт Live Coach

`GET /v1/ai/coach-prompt` (Bearer) возвращает `{ "prompt": "..." }`.
Текст хранится в `ai_settings.coach_prompt`; сервер кэширует его на 5 минут на каждом экземпляре.
Редактирование: `coachPrompt` в существующем `PUT /admin/api/ai-settings` с проверкой `revision`.
Пустой текст, NUL и более 16000 символов отклоняются; переносы строк сохраняются.
Сначала развернуть сервер с миграцией, затем Android. Старые клиенты продолжают использовать встроенный текст.
