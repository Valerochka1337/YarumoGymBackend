# Интеграция strength, main и sharing

Запрос пользователя: «синкани с main и с соседней доработкой по sharing (соседний чат codex)».

## Объём

Локально обновить текущие strength ветки до main и перенести стабильный sharing diff из задачи «Добавить шеринг программ по ссылке». Исходные sharing checkout не редактировать. Не выполнять push, публикацию или перенос отдельного rationale fix.

## Критерии и проверки

| AC | Условие | Проверка |
|---|---|---|
| I-001 | Android main99e2ca1 и backend maina330cba являются текущей базой; strength сохранён | HEAD, backups, diff и scoped review |
| I-002 | Sharing снимки совпадают со стабильными исходниками | manifests с SHA-256, source HEAD, подтверждение соседней задачи |
| I-003 | main Room28→29 сохранена, strength перенесена29→30 | KSP schema30, Migration28To29Test/29To30Test/1To30Test |
| I-004 | Импорт sharing не теряет strength-capability, локальные факты и очередь | BackendSyncTest combined regression |
| I-005 | Backend migration018remove/019sharing/020strength и новый IdentitySessionGuard совместимы | StrengthPlanner/RoutineShare tests, Liquibase full check |
| I-006 | Обе функции проходят общие gates и версия согласована | Android full unit+debug, backend check+bootJar; version74/1.3.66 |

## Порядок

1. Снимки исходных strength изменений и сохраняемые stash; fast-forward текущих веток до main.
2. Перенос стабильных sharing снимков трёхсторонним file merge; разрешение общих version/schema/digest/changelog участков.
3. Scoped независимый review, targeted migrations/sync/backend tests.
4. Один финальный full unit/debug Android и check/bootJar backend на стабильном diff.
5. Запись результата и сообщение соседней задаче. Release signing и live App Links/nginx остаются проверками будущего развёртывания.
