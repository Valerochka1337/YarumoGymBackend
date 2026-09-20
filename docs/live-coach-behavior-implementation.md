# Live Coach: состояние внедрения

Реализация от 2026-09-19 по двум архитектурным документам. Реализован первый сквозной backend-срез;
полная целевая архитектура и Android-контракт ещё не завершены.

## Реализовано

- Чистый `CoachBehaviorPolicy`: независимые lifecycle/phase/online, явные concern,
  решения CONCERN/EVALUATE/DEFER/SILENT с reasonCode. UNKNOWN не становится IN_SET.
- Миграция 029: durable state, schema/state/policy version, режим сессии,
  упорядоченный журнал входов и решений. Запись в транзакции существующего session admission;
  повтор eventId возвращает прежний ACK, не повторяет переход.
- Concern обходит дедлайн, последний подход, открытую карточку, выключенную инициативу
  и завершение тренировки. Шаблон публикуется в workout stream без LLM и без run.
  Открытая жалоба отменяет обычные автоматические задачи, в том числе через fencing.
  Исчезновение поля/молчание не закрывает жалобу.
- Явное разрешение жалобы через `resolvedConcernKeys` в PUT session. Исходные факты
  сохраняются; из производного planning view удаляются только разрешённые сигналы.
  Дедупликация: set_id + тип жалобы; для повторного эпизода в том же подходе понадобится
  отдельный concernId в следующей версии контракта.
- AutoregulationRecommendation содержит reasonCode/evidenceKey. В ENFORCE готовые
  `confirmed_harder_adjustment` и `time_capacity` проходят общий валидатор и публикуются
  в транзакции напрямую, без AI task. Старый run/proposal/receipt контракт сохранён;
  executor также умеет восстановить готовый расчёт из checkpoint.
- Миграция 030: durable questions, proposals и идемпотентный ledger ответов/receipts.
  Неясная просадка результата создаёт один вопрос с заранее рассчитанной веткой HARDER.
  PLANNED_EFFORT/INTERRUPTED сохраняют план; HARDER повторно проверяет текущие зависимости
  и сразу возвращает preview. Ответ на факт не применяет операции.
- Вопросы привязаны к setId, version, baseRevision, dependencies и сроку. Повтор answerId
  возвращает прежний результат; изменённый payload конфликтует. Устаревший вопрос
  возвращает терминальный STALE/EXPIRED без изменения тренировки. Ответ хранится с
  источником USER_ANSWER и доступен модели как behavior_facts.
- Proposal существует независимо от run. Поздний APPLIED подтверждает локальное
  применение даже после expiry; нужны version и resultRevision. Повтор receipt
  возвращает прежний ACK. Rejection сохраняется в общей памяти решений.
  Heartbeat/countdown не меняет dependencies; изменение baseRevision инвалидирует
  вопрос/предложение целиком. Topic/intervention keys подавляют повтор той же идеи.
- Автоматические tool calls не могут записать выдуманные результаты/выполнение/самочувствие.
- Ограниченный пул workers (4 по умолчанию), прежняя сериализация внутри тренировки,
  lease/fencing, SKIP LOCKED на account claim и приоритет ручных задач между сессиями. Очередь ручных сообщений ограничена 8; переполнение возвращает
  `429 coach_queue_full`, но повтор ранее принятого requestId возвращает прежний run.
- Диагностика с согласованным high-water sequence, последними 50 переходами,
  числом ожидающих задач и возрастом самой старой задачи.
- Playground: concern без run, replay/dedup, явное закрытие жалобы, фазы, пауза,
  просмотр policy state и timeline, вопросы/preview и durable outbox ответа/receipt. Это симулятор клиентского контракта.

## Включение

