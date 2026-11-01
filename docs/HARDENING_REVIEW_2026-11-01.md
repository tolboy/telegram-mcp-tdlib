# Повторное ревью ветки hardening — 2026-11-01

Область: 19 коммитов `dbfcca5..6a3b449` на `hardening/mcp-landscape-followups`,
план и обзор от 2026-10-31, companion skill, новые manual workflows.
Перед работой дерево было чистым. Локальный `.env` и установленные клиентские
конфигурации не изменялись. Это ревью реализации и регрессионные проверки,
не сертификация безопасности и не полный динамический пентест Telegram.

## Корректировки плана и обзора

Оба документа теперь различают историческое состояние до ветки и сделанную
реализацию. Уточнены конфигурационная несовместимость startup guard, область
mutation allow-list, JSON-контракт результатов, ограничения Windows ACL,
отмена TDLib и границы conformance. Историческую запись SESSION сохранили
с явной пометкой её времени, а CHANGELOG и PERMISSIONS привели к текущему коду.

2026-11-01 повторно открыты первоисточники:

- [MCP latest](https://modelcontextprotocol.io/specification/latest) перенаправляет
  на 2026-07-28; [новый HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
  действительно отличается от [2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports).
  Миграция транспорта оставлена отдельной задачей.
- [GitHub configuration](https://github.com/github/github-mcp-server/blob/main/docs/server-configuration.md),
  [Playwright README](https://github.com/microsoft/playwright-mcp) и
  [Context7 clients](https://context7.com/docs/resources/all-clients) по-прежнему
  подтверждают применимые архитектурные ориентиры. Числа звёзд не обновлялись.
- [Agensi listing](https://www.agensi.io/skills/telegram-inbox-assistant) уже
  опубликован с версией 0.1.1. Прежнее утверждение «не опубликован» исправлено;
  новая 0.1.2 пока только локальная.

## Найдено и исправлено

Приоритеты ниже относятся к последствиям ошибки, а не к доказанной внешней атаке.

| Приоритет | Сценарий и прежнее поведение | Исправление и доказательство |
|---|---|---|
| P1 | Клиент B делает DELETE с ID клиента A: binding удалялся до проверки владельца. Даже DELETE, отвергнутый транспортом с 405, оставлял живую сессию без владельца | Проверка principal до chain, удаление только после успешного owner DELETE или 404 транспорта. `McpSessionPrincipalFilter`, binder tests и настоящий HTTP integration |
| P1 | ID выдавался без binding, а первый запрос с произвольным ID занимал слот | Binding при выдаче заголовка initialize; неизвестный ID — 404 без регистрации. Лимит 4096 и согласованный с SDK idle timeout 24 часа; тесты initial ownership, capacity и fake-clock expiry |
| P1 | Частичный anti-spam ceiling заменял весь безопасный envelope: например, один maxOps оставлял dailyMax/window без пределов | Отсутствующие измерения дополняются defaults. Runtime не меняет trusted keyField/dedupTextField. `AntiSpamPolicyServiceTest` |
| P1 | Только mutation list при пустом read list не учитывался `hasChatAllowList`; динамическая папка могла охватить другие чаты | Проверка эффективной mutation scope; regression в `GuardrailServiceTest` и `ChatFolderToolsTest` |
| P1 | Conformance запускался из временного data dir, но stop наследовал окружение оператора | Один sanitized env для start/suite/stop, cleanup даже после ошибки старта, временное состояние удаляется лишь после stop. `scripts/tests/test_manual_checks.py` |
| P2 | Servlet rewriter каталога предполагал синхронное тело и не учитывал позднюю SSE-сериализацию | Удалён `McpToolsListFilter`; используется нативный SDK tool filter. Grants пересекаются с реальными доступными аккаунтами, manifest согласован с фильтром; real HTTP/SSE regression |
| P2 | ACL DENY ошибочно принимался за разрешение, BUILTIN Users исключался из shared-write | Только ALLOW, включая Users; реальный Windows ACL regression. Ограничение локализации остаётся |
| P2 | Мастер входа создавал TDLib settings отдельно и пропускал owner-only подготовку | Общая `LocalAccessChecks.prepareOwnerDirectory` вызывается также в auth orchestrator; ошибка POSIX chmod больше не скрывается |
| P2 | Origin/Host принимали отдельные некорректные loopback authority, userinfo/path/query и дубликаты заголовков | Строгая грамматика origin/authority и отказ 403, unit/security-chain regressions. Browser exploit не заявляется |
| P2 | Conformance proxy ждал заполнения буфера на долгом SSE; canary распаковывал архив без общего expansion budget | Потоковый `read1`; bounds на bytes/entries, запрет ссылок/спецфайлов, приватные режимы извлечения и общий timeout canary; Python regressions |
| P2 | Пустой server.address не давал предупреждения API-key deployment, хотя Spring слушает все интерфейсы | Предупреждение включает unset/empty address |
| P2 | `sendError(404)` для неизвестной сессии переходил на закрытый `/error`, превращая ожидаемый 404 в 403 | Session filter выставляет статус напрямую; реальный HTTP regression проверяет неизвестный ID до и после DELETE |
| P3 | Счётчики concurrency оставались навсегда; progress принимал произвольный объект; inbox prompt велел остановиться при отсутствии write tools | Атомарное удаление нулевых счётчиков, string/finite-number progress token, корректный read-only prompt |

`McpConfig` упрощён: единый lifecycle для concurrency, mutation scope, deadline,
результата и progress с восстановлением контекстов в finally.

Важно: исходники Spring AI 2.0.1 включают `immediateExecution(true)` для servlet
транспорта. Поэтому потеря servlet identity в обычном текущем HTTP не объявляется
подтверждённым обходом. Добавлен явный `McpRequestIdentity` для worker execution,
с отдельным тестом переноса и восстановления authority. Primary customizer
сохраняет исходный immediate режим Spring. STDIO использует identity `stdio`.

Проверены также startup approval, файловое исключение TDLib, Docker hash mount,
CSP/referrer policy, prompts и annotations. В этих механизмах дополнительных
подтверждённых ошибок, кроме перечисленных, не найдено.

## Проверки

Финальный прогон: `gradlew.bat clean test bootJar verifyNativeRuntimeCoverage
verifyReleaseMetadata --no-build-cache --console=plain`, exit 0, BUILD SUCCESSFUL.
1044 tests: 1042 passed, 2 skipped. Пропущены symlink-тесты AuditService и
FileSecurityService из-за отсутствия поддержки создания ссылок в окружении.

| Проверка | Результат |
|---|---|
| Полный JVM-набор и сборка | 1042 passed, 2 skipped; bootJar, embedded release metadata и native classifier coverage — PASS |
| Python suite `unittest discover -s scripts/tests -v` | 11 passed; финальные harness regressions дополнительно 6 passed |
| Skill validation и упаковка | PASS; 0.1.2, архивы в `build/skills` |
| Независимый Python MCP SDK: STDIO, HTTP, bridge | PASS: 36 read tools, успешный manifest, ожидаемый TELEGRAM_UNAVAILABLE без credentials, output schemas и annotations |
| Изолированный daemon stop/restart | PASS в независимом smoke; conformance daemon также остановлен и временный каталог удалён |
| Live Telegram на новом JAR: STDIO и HTTP | PASS: identity baratnor, resolve kodobeast, по одной записи history, ограниченный каталог из 5 tools и 5 prompts; сообщений отправлено 0 |
| HTTP policy на финальном JAR без Telegram credentials | PASS: public info содержит только build; контрольное info.review-canary не опубликовано; invalid Origin/Host — 403 с валидным ключом; unknown session — 404 |
| Focused conformance 0.1.16 | 6 checks SUCCESS по 5 сценариям; общий процесс exit 1 из-за Windows Node 24.14.1 native libuv assertion. End-to-end НЕ пройден |

Conformance запускает `server-initialize`, `ping`, `tools-list`, `prompts-list`,
`dns-rebinding-protection`. Полный suite рассчитан на fixture-сервер с `test_*`
tools/prompts/resources; его нельзя применять целиком к этому production-серверу
и считать отсутствие fixture-инструментов ошибкой реализации. Harness сохраняет
и проверки, и коды процессов, не превращая runner crash в зелёный результат.

Локальные диагностические артефакты (не входят в Git):

- `%TEMP%/ktm-build/telegram-mcp-tdlib/reports/tests/test/` — JVM report;
- `%TEMP%/telegram-mcp-result-smoke-6_de54h3/report.json` — SDK smoke;
- `%TEMP%/ktm-hardening-live-9ubmaq97/report.json` — live итог без переписки;
- `%TEMP%/ktm-hardening-http-policy-877lcb3b/report.json` — HTTP policy/info;
- `build/reports/mcp-conformance-review/` — выбранные upstream checks.

Live использовал существующую локальную авторизацию, без повторного входа или
копирования TDLib-сессии. Ответы Telegram и секреты не включены в этот отчёт.

## Skill и оставшиеся ограничения

`skills/telegram-inbox-assistant/SKILL.md` версии 0.1.2 уточняет текстовые ошибки,
разные pagination contracts compact/history и сохранение read-only области.
SHA-256 `telegram-inbox-assistant-0.1.2.zip`:
`28b1ba24ee0f08d497001e02d12043a94fb37a388405217b9fed31ff689fe62d`.
Agensi не обновлялся; загрузка skill в стороннем хосте и fresh authorization не проверены.

Остаются отдельными задачами:

- отмена текущего TDLib-вызова с освобождением ресурсов и корректным исходом мутации;
- миграция всего транспорта на MCP 2026-07-28;
- настраиваемый ключ TDLib, Windows ACL всего session tree и защищённые бэкапы;
- полноценный effective-access аудит Windows: текущие shared-group проверки
  зависят от распознанного имени. Well-known SID lookup через NIO на этом JDK
  проверен и не поддерживается; локализованная ОС не объявляется покрытой;
- CI canary с защищёнными credentials и проверка GitHub Actions на runner;
- end-to-end conformance без native Windows Node crash, нативная совместимость
  конкретных AI-хостов, Linux/macOS исполнение POSIX-ветвей.

Human approval покрывает destructive-набор, а не все мутации. Mutation chat list
не ограничивает account-wide tools. Для них по-прежнему нужны профили, allow/deny
и action grants. Read-only и маркировка контента не устраняют prompt injection.
