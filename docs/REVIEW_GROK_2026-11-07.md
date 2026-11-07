# Ревью фаз доработки — 2026-11-07

Проверяемый диапазон: `ae845a7..6d2cdb3` в
`codex/tdlib-improvement-phases`. До ревью рабочее дерево было чистым.
Основание: [аудит проекта](PROJECT_AUDIT_2026-11-07.md) и
[обзор открытых TDLib-проектов](TDLIB_OPEN_SOURCE_REVIEW_2026-11-07.md).

## Вывод о полноте

Не все пункты исходного плана полностью реализованы. Таблица отделяет
реализованный код от фактической проверки и дальнейшей работы.

| Пункт аудита | Состояние | Доказательство и оставшаяся работа |
|---|---|---|
| 1. Общий integer parser | Реализован | `ToolNumbers`/`ExactNumbers`, миграция handlers и input schemas, регрессии неправильного ID/default/batch. Дополнительная проверка mutation collections присутствует |
| 2. Декомпозиция клиента | Частично | Выделен единый `TdLibRequestExecutor`; `TdLibClientService` всё ещё около 2 600 строк. Read/message/admin adapters ещё не выделены |
| 3. Native matrix | Реализована, локально проверен Windows | Два offline JNI lifecycle теста и workflow четырёх платформ. Linux x64/ARM64 и macOS execution в этой сессии не подтверждён; наличие workflow не считается прохождением матрицы |
| 4. Cancellation end-to-end | Частично | HTTP notification останавливает TDLib/rate-limiter waits; внесены исправления auth/order, ранней отмены и переноса signal в research workers. STDIO и ожидания auth/change-journal/human approval пока не покрывают полный lifecycle отмены |
| 5. Session security/retention | Дизайн и часть защиты реализованы | Threat model, POSIX ancestor checks, сохранение send receipts. Настраиваемого ключа БД нет; Windows effective-access enforcement и защищённые бэкапы остаются отдельными задачами |
| 6. Upload snapshot | Реализован с исправлениями ревью | Приватная копия, стабильный replay fingerprint, лимиты, cleanup и защита snapshot directory. Ограничения descriptor/ancestor race и неизвестного исхода описаны в документации |
| 7. Upgrade gates | Базовая защита реализована | BOM/checksums, pin review test, native и SDK checks. Это не выполненная миграция протокола или обновление зависимости и не автоматическое доказательство совместимости любой будущей версии |
| 8. SSE timeout | Реализован | Committed SSE не заменяется ProblemDetail JSON; отдельные advice regressions и HTTP integration |

Дополнительные идеи обзора — полный update-driven индекс чатов, явные
ограниченные open-chat subscriptions и проверка комплекта third-party notices —
по-прежнему не реализованы. Они не должны считаться закрытыми только потому,
что завершены коммиты других фаз.

## Подтверждённые дефекты в проверяемых коммитах

| Приоритет | Сценарий | Исправление |
|---|---|---|
| P1 | Cancellation filter завершал запрос с 202 до API-key/JWT authentication и проверки владельца сессии | Перенос после `McpSessionPrincipalFilter`; реальные HTTP regressions для отсутствующего/ошибочного ключа, чужого principal и владельца |
| P1 | Fingerprint keyed upload включал случайный путь snapshot UUID: повтор того же файла и ключа всегда конфликтовал | Канонический исходный путь остаётся стабильной частью request fingerprint, SHA-256 берётся с реально отправляемой копии |
| P2 | Отмена после отправки становилась health failure и включала flood gate; чтение LOCAL_CANCEL тоже получало gate | Отдельная классификация локальной отмены, без breaker/flood effects; до dispatch отправка предотвращается, после dispatch остаётся UNKNOWN |
| P2 | Отмена между bind и listen терялась; research coroutines не получали signal | Регистрация сразу в filter scope, общий AtomicBoolean и coroutine context; deadline измеряется монотонным временем |
| P2 | Закрытие tracker стирало уже подтверждённый второй элемент batch, пока первый ещё ожидался | Предшествующее закрытию definite evidence остаётся доступно до прежнего TTL; поздние updates после close игнорируются. Partial forward сохраняет delivered/unknown IDs |
| P2 | Клиентские hints для LOCAL_CANCEL говорили о Telegram timeout и локальном flood gate | Сохранён стабильный error code, но recovery action описывает отмену без выдуманной задержки или автоматического resend |
| P2 | Snapshot назывался UUID вместо исходного имени; cleanup выполнялся только при первом upload, а двусмысленные ошибки могли удалить ещё используемую копию | Сохранены имя/расширение, удерживается lease активного snapshot, сборка просроченных копий выполняется при каждом stage, неопределённые ошибки сохраняют копию |
| P2 | Учёт snapshot capacity был локален одному объекту; собственный snapshot directory мог попасть под широкий allowed root | Общая блокировка и проверка бюджета, исключение snapshot directory из файловых операций; тесты нескольких экземпляров и текущей политики |

