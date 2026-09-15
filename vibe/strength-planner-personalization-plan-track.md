# Strength planner personalization — итоговый трекер

**Последующее обновление:** main и sharing интегрируются по strength-sharing-integration-plan.md. Backend база a330cba, миграция персонализации020 после sharing019. Итоговые проверки см. strength-sharing-integration-plan-track.md; ниже сохранены результаты до интеграции.

## Статус

Интеграция персонализации STRENGTH завершена локально. Финальный backend check bootJar прошёл: 256 тестов, 0 failures/errors/skipped, Spotless и JAR успешны. Настройки и effort теперь участвуют в серверном выборе кандидатов и компактном контексте AI; ответ проверяется на включение основного упражнения.

Пользователь 2026-09-15 ответил «продолжай. я согласен» на конкретный вопрос о передаче настроенному AI-провайдеру через backend последних веса/повторов, объёма/частоты за7/28дней и оценки усилия. Разрешение получено. Первые изолированные исполнители не смогли подтвердить его автоматической проверке; primary-context правки приняты. Обходов границы, живых вызовов AI, коммитов, push, PR, merge или публикации не было.

## Ветки и база

- Android: `/Users/raul/.codex/worktrees/2845/ValerochkaGym`, `feat/strength-planner-personalization`, база `8cf37b5`.
- Backend: `/private/tmp/ValerochkaGymBackend-strength-personalization`, `feat/strength-planner-personalization`, база/актуальная origin/main `253c81a`.
- Android: один ранее сделанный инкремент `70/1.3.62 → 71/1.3.63`; при продолжении Android-код и версия не менялись.
- Android origin/main за время работы обновилась до `b6a3717` с отдельным экраном аналитики и версией71/1.3.63. Перед будущим commit/push нужно согласовать базу и сделать итоговую версию выше main. Чужие изменения не импортировались; merge/rebase не выполнялись.
- Отдельный rationale fix `6be6994448bee4dc86ba1a068b2e753b1ef23018` не перенесён. Это зависимость подготовки публикации, а не незавершённая часть персонализации. Runtime output schema и PlannerExplanationFactory не менялись; общие CalendarAiService и тестовый PlannerProviderFixture нужно согласовать при будущей интеграции.

## Задачи и критерии

| Задача | Статус | Доказательства |
|---|---|---|
| T-001 / AC-005–009 | готово | Зафиксированы sync и компактный AI-контракт, согласовано раскрытие данных. |
| T-002 / AC-003–004 | готово | Рукописная Room28→29, schema29, incremental/full-path migration tests. |
| T-003 / AC-001,004–005 | готово | Независимые optional records, реальное HTTP capability advertisement, cap-off exact bytes, cap-on roundtrip, owner/session guards. |
| T-004 / AC-001–003 | готово | STRENGTH picker до5, HIGH/NORMAL, атомарное сохранение, effort/clear/recreation, fontScale2 semantics и GymHaptics. |
| T-005 / AC-006–008 | готово | Backend валидация/каскад/replay, hard eligibility → ranking до24 → обязательный focus; bounded latest до29, effort, существующие валидаторы и перенос веса. |
| T-006 / AC-007–009 | готово | calendar-strength-v1 / strength-compact-v1, синхронные fixtures/digest, точный serialized fixture, provider boundary tests. |
| T-007 / AC-001–009 | готово | Независимые review/coverage audit; P1 с лишней историей исправлен и повторно одобрен. Дополнены owner/revision/exclusion/fallback regressions. |
| T-008 / AC-009 | готово с ограничением release signing | Android прежние успешные gates сохраняются; backend check/bootJar — pass,256/256 тестов. Release signing inputs отсутствуют. |

## Поведение backend

