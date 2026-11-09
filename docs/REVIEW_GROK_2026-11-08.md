# Повторное ревью фаз — 2026-11-08

Проверен диапазон `4c91694..757c631` в ветке
`codex/tdlib-improvement-phases`. Рабочее дерево перед ревью было чистым.
Это продолжение [ревью 7 ноября](REVIEW_GROK_2026-11-07.md), а не замена
его исторических результатов.

## Полнота реализации

| Фаза исходного аудита | Результат проверки |
|---|---|
| 1. Exact integer parser | Реализован ранее; полный JVM-прогон проходит |
| 2. Декомпозиция клиента | Реализована: read/message/admin adapters, общий support и один request executor. Независимо сопоставлены 157 перенесённых функций и 115 façade delegation methods; изменений аргументов или поведения при переносе не найдено |
| 3. Native lifecycle matrix | Код тестов и workflow четырёх платформ есть. Windows и Linux x64 проверены локально; Linux ARM64 проверен под Docker-эмуляцией. macOS ARM64 runner в этой сессии не запускался |
| 4. Cancellation end-to-end | Есть HTTP и настоящий SDK STDIO regression; auth-ready, change-journal, approval и state-store lock waits учитывают отмену. Дополнительно исправлены неограниченные elicitation workers и блокирующее ожидание journal lock |
| 5. Session security/retention | Threat model и политика receipts реализованы; исправлены Windows ACE ordering и защита резервных копий. Ограничения Windows ancestors и шифрования перечислены ниже |
| 6. Upload snapshot | Реализован ранее с исправлениями предыдущего ревью; полный JVM-прогон проходит |
| 7. Upgrade gates | Dependency pins, checksums и compatibility checks есть; версии и ревизия MCP автоматически не обновлялись |
| 8. SSE timeout | Реализован ранее; HTTP regressions и независимый SDK smoke проходят |

Полный update-driven индекс чатов и ограниченные явные `openChat`
subscriptions остаются отложенными возможностями внешнего обзора. Они не
входили в обязательные восемь фаз рефакторинга. Проверка полноты third-party
license/notices в распространяемых runtime bundles также остаётся отдельным
pre-release gate; эта работа не подтверждает готовность нового публичного релиза.

## Найденные и исправленные недостатки

| Приоритет | Дефект | Исправление и проверка |
|---|---|---|
| P1 | Windows ACL вычислялась как ALLOW минус все DENY, игнорируя порядок ACE; поздняя DENY могла скрыть уже предоставленное право. Опасные права BUILTIN\Users исключались из проверки | Для каждого SID и опасного бита учитывается первое применимое решение в существующем порядке ACE; исключение Users убрано. Регрессии обеих последовательностей, смешанных битов и безопасного READ/EXECUTE |
| P1 | Новые plaintext backups наследовали доступ и могли попасть в upload/download под широким allowed root | Owner-only backup directories с наследуемой ACL на Windows / 0700 на POSIX, 0600 для POSIX-файлов; вся ветка `tdlib-backups` запрещена файловым инструментам. Проверены реальные права синтетической копии и file-tool boundary |
| P1 | Config shutdown и interactive auth build могли пересечь final common-factory close и новый acquire; TDLight очищает общий slot до завершения receiver drain и может проглотить interrupt при закрытии | Общий lifecycle worker сериализует actual factory acquisition/close всех production owners; caller interrupt восстанавливается после завершения операции, close failure блокирует новые factories. Проверяются close/acquire ordering, interrupt и fail-closed semantics; offline native fixture использует тот же guard |
| P1 | Стандартный session path мог быть junction на внешний каталог: проверка `isSymbolicLink` не покрывала Windows junction | Перед staging проверяется реальный standard path; copyTree отдельно отвергает linked source. Windows regression создаёт настоящий junction и проверяет сохранность внешних данных |
| P2 | Копирование выполнялось до повторной проверки staged lock; частичная копия выглядела готовой; после failed restore сообщалось об успехе | Lock проверяется до копирования; приватный UUID pending directory публикуется атомарно только после копирования и flush. Ошибки сохраняют исходные данные, называют staging/backup и не выдают неудачное восстановление за успешное |
| P2 | Каждый elicitation создавал новый daemon thread; отменённые callers освобождали свой лимит, оставляя SDK prompts без общего ограничения | Executor без очереди ограничен восемью outstanding запросами; слот удерживается до фактического SDK completion. Saturation, pre-cancel и shutdown проверены |
| P2 | Journal reader мог остаться в ReentrantLock/Condition reacquisition или FileChannel.lock после отмены | Polling выполняется вне writer lock, acquisition разбит на отменяемые интервалы, бюджет измеряется монотонно. Регрессии blocked writer и реально удерживаемого OS lock; cursor остаётся прежним |
| P2 | Request inventory test искал TDLib requests только в старом façade после переноса | Явно включены все семь владельцев requests; сохранены проверки классификации Get/Search/Load |
| P2 | Native lifecycle timeout включал холодную загрузку JNI; interrupt прерывал ожидание cleanup и мог допустить второй receiver | Загрузка вынесена в отдельный bounded BeforeAll до создания клиентов; cleanup ждёт свой ограниченный бюджет, сохраняя interrupt после завершения. Lifecycle assertions и их 30/45-секундные границы сохранены |

