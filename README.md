# Tungo AI Kotlin SDK (`kz.tungo:tungo-sdk-kotlin`)

Официальный Kotlin SDK для публичного API платформы **Tungo AI** (Kotlin 2.0+).

## Быстрый старт

```kotlin
import kz.tungo.sdk.kotlin.TungoClient
import kz.tungo.sdk.kotlin.TungoMoney

val client = TungoClient(apiKey = "tg_live_...", companyId = "co_...")
val ans = client.ask("Покажи дебиторку за октябрь")
println(TungoMoney.formatTiyn(TungoMoney.toTiyn(45000.0)))
```

## Полный справочник ресурсов и методов

<!-- sdkgen:methods -->
Автоматически сгенерировано из OpenAPI-контракта Tungo Platform API: **13 ресурсов, 82 метода, 22 вебхука**.

| Ресурс | Описание | Методы |
| --- | --- | --- |
| `client.workspaces` | Организации (workspace), доступные ключу API | `list` |
| `client.companies` | Компании организации и их карточки | `list` · `get` |
| `client.platform` | Баланс платформы, книга движений, отчёты о расходе, лимиты, аудит и тарифы моделей | `listUsageRates` · `getAccount` · `getAudit / getAuditAll` · `getLedger / getLedgerAll` · `listLimits` · `listRequests / listRequestsAll` · `getUsage` |
| `client.apiKeys` | Сведения о текущем ключе API и статистика его расхода | `getCurrent` · `getCurrentUsage` · `getUsage` |
| `client.webhooks` | Исходящие вебхуки организации (Svix): адреса, каталог событий, доставки и проверка подписи | `listEndpoints / listEndpointsAll` · `createEndpoint` · `getEndpoint` · `updateEndpoint` · `deleteEndpoint` · `listEndpointAttempts / listEndpointAttemptsAll` · `rotateEndpointSecret` · `testEndpoint` · `listEventTypes` · `listMessages / listMessagesAll` · `listMessageAttempts / listMessageAttemptsAll` · `resendMessage` · `getUsage` |
| `client.chat` | Диалоги с ИИ-бухгалтером: вопросы, потоковый чат (SSE), подсчёт токенов и история | `list / listAll` · `create / createStream` · `countTokens` · `get` · `update` · `delete` · `answer / answerStream` · `listEvents` · `stop` |
| `client.proposals` | Предложения документов ИИ-бухгалтера: просмотр, одобрение записи в 1С и отклонение | `list / listAll` · `approveBatch` · `get` · `approve` · `reject` |
| `client.feed` | Лента событий и предложений компании (страницы и живой поток SSE) | `get / getAll` · `listEvents` |
| `client.batches` | Пачки обработки банковских выписок, входящих ЭСФ и DirectBank | `list / listAll` · `createBankStatement` · `createEsfPurchases` · `get` · `listEvents` · `stop` · `getSummary` · `listStatements / listStatementsAll` · `pollDirectbank` |
| `client.esf` | Входящие и исходящие электронные счета-фактуры (ИС ЭСФ) | `getIncoming / getIncomingAll` · `getOutgoing / getOutgoingAll` |
| `client.forms` | Налоговые формы (ФНО 100.00 и др.): сборка, проверка ИСНА и разрывы данных | `list` · `build` · `listDataGaps` · `getIsna` · `listNotes` |
| `client.onec` | Состояние подключения компании к базе 1С | `getStatus` |
| `client.automations` | Автоматизации компании: входящие хуки, сценарии, цепочки шагов, запуски и уровень автономности ИИ | `postHook` · `listHooks` · `createHook` · `getHook` · `updateHook` · `deleteHook` · `rotateHook` · `testHook` · `listRuns / listRunsAll` · `getRun` · `cancelRun` · `retryRun` · `listScenarios` · `createScenario` · `getScenario` · `putScenario` · `updateScenario` · `deleteScenario` · `runScenario` · `getState` · `putState` · `getAutonomy` · `putAutonomy` |
<!-- /sdkgen:methods -->
