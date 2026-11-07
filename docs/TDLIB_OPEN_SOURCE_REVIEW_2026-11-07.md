# TDLib: открытые конкуренты и применимые инженерные решения

Дата проверки: 2026-11-07. Цель — найти решения для существующего сервера,
а не выбрать новый язык или Telegram-стек. Просмотрены публичные исходники,
тесты, лицензии и метаданные GitHub; чужие приложения и их тестовые наборы
не запускались. Собственные результаты проверки проекта описываются отдельно
в отчёте об аудите.

## Что считать подтверждением

Разделены три уровня доказательств: контракт официальной TDLib; реализация
с конкретными регрессионными тестами; признаки независимого использования
проекта. Звёзды и форки помогают выбрать материал для изучения, но не доказывают
корректность, безопасность или успешность конкретного решения. Наличие workflow
тоже не означает, что его последний запуск успешен.

Прямые TDLib MCP-конкуренты найдены, однако у трёх изученных проектов пока нет
заметных публичных сигналов независимого распространения. Их идеи полезны как
кандидаты, но называть их проверенными сообществом преждевременно. Более сильная
опора — официальная TDLib, используемая зависимость TDLight, многоплатформенный
wrapper `tdl` и существующий клиент Telegram X.

## Зафиксированные ревизии

Метаданные получены из публичного GitHub API. Счётчики относятся только к моменту
проверки; дата коммита не равна дате релиза. Ссылки на SHA ниже не меняются вслед
за ветками. Это ревизии **изученных исходников**, а не рекомендация обновить
зависимости проекта до HEAD.

