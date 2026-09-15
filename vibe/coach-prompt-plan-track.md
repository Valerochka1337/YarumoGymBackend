# Статус переноса промпта Live Coach

- Реализованы миграция 016-coach-prompt.sql, GET /v1/ai/coach-prompt и кэш на 5 минут на экземпляр сервера.
- Точное совпадение исходного текста проверено: 6294 символа.
- В настройках ИИ добавлен многострочный редактор; сохранение учитывает revision и сохраняет форматирование.
- Лимит 16000 символов соответствует существующему контракту системных сообщений coach-turn.
- Новый текст начинает действовать не позднее истечения кэша; текущий цикл инструментов Android сохраняет полученный текст.
- spotlessApply прошёл. Все 9 JS-тестов админки прошли.
- Серверный прогон на PostgreSQL/Testcontainers: 213 тестов, 211 прошли; два ожидания числа миграций обновлены с 17 до 18.
- Повторный полный класс BackendIntegrationTest после исправления: 49 тестов, BUILD SUCCESSFUL.
- Testcontainers запускался с DOCKER_HOST=unix:///Users/valerochka1337/.colima/default/docker.sock и TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock.
- Рабочая копия: /private/tmp/ValerochkaGymBackend-coach-prompt, ветка feat/coach-prompt-backend, база main (0f8082c).
- Сначала развернуть сервер и миграцию, затем Android 1.3.56 (64). Старые клиенты сохраняют встроенный промпт.
- Развёртывание не выполнялось.