- Новая ветка работает только для STRENGTH. Остальные цели сохраняют предыдущую сериализацию/выбор и исторический перенос веса.
- Сначала ограничения архива/оборудования/исключений; затем замороженные HIGH80/NORMAL40, давность, compound allowlist и штраф повторов. Последняя тренировка считается недавней только до7дней. При достаточном количестве альтернатив повтор исключается, иначе остаётся со штрафом и серверным reason code.
- Основное упражнение должно присутствовать в ответе. Модель не может выбирать за пределами списка или задавать вес. Продолжают действовать ограничения типа подходов, количества, длительности и актуальности revision.
- История последних совместимых WORK/null подходов ищется отдельно от обычных трёх старых родителей. SQL возвращает ограниченный набор scalar facts, а не целиком старые workout payload. Проверки владельца, завершённости, времени и формы стоят до выбора последней записи.
- Фактические поля имеют приоритет перед legacy; actual null не заменяется планом. Legacy-числа не выдаются AI; прежний серверный fallback веса сохраняется.
- Окна7/28дней скользящие, закрытые и пересекающиеся. Volume=kg×reps использует только фактические вес и повторы. Частота считает уникальные тренировки, повторное чтение latest не добавляет объём.
- STRENGTH-контекст исключает прежние подробные history/mass, observation pointers и ненужные IDs в opt-in notes. Компактные повторные упражнения представлены только выбранными exercise IDs. Effort — явный nullable enum, без выводов о здоровье/восстановлении.

## Проверки

- Android `./gradlew :app:testDebugUnitTest`: 1606 тестов,1605 passed,1 skipped,0 failures. Пропущенный существующий EmulatorV11CopyMigrationTest требует VALEROCHKA_GYM_DB_COPY.
- Android `./gradlew :app:assembleDebug`: pass. Код Android при продолжении не менялся; повторный полный прогон не запускался.
- Android `./gradlew :app:assembleRelease`: ранее остановлен validateSigningRelease — отсутствуют RELEASE_KEYSTORE_FILE, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD. R8/release packaging не заявлены проверенными.
- Backend targeted: StrengthPlannerIntegrationTest, StrengthPlannerFactsTest, CalendarPlannerContextTest, CalendarAiContractTest, CalendarAiCaptureIntegrationTest — pass.
- Первый финальный backend прогон: 256 тестов, единственный сбой — дублирующий старый SHA в AiIntegrationTest. SHA обновлён к новой версии контракта; повторный ./gradlew check bootJar — PASS: 256 тестов, 0 failures/errors/skipped.
- Лог финального backend прогона: `/private/tmp/strength-resumed-final.log`. Targeted: `/private/tmp/strength-resumed-targeted.log`.
- git diff --check — pass в обоих репозиториях.
- Скриншоты не создавались/не анализировались; реальных запросов AI не было, только fake provider и синтетические данные.

## Review и исправления

Ранее исправлены stable UUID, stale choices, атомарные profile+keys, post-write owner guard, сохранение черновиков, captured effort target, actual clear, touch bounds/fontScale2, archived new key rejection, парные/cascading effort tombstones, cap negotiation.

При интеграции дополнительно исправлены SQL bind/type mistakes, фильтрация actual volume, точные rolling windows, data minimization всего provider context. Независимый reviewer повторно одобрил исправление; tester подтвердил покрытие live excluded HIGH key, exact repeat fallback, provider-time revision change, другой владелец, несовместимые/некорректные старые подходы. P2 hardening (provider.calls==1 в race-тесте) выполнен.

## Артефакты и публикация

- Sync fixture SHA-256: `06b689084f63ff717951dfd3b82ff599120efe640dc1014e61d84d8a76eff15c` — одинаков в Android/backend.
- Strength context fixture SHA-256: `f4e3e6713c40a9df161b5b58714b031edb57e93951358e5cbf002f93f6568da1` — одинаков в src/test/resources и vibe/contracts backend.
- Android APK: `/Users/raul/.codex/worktrees/2845/ValerochkaGym/app/build/outputs/apk/debug/app-debug.apk`.
- Backend JAR: `/private/tmp/ValerochkaGymBackend-strength-personalization/build/libs/app.jar`.
- Планы Android находятся в локальной ignored папке vibe. Все изменения остаются незакоммиченными.
- Перед публикацией: согласовать отдельный rationale fix и Android main/version; backend разворачивается первым. Публикация этой задачей не выполняется.
