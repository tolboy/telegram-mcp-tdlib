# Аудит и рефакторинг — 2026-11-07

Исходный HEAD: `6de8dfe` (после 1.19.1); до работы дерево было чистым.
Область: архитектура приложения, жизненный цикл TDLib и авторизации,
multi-account routing, HTTP/STDIO, безопасность, отправки и восстановление,
хранение, экспорт/пагинация, схемы инструментов, сборка, CI и документация.
Проверка сочетает чтение кода по слоям, воспроизведение конкретных сценариев,
регрессионные тесты и независимый MCP-клиент. Это не доказательство корректности
каждой строки и не внешний пентест развёрнутого сервиса.

Исследование внешних решений, точные GitHub SHA, лицензии и основания для
заимствования вынесены в [обзор TDLib-проектов](TDLIB_OPEN_SOURCE_REVIEW_2026-11-07.md).
Код конкурентов в проект не переносился.

## Архитектурный вывод

Текущую платформу Kotlin/Spring/TDLight целесообразно сохранить. В проекте уже
есть полезные для MCP инварианты: маленькая read-only поверхность по умолчанию,
проверка прав при вызове, изолированные аккаунты, идентичность HTTP-сессии,
безопасная обработка неизвестного результата отправки, общий deadline,
bounded change journal с явными gap и типизированные output schemas.
Переписывание транспорта или клиента одновременно с исправлением lifecycle
увеличило бы область возможных регрессий.

Основной рефакторинг здесь направлен на владение ресурсами и сохранение
семантики ошибок. Повторяющийся код разрешения сущностей сведён к одному
механизму кеша; auth-события привязаны к поколению клиента; поздние подтверждения
сохранения вынесены из потока TDLib в отдельный ограниченный worker.

## Подтверждённые проблемы и изменения

Приоритет отражает последствия сценария, а не факт эксплуатации.

| Приоритет | Сценарий до изменения | Исправление и проверка |
|---|---|---|
| P1 | Валидный MCP API key/JWT давал удалённый доступ к `/auth/**` в обход tool/account grants: logout, новый login, QR и hints | Весь auth/setup API требует прямого loopback, отсутствия forwarding headers и допустимых Origin/Host. Security-chain regressions с API key, scoped key и JWT |
| P1 | Logout во время build, когда `currentClient == null`, не инвалидировал build; позднее старый клиент мог появиться снова. Старые auth callbacks/errors могли менять новую попытку | Teardown всегда увеличивает generation; callbacks, input requests и результаты build проверяют поколение. Stale factory закрывается до следующего build |
| P2 | READY мог прийти до возврата `builder.build()`, и hot-swap терялся | Готовый клиент публикуется атомарно; уже полученный READY вызывает регистрацию после публикации |
| P2 | Исключение build после создания factory оставляло native resources; закрытый interactive client оставался delegate | Ошибка закрывает factory; teardown сбрасывает delegate на исходный fallback и очищает состояние подключения |
| P2 | Global key и named client могли иметь одинаковый principal/credential; выбирались права первого совпадения | Конфигурация с неоднозначными identities/keys отклоняется до запуска; regressions не выводят ключи |
| P2 | Owner-only secret/policy файл можно было подменить через group-writable POSIX parent | Group/world writable parent без sticky bit отклоняется. POSIX filesystem regression добавлен для Linux CI; на локальной Windows эта ветвь не исполнялась |
| P2 | Resolver превращал timeout, flood-wait, auth/permission failure и cancellation в `EntityNotFoundException` | Только классифицированный Telegram NOT_FOUND преобразуется в ошибку отсутствия; остальные исключения сохраняются вместе с retry semantics |
| P2 | Отсутствующая account selection молча превращалась в cache scope `default` | Если account context настроен, он обязателен до чтения кеша. Кеши по аккаунтам сохраняются; casing username теперь делит одну TTL-запись |
| P2 | `Number.toLong()` мог обрезать дробь или переполниться, меняя numeric chat identifier | Проверяется точный int64; дроби, nonfinite, overflow и небезопасные Float/Double отклоняются. Для больших ID использовать JSON integer/string |
| P2 | `getChats()` считал любое исключение `LoadChats` концом списка, возвращая ложный успешный partial result | Только ожидаемый TDLib 404 допускает чтение кешированного списка. 429 и server failure возвращаются клиенту |
| P2 | Поздний send observer делал запись журнала и fsync в callback TDLib, задерживая ответы другим вызовам | Один background writer, очередь 256, отказ без caller-runs, ограниченный drain при shutdown. Тесты с заблокированным диском, полной очередью и отказом записи |
| P2 | При падении старта второго аккаунта первый оставался незакрытым: registry ещё не принадлежал Spring lifecycle | Регистрация всех клиентов имеет rollback; уже зарегистрированные и не принятый registry handle закрываются, delegate возвращается к fallback |
| P2 | Таймаут одного из двух `awaitFinal` удалял общее ожидание, и второй терял поздний success; итог нельзя было повторно прочитать нескольким consumers | Reference counting ожиданий, очистка после последнего waiter и общий TTL-кеш outcome. Тесты двух waiters, cleanup и нескольких late consumers |