| Проект / роль | Изученная ревизия, дата коммита UTC | Stars / forks | Лицензия репозитория |
|---|---|---:|---|
| [xhns/tdlib-mcp](https://github.com/xhns/tdlib-mcp), прямой конкурент: Rust, TDLib FFI, HTTP daemon | [c6c7dbb](https://github.com/xhns/tdlib-mcp/tree/c6c7dbb279099989fe44fea74ef30bbbc0889d90), 2026-10-27 | 0 / 0 | Apache-2.0 / MIT, два файла лицензий |
| [Crocodile-beep/tdlib_mcp_server](https://github.com/Crocodile-beep/tdlib_mcp_server), прямой конкурент: Python, ctypes | [4ab5dd2](https://github.com/Crocodile-beep/tdlib_mcp_server/tree/4ab5dd21509556b5e36513f74b997147c698559a), 2026-06-08 | 0 / 0 | Не обнаружена; API возвращает null |
| [andrewkomkov/tg-mcp](https://github.com/andrewkomkov/tg-mcp), прямой конкурент: Python, JSON-интерфейс | [a1eb41c](https://github.com/andrewkomkov/tg-mcp/tree/a1eb41cd88ac745a92735973d32858c529d1b767), 2026-10-26 | 0 / 0 | MIT |
| [tdlib/td](https://github.com/tdlib/td), официальный контракт и reference client | [4c6377a](https://github.com/tdlib/td/tree/4c6377aa7386c39217878a9a56d581922ad37ca4), 2026-10-27 | 9260 / 2230 | BSL-1.0 |
| [tdlight-team/tdlight-java](https://github.com/tdlight-team/tdlight-java), используемая JVM-обвязка | [e1a240e](https://github.com/tdlight-team/tdlight-java/tree/e1a240e00a03b8867b1fbbb5c326ee6e194b4eae), 2026-10-19 | 331 / 50 | LGPL-3.0 |
| [eilvelia/tdl](https://github.com/eilvelia/tdl), раньше Bannerets/tdl: Node.js wrapper TDLib | [34f1b86](https://github.com/eilvelia/tdl/tree/34f1b868f64eb5c4952b357db8094da218ab8ba9), 2026-07-23 | 537 / 62 | MIT |
| [TGX-Android/Telegram-X](https://github.com/TGX-Android/Telegram-X), полноценный Android-клиент | [08ee6c2](https://github.com/TGX-Android/Telegram-X/tree/08ee6c2c5a9275dc51ecb004f336abc1982799d5), 2026-11-06 | 6176 / 1181 | GPL-3.0 |
| [paul-nameless/tg](https://github.com/paul-nameless/tg), терминальный TDLib-клиент | [b0eee6f](https://github.com/paul-nameless/tg/tree/b0eee6f004e8df9556a9a9a0b42c9ebe0159c66b), 2026-05-04 | 1200 / 94 | Unlicense |

`eilvelia/tdl` нельзя путать с Go toolkit `iyear/tdl`: совпадение названий
не означает общий стек. [gotd/td](https://github.com/gotd/td) — Go-клиент MTProto,
а [iyear/tdl использует gotd](https://github.com/iyear/tdl/blob/master/go.mod).
Telethon, GramJS и Bot API MCP-серверы тоже не TDLib-конкуренты
по реализации протокола; их прежний функциональный обзор остаётся в
[PUBLIC_BENCHMARK_AND_ROADMAP.md](PUBLIC_BENCHMARK_AND_ROADMAP.md).

## Что применимо к этому проекту

### 1. Цикл загрузки чатов должен следовать контракту TDLib

Официальная схема определяет `loadChats` 404 как признак полной загрузки.
Меньшее число загруженных чатов само по себе не означает конец списка.
`getChats` предназначен для информационного снимка; согласованный постоянно
обновляемый каталог строится через загрузку и updates.
[Контракт](https://github.com/tdlib/td/blob/4c6377aa7386c39217878a9a56d581922ad37ca4/td/generate/scheme/td_api.tl#L12498),
[Java example: повтор загрузки и обработка 404](https://github.com/tdlib/td/blob/4c6377aa7386c39217878a9a56d581922ad37ca4/example/java/org/drinkless/tdlib/example/Example.java#L230).

Для текущего bounded `list_chats` допустим снимок, но нельзя превращать
произвольную ошибку загрузки в успешный ответ. В исходном `getChats` был именно
такой широкий `catch (Exception)`; в текущем аудите он заменён обработкой
только TDLib 404 с регрессионными проверками ошибок загрузки.
Полный update-driven индекс нужен только при отдельном требовании к полноте
каталога. Он потребует обработки позиций, archive/folders и перестановок,
а не простого накопления `UpdateNewChat`.

### 2. Поток callbacks TDLib должен оставаться свободным

Пример TDLight явно использует асинхронное завершение для продолжения работы,
чтобы не блокировать поток ответов. У upstream есть отдельные проверки
регистрации клиента из callback и закрытия receiver из собственного callback.
[Пример](https://github.com/tdlight-team/tdlight-java/blob/e1a240e00a03b8867b1fbbb5c326ee6e194b4eae/example/src/main/java/it/tdlight/example/Example.java#L157),
[CallbackLifecycleTest](https://github.com/tdlight-team/tdlight-java/blob/e1a240e00a03b8867b1fbbb5c326ee6e194b4eae/tdlight-java/src/test/java/it/tdlight/CallbackLifecycleTest.java).

У нас этот принцип уже реализован в `ChangeJournal`: ограниченная очередь,
один writer, запись пакетами, callbacks без диска. Дублировать его другим
event bus не нужно. Отдельно проверяется цепочка поздних send receipts:
обычный `CompletableFuture.thenRun` выполняет observer на завершающем потоке,
а запись receipt включает файловые операции. Это отдельная граница от журнала
изменений. В текущем аудите запись поздних receipts вынесена в
`LateSendReceiptWriter`: один поток, очередь 256, без выполнения на submitting
thread при переполнении. Невозможность надёжно записать исход оставляет `UNKNOWN`.

### 3. Завершение клиента — состояние, наблюдаемое всеми ожидающими

В `tdl` закрытие завершает pending requests с ошибкой и запрещает повторное
использование клиента. Тесты проверяют повторный `close`, вызовы после закрытия,
окончание update iterator и независимость двух клиентов.
[Реализация](https://github.com/eilvelia/tdl/blob/34f1b868f64eb5c4952b357db8094da218ab8ba9/packages/tdl/src/client.ts#L325),
[integration tests](https://github.com/eilvelia/tdl/blob/34f1b868f64eb5c4952b357db8094da218ab8ba9/tests/integration/test.ts#L96).
У TDLight есть тесты гонок send/close и освобождения временных сообщений
при закрытии и отмене.
[SendCloseRaceTest](https://github.com/tdlight-team/tdlight-java/blob/e1a240e00a03b8867b1fbbb5c326ee6e194b4eae/tdlight-java/src/test/java/it/tdlight/SendCloseRaceTest.java),
[TemporaryMessageHandlerTest](https://github.com/tdlight-team/tdlight-java/blob/e1a240e00a03b8867b1fbbb5c326ee6e194b4eae/tdlight-java/src/test/java/it/tdlight/client/TemporaryMessageHandlerTest.java).

Заимствовать стоит сценарии проверки, а не ещё один клиентский runtime.
Простой переход на upstream `sendMessage(wait=true)` не заменяет наш контракт:
он дополнительно требует durable idempotency, deadline, поздние receipts
и определённый исход частичной пересылки/альбома. Отмена локального ожидания
сама по себе не отменяет отправку в Telegram.

### 4. Потеря updates должна быть видна читателю

У `xhns/tdlib-mcp` ring buffer имеет монотонный cursor и флаг gap; тест
явно переполняет буфер и проверяет потерю покрытия.
[Код и тесты](https://github.com/xhns/tdlib-mcp/blob/c6c7dbb279099989fe44fea74ef30bbbc0889d90/crates/tdlib-mcp/src/td/client.rs#L159).
Это полезное независимое совпадение дизайна, уже присутствующего у нас:
`ChangeJournal` хранит bounded metadata, сообщает restart/reconnect/overflow
и не выдаёт locally observed updates за полный поток Telegram.
`ChangeJournalTest` проверяет overflow, ошибки записи, retention и restart.

Более зрелый reference Telegram X считает открытые чаты и симметрично вызывает
`openChat`/`closeChat`.
[Реализация](https://github.com/TGX-Android/Telegram-X/blob/08ee6c2c5a9275dc51ecb004f336abc1982799d5/app/src/main/java/org/thunderdog/challegram/telegram/Tdlib.java#L4363).
Схема TDLib предупреждает: для supergroups/channels полный набор updates
зависит от открытого чата.
[Контракт](https://github.com/tdlib/td/blob/4c6377aa7386c39217878a9a56d581922ad37ca4/td/generate/scheme/td_api.tl#L14186).
Поэтому отдельный opt-in механизм подписок с лимитом и reference counting
можно рассматривать в будущем; автоматически открывать все чаты при чтении
или обещать полный мониторинг сейчас нельзя.

### 5. Файлы и MCP-сессии: полезные идеи, разные уровни зрелости

`xhns` ограничивает таблицу сессий, связывает владельца с токеном и имеет
HTTP-тесты чужого session ID, насыщения таблицы и независимости клиентов.
[Auth implementation](https://github.com/xhns/tdlib-mcp/blob/c6c7dbb279099989fe44fea74ef30bbbc0889d90/crates/tdlib-mcp/src/mcp/auth.rs),
[HTTP tests](https://github.com/xhns/tdlib-mcp/blob/c6c7dbb279099989fe44fea74ef30bbbc0889d90/crates/tdlib-mcp/tests/http.rs).
У нас ownership, резервирование до выдачи ID, общий предел 4096 и idle expiry
уже есть в `McpSessionPrincipalBinder`; это не новая находка и не повод
переписывать фильтр. Per-principal quota — отдельное возможное улучшение
справедливости использования общего daemon.

Ещё одна идея `xhns` — передавать TDLib приватную staging-копию upload,
удерживать её при неизвестном исходе и удалять после подтверждения.
Тесты проверяют staging, позднее завершение и request timeout.
[Send implementation and tests](https://github.com/xhns/tdlib-mcp/blob/c6c7dbb279099989fe44fea74ef30bbbc0889d90/crates/tdlib-mcp/src/tools/send.rs#L894).
Наши canonical roots/session exclusions уже полезны, но проверка пути
не эквивалентна чтению из того же открытого descriptor. Полноценный staging
с защитой от замены файла, hardlinks, лимитом копирования и восстановлением
после restart следует проектировать отдельно и проверять на Windows и POSIX.
Слепой перенос Unix-решения в JVM-сервер не предлагается.

### 6. Native compatibility проверяется исполнением, а не наличием JAR

У `tdl` CI запускает integration tests на Linux, macOS и Windows,
с несколькими версиями TDLib; исключения платформ указаны явно.
[Workflow](https://github.com/eilvelia/tdl/blob/34f1b868f64eb5c4952b357db8094da218ab8ba9/.github/workflows/ci.yml).
У нас есть Linux build/test, Windows daemon/ACL проверки, проверка состава
native classifiers и [platform-smoke.yml](../.github/workflows/platform-smoke.yml)
с offline MCP surface smoke на Linux/Windows/macOS. Последний проверяет
запуск и tool profiles без Telegram credentials, поэтому TDLib factory не
создаётся; это не native create/close test. Следующее полезное заимствование —
дополнительный native smoke, включая macOS и Linux ARM64: загрузить библиотеку,
выполнить синхронный безсетевой метод, создать/закрыть клиент без реальных credentials.
Наличие нужного classifier в boot JAR не доказывает его загрузку на каждой ОС.

## Решения по заимствованию

| Статус | Решение для проекта |
|---|---|
| Adopted | В документации уточнены границы TDLib/encryption и добавлены ссылки на зафиксированные upstream исходники и тесты |
| Already present | Общий ref-counted low-level native receiver, отдельные `SimpleTelegramClientFactory` аккаунтов и разделённые sessions; финальный ID отправки, durable receipts и запрет слепого повтора неизвестного send |
| Already present | Bounded async change journal, gap semantics, account-scoped cursors и фильтрация чатов |
| Already present | MCP session ownership, общий лимит и expiry; read-only поверхность и проверка прав при вызове |
| Adopted | Обработка только ожидаемого LoadChats 404, освобождение send waiters при закрытии и bounded writer поздних receipts; проверки shutdown/ошибок/насыщения добавлены в текущем аудите |
| Defer | Update-driven полный индекс чатов и ограниченные явные open-chat subscriptions: нужны отдельный контракт полноты и lifecycle |
| Defer | Descriptor-based upload staging, recovery/cleanup и hardlink-политика: отдельная реализация с тестами ОС |
| Defer | Native smoke macOS/ARM64 и проверка распределяемых third-party licenses/notices |
| Reject | Универсальный raw TDLib tool: расширит модельную поверхность и потребует повторить весь permission/approval/classification слой |
| Reject | Добавление runtime-загрузчика схемы вместо сгенерированных типов: здесь API и natives уже связаны BOM, новый сетевой компонент не нужен |
| Reject | Миграция с JVM/TDLight на Rust/Python/Node только из-за меньшего объёма чужого кода |

`andrewkomkov/tg-mcp` проверяет входящие JSON-запросы по схеме TDLib и содержит
тесты типов, неизвестных полей и соответствия установленной версии.
[Schema tests](https://github.com/andrewkomkov/tg-mcp/blob/a1eb41cd88ac745a92735973d32858c529d1b767/tests/test_schema.py).
Это подходящий ответ для динамической Python-обвязки. Здесь полезен принцип
совпадения API и бинарника, уже реализованный BOM и checksum verification;
переносить сетевую schema loader архитектуру необходимости нет.
Upstream loader использует cache и возвращает `None`, если схема недоступна;
его сетевую проверку нельзя считать безусловной гарантией каждого старта.
[Loader](https://github.com/andrewkomkov/tg-mcp/blob/a1eb41cd88ac745a92735973d32858c529d1b767/src/tgmcp/schema.py#L178).

У `Crocodile-beep` просмотрены ctypes wrapper с request queue и корреляцией
через `@extra`, а также `test_auth.py`. Последний — интерактивная диагностика
входа с настоящими credentials, а не доказательство автоматической проверки
ошибок и гонок. Переносить этот код не предлагается; у нас уже есть типизированная
JVM-обвязка, собственные regression tests и изолированные auth workflows.
[Client](https://github.com/Crocodile-beep/tdlib_mcp_server/blob/4ab5dd21509556b5e36513f74b997147c698559a/tdlib_client.py),
[Auth diagnostic](https://github.com/Crocodile-beep/tdlib_mcp_server/blob/4ab5dd21509556b5e36513f74b997147c698559a/test_auth.py).

`paul-nameless/tg` полезен как пример разделения chat/message/user моделей,
а не как готовый серверный cache/security слой.
[Models](https://github.com/paul-nameless/tg/blob/b0eee6f004e8df9556a9a9a0b42c9ebe0159c66b/tg/models.py).
Его TUI сам отмечает просматриваемые сообщения прочитанными; такое поведение
нельзя переносить в read-only MCP-сценарий по умолчанию.

## Лицензии и поставка

В этом обзоре чужой код не копировался; предлагается независимая реализация
контрактов и тестовых сценариев. У Crocodile-beep лицензия не найдена:
доступность исходников не даёт основания переносить их в наш репозиторий.
GPL-код Telegram X не предлагается включать в Apache-2.0 исходники.

TDLight уже используется как LGPL-зависимость, поэтому вопрос касается
**существующей поставки**, а не только будущего копирования фрагментов.
Перед следующим релизом нужно проверить лицензионные тексты, copyright notices,
исходники и условия замены/пересборки библиотеки для boot JAR, runtime bundles
и контейнера; отдельно проверить лицензии native/transitive компонентов.
Apache-2.0 файла проекта и SBOM недостаточно, чтобы считать этот пункт
автоматически закрытым. Это конкретная задача проверки артефактов,
не заключение о совместимости или нарушении лицензии.
[TDLight LICENSE](https://github.com/tdlight-team/tdlight-java/blob/e1a240e00a03b8867b1fbbb5c326ee6e194b4eae/LICENSE),
[дополнительный JTDLib notice](https://github.com/tdlight-team/tdlight-java/blob/e1a240e00a03b8867b1fbbb5c326ee6e194b4eae/JTDLIB_LICENSE),
[Telegram X LICENSE](https://github.com/TGX-Android/Telegram-X/blob/08ee6c2c5a9275dc51ecb004f336abc1982799d5/LICENSE).

## Изменение прежнего сравнения

[COMPARISON.md](COMPARISON.md) исправлен точечно: TDLib используется Telegram X,
но обобщение про все desktop clients убрано. Возможность шифрования у самой
TDLib отделена от фактической конфигурации нашей TDLight-обвязки: сервер
не объявляет свою session database зашифрованной настроенным секретным ключом.
Обзор не подтверждает общий тезис, что все прочие MCP-серверы небезопасны;
каждый конкурент требует проверки конкретной версии и конфигурации.