Уточнена документация STDIO: чтение frames действительно независимо от
выполнения tool handler. Ограничение находится в отсутствующем обработчике
notification в текущей интеграции, а не в невозможности прочитать cancel.
Это проверено по source JAR MCP SDK 2.1.0 (`StdioServerTransportProvider`,
`McpServerFeatures`, `McpAsyncServer`, `McpServerSession`) и Spring AI 2.0.2.
Для HTTP сейчас сохраняется `immediateExecution=true`; реальный HTTP тест
фиксирует перенос cancellation до callback. При смене scheduler потребуется
перенос signal через transport context, как уже сделано для identity.

Исходный полный набор тестов проходил, несмотря на эти сценарии: они
потребовали новых регрессий. Это показывает границу прежнего покрытия,
а не доказательство отсутствия остальных дефектов.

## Проверка

Базовая сборка проверяемого HEAD до исправлений: Gradle `test nativeTest
bootJar verifyNativeRuntimeCoverage verifyReleaseMetadata` — PASS;
1238 JVM tests (1234 passed, 4 skipped), 2 native tests passed на Windows.

Итоговый прогон исправленного дерева: **1259 JVM tests: 1254 passed,
5 skipped, 0 failures/errors**. Два opt-in native lifecycle теста также прошли
на Windows. `bootJar`, `verifyNativeRuntimeCoverage` и `verifyReleaseMetadata`
успешны; четыре classifier и единственная версия natives 4.0.597 присутствуют.
Отдельно пройдены 11 Python regressions и независимый Python MCP SDK smoke
через STDIO, HTTP и bridge, включая restart изолированного offline daemon.
Реальный HTTP regression проверяет 401 для отсутствующего/неправильного ключа,
403 для чужого владельца сессии, 202 для разрешённой отмены, завершение callback
и возможность следующего вызова без унаследованного cancellation state.

Пять skips связаны с терминалом, symlink privileges и отсутствием POSIX
link-count policy на Windows. Проверка матрицы Linux/macOS в GitHub Actions
не выполнялась. На остановке servlet-контекстов остаются SDK warnings о
recycled SSE response; они не превращались в неуспешные тесты.

Для итогового JVM-прогона использовались отдельный build/cache directory,
`--no-build-cache`, `-Pkotlin.incremental=false` и
`-Pkotlin.compiler.execution.strategy=in-process`: общий incremental compiler
при первой попытке ссылался на отсутствовавшие class files. Исходники и
обычные настройки сборки ради этого обхода не менялись.

Локальные артефакты проверки (игнорируются Git):

- `build-review-grok-2026-11-07/reports/tests/test/index.html`;
- `build-review-grok-2026-11-07/reports/tests/nativeTest/index.html`;
- `build-review-grok-2026-11-07/libs/telegram-mcp-server.jar`;
- `build-review-grok-2026-11-07/build.log`;
- `build-review-grok-live/report.json` и локальный stderr запуска.

Live read-only smoke **не пройден**. Пересобранный JAR успешно инициализировал
STDIO и каталог ровно из трёх read-only tools. Существующая сессия закрылась
TDLib до `get_me`; username не подтверждён, resolve/history не вызывались.
Повторный login, ввод кода/пароля и копирование сессии не выполнялись.
Это не объявляется успешной проверкой аккаунта или основанием повторять
авторизацию автоматически. Дальнейшая диагностика этой сессии остаётся
отдельной задачей.

Реальные Telegram-мутации не входят в это ревью. Если используется существующая
авторизованная тестовая сессия, запускается пересобранный JAR с read-only
профилем; переписка, API keys и данные сессии в отчёт не включаются.