Windows ACL check консервативен для каждого principal и не моделирует
пересекающиеся членства групп как полный `AccessCheck`. Опасные replace rights
Windows ancestors пока логируются; hostile concurrent ancestor replacement не
имеет descriptor-level гарантии. Приватный каталог и backup не означают
шифрование TDLib database. Уже начатое дисковое I/O и отправленный SDK prompt
не прерываются локальной отменой. Подробнее —
[session threat model](SESSION_SECURITY_AND_RETENTION.md).

## Проверки

Полная сборка `test nativeTest bootJar verifyNativeRuntimeCoverage
verifyReleaseMetadata` прошла: **1287 JVM tests, 1281 passed, 6 skipped,
0 failures/errors**, два native lifecycle tests на Windows. Использованы
отдельные build/project-cache directories, `--no-build-cache`,
`-Pkotlin.incremental=false` и in-process Kotlin compiler.

Шесть skips обусловлены терминалом, отсутствием symlink privileges и POSIX
hard-link policy на Windows. SSE shutdown сохраняет известные SDK warnings
о recycled response; тесты при этом проходят.

Linux x64: два native lifecycle tests прошли в Gradle на Temurin 25, также
прошли 63 выбранных backup/ACL/file-security/factory-lifecycle tests
(61 passed, 2 Windows-only skips). ARM64: два тех же lifecycle tests прошли через JUnit launcher в
Linux-контейнере под Docker-эмуляцией, без сети и credentials. Это не
исполнение на физическом ARM64-хосте. В контейнер передавались только исходники
или собранные JVM-классы и зависимости, без `.env` и реальных сессий.
После первого source build Linux-проверки повторяли исполняемые JVM-классы
финальных исходников, с отключённой повторной компиляцией в контейнере.

Первый Linux Gradle native-прогон завершился SIGABRT: холодная загрузка заняла
около 28 секунд из 30-секундного test timeout; interrupt совпал с teardown и
запуском нового receiver. Первичный отчёт сохранён отдельно в
`build-review-2026-11-08/linux-amd64-initial`. После исправления границ setup и
cleanup Gradle-прогон прошёл. Исходное падение не скрывается повторным PASS.

**11 Python regressions** прошли с доступом к реальному localhost. Первый
запуск в сетевой песочнице был прерван после socket/subprocess ошибок;
успешным считается повторный host-прогон. Независимый Python MCP SDK проверил
STDIO, HTTP и bridge: lifecycle, 36 tools reader surface, result/error envelopes,
output schemas, annotations и restart изолированного offline daemon — PASS.

Live read-only smoke нового JAR — **PASS**. Нужная авторизованная сессия найдена
по старому launcher Codex; для запуска сделана приватная рабочая копия.
`get_me` подтвердил личность тестовой учётной записи, `resolve_username` разрешил публичный
`@telegram`, `get_history` вернул одну запись. Исходный каталог не запускался
и не изменялся; login, logout, отправки и другие Telegram-мутации не вызывались.
Credentials и текст сообщений в отчёт не включены.

Локальные артефакты исключены из Git:

- `build-review-2026-11-08/reports/tests/test/index.html`;
- `build-review-2026-11-08/reports/tests/nativeTest/index.html`;
- `build-review-2026-11-08/libs/telegram-mcp-server.jar`;
- `build/review-2026-11-08*.log`;
- `build-review-2026-11-08-live/report.json` (приватный каталог).

Проверки выполняются локально. Remote push и публикация релиза не выполнялись.