Таким образом, исправлено 13 групп воспроизводимых дефектов и связанных
пограничных сценариев; размер группы не является числом независимых уязвимостей.

## Проверенные границы

| Область | Что проверено | Практический предел |
|---|---|---|
| TDLib requests | Классификация read/write, локальный timeout, общий deadline, flood gate, provisional/final send ID | Локальный timeout не отменяет уже отправленную Telegram-мутацию |
| MCP dispatch | Read-only, profile/allow/deny, account selection, mutation scope, fallback audit, output envelope | JSON Schema не заменяет серверную проверку аргументов каждого handler |
| HTTP | Key/JWT, Origin/Host, routing headers, session ownership, resource authorization | Ingress обязан исключать auth/setup; proxy, удаляющий все внешние признаки запроса, неотличим от localhost |
| Хранение | Reservation до send, UNKNOWN вместо resend, checkpoint после page, file restrictions и private state | Нет распределённой exactly-once гарантии и гарантии произвольного power loss |
| Changes/resources | Очереди и retained history ограничены, gaps явные, уведомления повторно проверяют права | Это локально наблюдавшиеся updates, не полная история Telegram |
| Build/supply chain | Wrapper SHA-256, TDLight exclusive repository/checksums/BOM, Actions SHA, release metadata и native classifiers | Наличие hashes не равно актуальной базе уязвимостей; native runtime не исполнен на всех ОС этим локальным прогоном |
| CLI/automation | Изолированные offline harness, session tooling, daemon lifecycle, workflows | Релиз, push, публикация и изменение установленного демона этим аудитом не выполняются |

## Совместимость и миграция

- Удалённый или proxied `/auth/**`, в том числе GET state, теперь получает 403
  даже с ключом/JWT. Авторизовать Telegram нужно локальным CLI или внутри
  network namespace контейнера. На ingress auth/setup маршруты не публиковать.
- Повторяющиеся ключи/identities и небезопасный POSIX parent теперь останавливают
  запуск. Разделить credentials и исправить права каталога.
- У resolver сохраняются реальные operational error codes; клиенту не следует
  интерпретировать flood wait как отсутствие username.
- Большой numeric ID передавать точным JSON integer или decimal string;
  значения с потерянной точностью floating point больше не принимаются.
- Поздняя квитанция сохраняется асинхронно: статус может кратко оставаться
  UNKNOWN. Отказ/переполнение очереди сохраняет UNKNOWN и не разрешает resend.

## Дальнейший рефакторинг по приоритету

1. **Унифицировать парсинг всех числовых аргументов.** Общий entity/chat parser
   исправлен, но в handlers остаются отдельные `Number.toLong()/toInt()` для
   message/user/topic IDs и limits. Вынести exact numeric parsing в общий helper,
   затем мигрировать по семействам инструментов с контрактными тестами; не
   заменять все конверсии механически (timestamps, budgets и Telegram IDs различны).
2. **Разделить `TdLibClientService`.** Файл около 2 800 строк объединяет request
   execution, queries, sends, moderation и mapping. Сохранить единый policy
   executor, выделить read/message/admin adapters за существующим интерфейсом.
   Сначала зафиксировать delegation tests, затем переносить группы без смены wire API.
3. **Добавить native failure matrix.** Дополнить существующие platform smoke и
   Windows daemon checks проверкой нескольких клиентов, повторного close,
   старта после неудачи и корректного закрытия update stream на Linux/macOS/Windows.
   Ориентир — native integration tests tdl из внешнего обзора.
4. **Проработать cancellation end-to-end.** MCP cancellation должна освобождать
   waiter/permit, сохраняя UNKNOWN для уже отправленной мутации. Не считать обрыв
   HTTP или `Future.cancel()` подтверждением отмены в Telegram.
