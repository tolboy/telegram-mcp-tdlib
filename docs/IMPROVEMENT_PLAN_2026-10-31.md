# План доработок после обзора MCP

Дата: 2026-10-31. Опирается на [обзор ландшафта](MCP_LANDSCAPE_AND_SECURITY_REVIEW_2026-10-31.md).
Актуализирован 2026-11-01 после ревью реализации. Протокол клиентов сохраняется,
но startup guard намеренно меняет конфигурационный контракт write-деплоев.
Результаты и границы проверки: [HARDENING_REVIEW_2026-11-01.md](HARDENING_REVIEW_2026-11-01.md).
Последующая публикация: сервер [1.18.1](RELEASE_READINESS_1.18.1.md) выпущен;
skill 0.1.2 отправлен на повторное одобрение Agensi. Состояние ниже описывает
именно ревью реализации, а не итог marketplace-модерации.

## Уточнения и критерии приёмки

- Startup opt-in применяется ко всем write-конфигурациям с `approval=off`.
  `auto`/`elicitation` защищают destructive-набор, а не каждую запись.
- Mutation allow-list сужает chat-scoped действия, включая guard для динамических
  папок. Для account-wide операций нужны tool/action restrictions. Пустой список
  наследует read allow-list, а не запрещает запись.
- HTTP identity переносится в SDK worker через transport context, включая grants,
  account scopes и concurrency. Каталог сужается нативным list-filter SDK до SSE.
- Session ownership устанавливается при выдаче session ID в `initialize`.
  Чужой DELETE и неудачный DELETE не снимают привязку; неизвестный ID не создаёт её.
- Частичные ceilings anti-spam дополняются безопасными значениями для каждого
  отсутствующего поля. Создание TDLib-каталогов через wizard и обычный запуск
  использует одну проверку. Windows ACL проверяет общие ALLOW, а не DENY ACE.
- Conformance использует изолированный data dir для запуска **и остановки** демона;
  proxy обязан пропускать SSE без ожидания полного буфера/закрытия потока.
- Skill проверяется по фактическим JSON-контрактам; локальная новая версия и ZIP
  не означают обновление опубликованного Agensi listing.

## Сделано в этой ветке

| Изменение | Зачем | Опора (где указана) |
|---|---|---|
| Старт запрещён, если запись включена, а `MCP_DESTRUCTIVE_APPROVAL=off`, пока нет `MCP_ALLOW_MODEL_CONFIRMED_WRITES=true` | `"confirmed": true` ставит модель. Генератор конфигов уже пишет `auto` или `elicitation` |
| `Origin` на `/mcp`: нет заголовка — можно; loopback и `MCP_ALLOWED_ORIGINS` — можно; остальное — 403 | Требование Streamable HTTP против DNS rebinding |
| Лимит одновременных вызовов на клиента, по умолчанию 8 | Один ключ демона не занимает весь bulkhead аккаунта |
| Потолок runtime-override anti-spam, отказ от общего writable и битого JSON | Подложенный файл не отключает защиту |
| Секреты: та же проверка записи для Everyone и Authenticated Users на Windows | POSIX уже запрещал групповую запись |
| Каталог сессии TDLib: предупреждение, что библиотека его не шифрует, и owner-only на POSIX | Ключ шифрования базы tdlight из Java не выставляется |
| Предупреждение, если HTTP слушает не loopback в режиме API-ключа, включая пустой `server.address` | Для удалённого деплоя нужны TLS и корректная аутентификация; для многопользовательского рекомендуется OAuth |
| `/actuator/info` без env-contributor и без полного git | Эндпоинт открыт без аутентификации |
| Версии плагинов Gradle в `settings.gradle.kts` | IDEA не видела `org.jetbrains.kotlin.gradle.dsl.JvmTarget`, пока класс не попадал в classpath скрипта. После обновления нужен Reload Gradle Project |
| `TELEGRAM_MUTATION_CHAT_IDS` сужает чаты для write tools. Пустой список оставляет прежний allow-list чтения | Запись не получает чаты шире чтения. Старт с пустым списком только предупреждает | `GuardrailService`, `OperationGuardService.WRITE_TOOLS` |
| Путь внутри каталога сессии TDLib недоступен файловым операциям, даже если он лежит под `MCP_FILE_ROOTS` | `td.binlog` и кэш загрузок не читаются через upload/download | `FileSecurityService` |
| `Host` на `/mcp`: нет заголовка — можно; loopback и `MCP_ALLOWED_HOSTS` — можно; остальное — 403 | Дополняет проверку Origin против DNS rebinding | `McpHostPolicy` |
| `Mcp-Session-Id` закрепляется за principal при выдаче сервером. Чужой ключ получает 403; только успешный owner `DELETE` снимает привязку | Сессия клиента A не продолжается ключом клиента B | `McpSessionPrincipalFilter` |
| Пять рецептов зарегистрированы как MCP prompts | Хост предлагает сценарий. Промпт не выдаёт прав и не подставляет аргументы клиента | `McpRecipePrompts` |
| `progressToken` в `_meta` даёт уведомления старта и финиша | Клиент видит, что долгий вызов жив. Отмена до TDLib отсюда не следует | `McpProgress` |
| `tools/list` сужается грантами и доступными аккаунтами, если задан `MCP_PERMISSIONS_FILE`, через штатный фильтр SDK | Клиент без права mutate не видит `send_message`; проверка при вызове остаётся. STDIO использует identity `stdio` | `McpHttpSecurityConfiguration`, `AccessPermissionService` |
| Присутствующие `Mcp-Method` и `Mcp-Name` сверяются с телом. Нет заголовка — запрос прежний | Обязательные заголовки SEP-2243 оборвали бы клиентов `2025-06-18` | `McpRoutingHeaderPolicy` |
| Docker-конфиг монтирует файл API hash только для чтения. Мастер входа отдаёт `Referrer-Policy: no-referrer` и отдельный CSP nonce | Хеш не попадает в аргументы контейнера. Nonce входа не становится nonce политики | `ClientConfigPrinter`, `AuthWizardController` |