`gym.coach-behavior.mode=SHADOW` по умолчанию: переходы сохраняются, обычный арбитраж
остаётся прежним. Приоритетный concern действует в обоих режимах.
`ENFORCE` включает фазовые ограничения и быстрые расчёты для **новых** сессий;
режим закрепляется при первом behavior transition. В локальном playground по умолчанию ENFORCE
(переопределяется COACH_BEHAVIOR_MODE); новый сценарий явно начинается в READY. Не включать для клиентов,
которые не передают phase и не отображают событие concern.

Настройки пула: `gym.coach-runs.workers` (1–32),
`gym.coach-runs.max-pending-messages` (1–100).

`PUT /v1/coach/sessions/{workoutId}` сохраняет прежний контракт. Дополнительно:

- `snapshot.phase`: UNKNOWN / READY / IN_SET / RESTING / BETWEEN_EXERCISES;
- `snapshot.paused`, `snapshot.finished`, `snapshot.online`: явные состояния;
- `snapshot.reported_feelings` и `sets[].reported_feelings`: PAIN / TECHNIQUE_BREAKDOWN;
- `resolvedConcernKeys`: явный список ключей из `state.openConcerns`.

`GET /v1/coach/sessions/{workoutId}/behavior` возвращает `behavior`, `timeline`,
`interventions`, `queue`, `highWaterSequence`. Это диагностическая проекция, не полный snapshot для
retention recovery. Требуется обычная аутентификация, ответ `Cache-Control: no-store`.

SSE `concern`: schemaVersion, source=BEHAVIOR, sequence, eventId, stateVersion,
text, decision. У него **нет runId**. Клиент сохраняет отображённый eventId и курсор,
игнорирует дубли; закрытие жалобы не является согласием на изменение тренировки.

Structured endpoints:

- `POST /v1/coach/sessions/{workoutId}/questions/{questionId}/answers`:
  `{answerId, expectedVersion: 1, answer}`. Варианты: PLANNED_EFFORT,
  HARDER_THAN_EXPECTED, INTERRUPTED. Возвращает proposal либо no_change и статус вопроса.
- `POST /v1/coach/sessions/{workoutId}/proposals/{proposalId}/receipt`:
  `{receiptId, version: 1, status, resultRevision?, reason?}`.
  APPLIED требует resultRevision > baseRevision. Этот endpoint подтверждает факт
  локальной транзакции, а не выполняет её и не принимает предложение за пользователя.
- SSE `intervention`, `intervention_answer`, `intervention_receipt` используют тот же
  durable sequence; данные находятся в `result`, ссылки на run нет.

## Следующие этапы целевого плана

- Полная agenda с приоритетами/окнами доставки и объединением нескольких причин;
  расширение каталога QuestionPlanner за пределы причины просадки/прерванного подхода.
- Расширение lifecycle proposal: принятие/applying/reconciling, writer epoch и
  Android Room ledger + receipt outbox, клиентский guard актуальности.
- Manual advice publication guard, turnEpoch/interrupt, delivery outcomes;
  retry/backoff/jitter/Retry-After и повтор актуальной инициативы после сбоя.
- Provider-wide retry/concurrency budget,
  durable generation timers, полное snapshot/replay recovery и fault injection.
- Shadow-сравнение итоговых кандидатов старого и нового движка, а не только
  журнал фазовых решений; UX-квоты, реальные replay/evals и продуктовая проверка шаблонов.

APPLIED по-прежнему фиксируется только по receipt. Сервер не утверждает, что локальная
транзакция Android реализована или проверена в этом репозитории.

## Проверки

Добавлены сценарии concern со всеми конкурирующими gates, duplicate ACK/replay,
явного закрытия жалобы, ENFORCE IN_SET, admission limit, чистого reducer,
структурированного question → proposal → late APPLIED и повторов, stale question,
KEEP без согласия, нуля LLM/tasks для time conflict, запрета автоматической записи
фактов. JS-проверки охватывают отсутствие применения до отдельной кнопки,
receipt path/resultRevision, replay concern и восстановление после перезагрузки.