5. **Сделать отдельный дизайн session security и retention.** TDLight сейчас не
   предоставляет используемому builder ключ шифрования БД; protected directory
   не означает encryption. Нужны threat model бэкапов, ancestor/effective ACL
   аудит и осторожная retention policy журналов: удаление send receipts снимает
   защиту от повторной отправки.
6. **Проверять upload из стабильного snapshot/descriptor.** Path validation и
   fingerprint полезны, но пользовательский файл должен оставаться неизменным
   во время отправки. Staging решает часть TOCTOU, требует ограничения диска,
   cleanup и независимой реализации с учётом лицензии источника идеи.
7. **Отдельно поддерживать protocol/dependency upgrades.** Миграция MCP транспорта,
   обновление TDLib и native ABI должны иметь свои compatibility gates; версии
   не повышались автоматически ради рефакторинга.
8. **Уточнить обработку истечения SSE.** Во время завершения HTTP integration
   contexts тесты логируют `AsyncRequestTimeoutException`, а catch-all REST
   handler пытается записать ProblemDetail в уже выбранный `text/event-stream`.
   Набор и независимый MCP smoke проходят; это воспроизведённый дефект обработки
   lifecycle/логирования, не доказательство потери tool result. Нужен отдельный
   transport-level regression для committed SSE response и scoped handler.

Эти пункты — отдельные изменения с собственными критериями приёмки, а не
заявление о выполненной миграции или проверенной совместимости всех AI-хостов.

## Воспроизведение проверки

```powershell
.\gradlew.bat test bootJar verifyNativeRuntimeCoverage verifyReleaseMetadata --console=plain '-Pktm.buildDir=build-audit-2026-11-07'
python -m unittest discover -s scripts/tests -v
python scripts/mcp-result-smoke.py --jar build-audit-2026-11-07/libs/telegram-mcp-server.jar
```

Python-команды требуют окружение с `scripts/requirements-mcp-client.txt` и
`jsonschema`. Независимый smoke очищает Telegram credentials и использует
новый временный data directory; это не live Telegram тест.

## Фактические результаты

Проверено финальное дерево изменений на Windows, Temurin 25.0.2.
Версия собранного JAR: `1.19.1-2-g6de8dfe-dirty`.

| Проверка | Результат |
|---|---|
| Полный Gradle `test` | 1194 tests: **1190 passed, 4 skipped, 0 failures/errors** |
| `bootJar`, `verifyNativeRuntimeCoverage`, `verifyReleaseMetadata` | PASS; все 4 поддерживаемых classifier, одна версия natives 4.0.597 |
| Python `unittest discover -s scripts/tests -v` | **11 passed** в существующем `build-agent-features/mcp-venv` |
| Независимый Python MCP SDK: STDIO, HTTP, HTTP→STDIO bridge | PASS: по 36 read tools, manifest, ожидаемый offline `TELEGRAM_UNAVAILABLE`, output schemas, content annotations |
| Изолированный daemon restart | PASS, без авторизованной Telegram session |
| Companion skill packaging/validation | PASS; 8 файлов, ZIP в `build/skills` |
| `git diff --check` | PASS |

Четыре JUnit skips: два NativeTerminal теста требуют терминал; по одному
AuditService/FileSecurityService тесту требуют возможности создать symlink.
POSIX filesystem checks в `LocalAccessChecksTest` на Windows возвращаются до
POSIX assertions, поэтому их зелёный статус не объявляется проверкой Linux.
В Gradle также остаются предупреждения об устаревшем SDK/ByteBuddy API и Gradle
10 compatibility; зависимости не менялись.

Локальные артефакты, игнорируемые Git:

- `build-audit-2026-11-07/reports/tests/test/index.html` — JVM report;
- `build-audit-2026-11-07/test-results/test/` — машинные JUnit результаты;
- `build-audit-2026-11-07/libs/telegram-mcp-server.jar` — проверенный JAR;
- `build-audit-2026-11-07/mcp-result-smoke.json` — SDK smoke report.

Первая попытка в sandbox не имела доступа к Gradle lock/loopback и Python MCP
dependencies; последующие проверки выполнены с разрешённым доступом к кэшу и
готовому окружению. Первый параллельный JVM-прогон пересёк редактирование
исходников, поэтому его mixed-snapshot failures не используются как итог:
выше указан повторный полный успешный прогон финального production/test дерева.
Linux/macOS native runtime, live Telegram и upstream full conformance в этой
сессии не исполнялись. `.env`, реальные сессии, установленные клиентские конфиги
и работающий пользовательский daemon не изменялись. Отправок в Telegram не было.