Историческое примечание первой сессии: в локальный gitignored `.env` был добавлен
`MCP_ALLOW_MODEL_CONFIRMED_WRITES=true` для прежнего write-запуска. Это исключение,
не новая норма. При текущем ревью `.env` не менялся, live-проверка использует отдельный
набор настроек с `MCP_READ_ONLY=true`.

## Не делалось, и почему

| Тема | Почему отложено |
|---|---|
| `notifications/cancelled` и отмена текущего вызова TDLib | Нет готовой интеграции приложения с TDLib; отсутствие удобного SDK-типа не доказывает невозможность. Нужен отдельный тест отмены и освобождения ресурсов. Обрыв HTTP в `2025-06-18` не равен отмене; повтор мутации без прежнего idempotency key может создать дубль |
| Обязательные заголовки SEP-2243 и протокол `2026-07-28` | Текущие клиенты их не шлют. Сверяется только то, что уже пришло |
| Префикс `untrusted Telegram data` в тексте ответа | Текст успешного ответа — это JSON поля `data`. `scripts/mcp-result-smoke.py` требует, чтобы он совпадал со structured data. Префикс сломает разбор. Пометка audience и `untrustedTelegramContent` остаётся |
| Ключ шифрования базы TDLib | `TDLibSettings` и `SimpleTelegramClientBuilder` версии 3.5.9+td.1.8.68 не принимают ключ. Отдельный клиент TDLib не писался |
| Составные инструменты, resources с текстом чатов, sampling, Intel macOS | Уже отклонены: ломают аннотации, расширяют утечку переписки или не имеют классификатора TDLight |
| Полный fixture-suite conformance в обязательном CI | `@modelcontextprotocol/conformance` 0.1.16 ожидает специальные `test_*` инструменты и ресурсы. Для production выбран ограниченный набор initialize/ping/catalog/prompts/DNS-сценариев, а локальный proxy добавляет Authorization. Это частичная проверка, не сертификация протокола |
| Живой canary без секрета | Секрета в репозитории нет. Workflow пропускает прогон, пока нет `TELEGRAM_CANARY_*`, и не печатает их |

## Проверка

Собрать и прогнать новые тесты:

```text
./gradlew test bootJar verifyNativeRuntimeCoverage verifyReleaseMetadata
python -m unittest discover -s scripts/tests -v
python scripts/package-telegram-skill.py
```

На Windows использовать `gradlew.bat`; build directory по умолчанию находится в
`%TEMP%/ktm-build/telegram-mcp-tdlib`, если не задан `KTM_BUILD_DIR`.
Независимый SDK smoke: `scripts/mcp-result-smoke.py --jar <путь-к-новому-bootJar>`
после установки `scripts/requirements-mcp-client.txt` и `jsonschema`.
Manual conformance и canary не считаются пройденными только по наличию workflow.
Фактический итог 2026-11-01: JVM 1042 passed / 2 skipped, Python 11 passed;
сборка и SDK smoke успешны, live read-only STDIO/HTTP с baratnor успешен.
Шесть выбранных conformance checks успешны, но процесс завершился exit 1
из-за Windows Node/libuv assertion; end-to-end conformance не объявляется пройденным.

Windows-проверка shared-write ACL покрывает распознанные английские имена и SID,
но не гарантирует локализованные группы или полный effective access. Owner-only
для TDLib применяется на POSIX; Windows ACL дерева и бэкапы остаются задачей оператора.

IDEA: Reload Gradle Project. Импорт `JvmTarget` берётся из `kotlin-gradle-plugin` 2.4.21, версия которого теперь задана в `settings.gradle.kts`.
