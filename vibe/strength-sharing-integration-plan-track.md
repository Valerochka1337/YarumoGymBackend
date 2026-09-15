# Трекер интеграции main + sharing

- Android база:99e2ca18d64039a7a74c3f6890da11c4fa19e46b; backend:a330cbad1bf21e7f4c058a5693864c7f4db057b4.
- Текущие ветки обоих репозиториев:feat/strength-planner-personalization.
- Sharing подтверждён стабильным соседней задачей01a0a601-8500-7b23-97ee-fee78e501e8e; Android24 пути, backend19 путей. Manifests/base/files в /private/tmp/strength-main-sharing-backup/sharing-android и sharing-backend. Исходные checkout не менялись.
- Предыдущие strength файлы и tracked patches сохранены в /private/tmp/strength-main-sharing-backup/android и backend. Stash с сообщением strength before main and sharing sync сохранены для восстановления; не удалялись.
- I-001/002:готово. Обе текущие ветки fast-forward доmain, обе feature diffs сохранены, sharing перенесён.
- I-003:готово. Room30, главная28→29 сохранена, strength29→30. KSP identityHash87c4957c9bcd49b486b3b25a0c8eae97. Все три migration tests прошли.
- I-004:готово. Shared receive-only capability дополнена strength; combined regression сохраняет EASY effort, исходные байты outbox и отсутствие upload при импорте. BackendSyncTest прошёл.
- I-005:готово, targeted и full check pass. 018remove→019sharing→020strength; старые отношения заменены main IdentitySessionGuard, fixture SHA пересчитан после main. Backend:234 теста,0 failures/errors/skipped; spotlessApply check bootJar прошли.
- I-006:Android full unit pass:1621 тест,1620 passed,1 skipped,0 failures/errors; assembleDebug прошёл; backend check и bootJar прошли. Версия74/1.3.66 = main72 + sharing + strength. Повторного отдельного feature increment не добавлено.
- Scoped reviewer:pass, замечаний нет. Проверены capabilities, миграции, IdentitySessionGuard, порядок Liquibase и fixture SHA. git diff --check обоих репозиториев прошёл.
- Release Android остаётся недоступен без signing inputs. Live App Links/assetlinks/nginx проверяются после размещения. Нет live provider calls, commit/push/PR/deploy.

- Проверка сохранности схем: main29.json byte-identical HEAD; schema30 добавляет только strength_planner_profiles, strength_planner_key_exercises и workout_efforts, удалённых относительноmain29 таблиц нет.
