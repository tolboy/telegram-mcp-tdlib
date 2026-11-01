# План доработок после обзора MCP

Дата: 2026-10-31. Опирается на [обзор ландшафта](MCP_LANDSCAPE_AND_SECURITY_REVIEW_2026-10-31.md).
В эту ветку вошло то, что закрывается локально и не ломает протокол клиентов.

## Сделано в этой ветке

| Изменение | Зачем |
|---|---|
| Старт запрещён, если запись включена, а `MCP_DESTRUCTIVE_APPROVAL=off`, пока нет `MCP_ALLOW_MODEL_CONFIRMED_WRITES=true` | `"confirmed": true` ставит модель. Генератор конфигов уже пишет `auto` или `elicitation` |
| `Origin` на `/mcp`: нет заголовка — можно; loopback и `MCP_ALLOWED_ORIGINS` — можно; остальное — 403 | Требование Streamable HTTP против DNS rebinding |
| Лимит одновременных вызовов на клиента, по умолчанию 8 | Один ключ демона не занимает весь bulkhead аккаунта |
| Потолок runtime-override anti-spam, отказ от общего writable и битого JSON | Подложенный файл не отключает защиту |
| Секреты: та же проверка записи для Everyone и Authenticated Users на Windows | POSIX уже запрещал групповую запись |
| Каталог сессии TDLib: предупреждение, что библиотека его не шифрует, и owner-only на POSIX | Ключ шифрования базы tdlight из Java не выставляется |
| Предупреждение, если HTTP слушает не loopback в режиме API-ключа | Удалённый деплой должен идти в OAuth |
| `/actuator/info` без env-contributor и без полного git | Эндпоинт открыт без аутентификации |
| Версии плагинов Gradle в `settings.gradle.kts` | IDEA не видела `org.jetbrains.kotlin.gradle.dsl.JvmTarget`, пока класс не попадал в classpath скрипта. После обновления нужен Reload Gradle Project |
| `TELEGRAM_MUTATION_CHAT_IDS` сужает чаты для write tools. Пустой список оставляет прежний allow-list чтения | Запись не получает чаты шире чтения. Старт с пустым списком только предупреждает | `GuardrailService`, `OperationGuardService.WRITE_TOOLS` |
| Путь внутри каталога сессии TDLib недоступен файловым операциям, даже если он лежит под `MCP_FILE_ROOTS` | `td.binlog` и кэш загрузок не читаются через upload/download | `FileSecurityService` |
| `Host` на `/mcp`: нет заголовка — можно; loopback и `MCP_ALLOWED_HOSTS` — можно; остальное — 403 | Дополняет проверку Origin против DNS rebinding | `McpHostPolicy` |
| `Mcp-Session-Id` закрепляется за первым аутентифицированным клиентом. Чужой ключ на тот же id получает 403. `DELETE` снимает привязку | Сессия клиента A не продолжается ключом клиента B | `McpSessionPrincipalFilter` |
| Пять рецептов зарегистрированы как MCP prompts | Хост предлагает сценарий. Промпт не выдаёт прав и не подставляет аргументы клиента | `McpRecipePrompts` |
| `progressToken` в `_meta` даёт уведомления старта и финиша | Клиент видит, что долгий вызов жив. Отмена до TDLib отсюда не следует | `McpProgress` |
| HTTP `tools/list` сужается грантами, если задан `MCP_PERMISSIONS_FILE` | Клиент без права mutate не видит `send_message`. Проверка при вызове остаётся. STDIO не фильтрует список | `McpToolsListFilter` |
| Присутствующие `Mcp-Method` и `Mcp-Name` сверяются с телом. Нет заголовка — запрос прежний | Обязательные заголовки SEP-2243 оборвали бы клиентов `2025-06-18` | `McpRoutingHeaderPolicy` |
| Docker-конфиг монтирует файл API hash только для чтения. Мастер входа отдаёт `Referrer-Policy: no-referrer` и отдельный CSP nonce | Хеш не попадает в аргументы контейнера. Nonce входа не становится nonce политики | `ClientConfigPrinter`, `AuthWizardController` |

Локальный `.env` уже был с `MCP_READ_ONLY=false` и без approval. В него добавлен явный `MCP_ALLOW_MODEL_CONFIRMED_WRITES=true`, чтобы текущий запуск не остановился. Это исключение, не новая норма.

## Не делалось, и почему

| Тема | Почему отложено |
|---|---|
| `notifications/cancelled` и отмена текущего вызова TDLib | В `mcp-core` 2.1.0 нет серверного типа для этого уведомления. Обрыв HTTP на жизненном цикле `2025-06-18` не равен отмене. Повтор мутации без того же idempotency key по-прежнему может создать дубль |
| Обязательные заголовки SEP-2243 и протокол `2026-07-28` | Текущие клиенты их не шлют. Сверяется только то, что уже пришло |
| Префикс `untrusted Telegram data` в тексте ответа | Текст успешного ответа — это JSON поля `data`. `scripts/mcp-result-smoke.py` требует, чтобы он совпадал со structured data. Префикс сломает разбор. Пометка audience и `untrustedTelegramContent` остаётся |
| Ключ шифрования базы TDLib | `TDLibSettings` и `SimpleTelegramClientBuilder` версии 3.5.9+td.1.8.68 не принимают ключ. Отдельный клиент TDLib не писался |
| Составные инструменты, resources с текстом чатов, sampling, Intel macOS | Уже отклонены: ломают аннотации, расширяют утечку переписки или не имеют классификатора TDLight |
| Conformance в обязательном CI | `@modelcontextprotocol/conformance` 0.1.16 не умеет передать `Authorization`. Keyless loopback всё равно требует CSRF. Ручной workflow ниже проксирует ключ локального демона и не входит в pull request |
| Живой canary без секрета | Секрета в репозитории нет. Workflow пропускает прогон, пока нет `TELEGRAM_CANARY_*`, и не печатает их |

## Проверка

Собрать и прогнать новые тесты:

```text
./gradlew test --tests dev.telegrammcp.server.security.McpRoutingHeaderPolicyTest --tests dev.telegrammcp.server.security.McpToolsListFilterTest --tests dev.telegrammcp.server.security.AccessPermissionServiceTest --tests dev.telegrammcp.server.security.CsrfSecurityFilterChainTest --tests dev.telegrammcp.server.config.McpRecipePromptsTest --tests dev.telegrammcp.server.config.McpConfigDeadlineTest --tests dev.telegrammcp.server.api.AuthWizardControllerTest --tests dev.telegrammcp.server.cli.ClientConfigPrinterTest
```

IDEA: Reload Gradle Project. Импорт `JvmTarget` берётся из `kotlin-gradle-plugin` 2.4.21, версия которого теперь задана в `settings.gradle.kts`.
