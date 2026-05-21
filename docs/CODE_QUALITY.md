# Критерии качества кода — IDE Introspector

Свод правил на основе анализа кодовой базы и актуальных best practices
(kotlinlang.org, IntelliJ SDK 2024–2026, Effective Kotlin, Kotest/MockK/JUnit 5).

Правила в [CLAUDE.md](../CLAUDE.md) (Hard rules: таймаут 10 s, MCP API target,
`compileOnly` для kotlinx-serialization, exec — последнее средство) остаются
авторитетными и не дублируются ниже.

---

## 1. Kotlin — общий код

### 1.1 Null safety и иммутабельность
- **`!!` запрещён** в production-коде. Используй `?.`, `?:`,
  `requireNotNull(x) { "осмысленное сообщение" }`. Допустимо только если инвариант
  задокументирован и недостижим на практике.
- **`val` по умолчанию**, `var` — только при необходимости мутации. Возвращай
  `List`/`Map`, не `MutableList`/`MutableMap`, из публичных API.
- `as?` вместо `as`. Платформенные типы (`String!` из Java) приводи к `T?` сразу
  на границе.
- `data class.copy(...)` для "обновления" — не выставляй мутирующие сеттеры на
  доменных моделях.

### 1.2 Scope-функции — по назначению, не для красоты

| Цель | Функция |
|---|---|
| null-safe трансформ | `?.let { … }` |
| настройка объекта (DSL-like) | `apply { … }` |
| init + результат | `run { … }` |
| побочный эффект в цепочке (лог) | `also { … }` |
| группа вызовов на готовом объекте | `with(obj) { … }` |

Не вкладывай scope-функции друг в друга; не используй `apply` ради одной строки.

### 1.3 Типы
- `data class` — только value carriers (DTO). Не добавляй поведение, не выставляй
  `var`.
- `sealed interface` для закрытых иерархий (results, ADT). `when` без `else` —
  компилятор сам подскажет недостающую ветку.
- `@JvmInline value class` для type-safety над одиночным примитивом
  (`ComponentId(String)`, `EpName(String)`).
- `enum class` — только для констант без per-instance данных.

### 1.4 Coroutines (structured concurrency)
- **`GlobalScope`, `runBlocking` на EDT — запрещены.** В IDE используй scope
  сервиса (constructor injection — см. §2.3).
- `withContext(Dispatchers.IO/Default/EDT)` для переключения, не `launch().join()`.
- `withTimeoutOrNull` для recoverable, `withTimeout` для ошибки. Все таймауты
  ≤ 10 с (см. CLAUDE.md).
- `suspend fun` читается как действие: `fetchUser()`, не `getUserSuspending()`.
- В CPU-циклах вставляй `yield()` для cancellation.

### 1.5 Видимость и пакеты
- **По умолчанию `private`**, затем `internal`, и только нужное — `public`.
  Особенно важно для плагина: `internal` ограничивает API-поверхность.
- Один публичный top-level класс на файл; sealed-дети группируй в один файл с
  родителем.
- Пакеты — по фиче (`core/`, `tools/`, `model/`), не по слою.

### 1.6 Именование
- Классы — `UpperCamelCase`, функции/поля — `lowerCamelCase`, `const val` —
  `UPPER_SNAKE_CASE`.
- Backing properties: `_value` (private) + `value` (public). Boolean: `is…`,
  `has…`, `should…`.
- Не пиши комментарии, объясняющие *что* делает код — имена должны это сделать.
  Комментарий — только для *почему* (скрытый инвариант, workaround).

### 1.7 Extension functions — оправдано, не злоупотреблять
- **Оправдано:** добавить intent-revealing метод к типу, который ты не
  контролируешь (`String.toSlug()`); DSL; замена `Utils`-классов.
- **Антипаттерн:** расширения `Any?`, `Any`; расширения, которые должны быть
  членом класса; расширения с зависимостью от приватного состояния.
- Группируй в файле, названном по receiver-у (`StringExtensions.kt`).

---

## 2. IntelliJ Platform Plugin

### 2.1 Threading (правила из IntelliJ SDK 2024.1+)
- **EDT — только Swing-мутации.** Любая другая работа на EDT — нарушение.
  Используй `onEdtBlocking { }` (`util/EdtHelpers.kt`).
- **Чтение PSI/индексов — в `ReadAction` или `readAction { }`** (suspending,
  since 2024.1). Любые PSI-операции (даже `getName()`) требуют RA.
- **`PsiElement` нельзя удерживать между ReadAction-ами** — между ними PSI может
  быть переразобран. Используй
  `SmartPointerManager.createSmartPsiElementPointer(psi)` и проверяй `.isValid()`
  после ресолва.
- **`ModalityState.any()` только для чисто UI-операций** на EDT при открытом
  модальном диалоге (наш case с exec-confirmation). Изменения PSI/VFS/project
  model под `any()` — UB.
- **Не пиши тяжёлые операции на EDT** — `SlowOperations.assertSlowOperationsAreAllowed()`
  поймает. Уйди в BG, а не подавляй ассерт.

### 2.2 PSI и dumb mode
- Помечай action `DumbAware` **только если он не трогает индексы**. Расширяй
  `DumbAwareAction`, не переопределяй `isDumbAware()`.

### 2.3 Сервисы
- **`@Service(Service.Level.PROJECT|APP)`** + `final` класс (Kotlin default).
  Без записи в plugin.xml для light services.
- **Никогда не кешируй сервис в поле** другого класса —
  `project.getService(Foo::class.java)` на каждый вызов (registry — thread-safe).
- **Constructor injection scope:** `class Foo(private val cs: CoroutineScope)`
  для app-сервиса, `(project: Project, cs: CoroutineScope)` для project-сервиса.
  IDE сам кэнсельнёт scope при unload.
- **Никакой тяжёлой работы в конструкторе** — он блокирует первый вызов. Делай
  `suspend init()` и запускай через `cs.launch`.
- **Никогда не используй `Application`/`Project` как parent `Disposable`** —
  утечка при unload плагина.

### 2.4 Extension points
- **`ep.point.size()`** для подсчёта (adapter count, без инстанциирования).
  **Никогда `ep.extensionList.size`** — см. CLAUDE.md, ломает другие плагины.
- Объявляй EP как
  `private val EP_NAME = ExtensionPointName.create<T>("…")`.
- Не кешируй extension instances — динамические плагины этого не переживут.

### 2.5 plugin.xml
- `<depends optional="true" config-file="myPluginId-kotlin.xml">org.jetbrains.kotlin</depends>`
  — наш паттерн с `kotlin-exec.xml` и `mcp-integration.xml`.
- `<idea-version since-build="252" until-build="252.*"/>` — без
  `pluginUntilBuild=false`.

### 2.6 Логирование и ошибки
- `private val LOG = Logger.getInstance(MyClass::class.java)` (или
  `thisLogger()`). **Запрещены `println`, `System.out`.**
- `LOG.warn(t)` / `LOG.error(t)` — попадает в IDE Internal Error reporter.
  Используй `PluginException` для атрибуции к нашему плагину.
- `LOG.debug` — оборачивай `if (LOG.isDebugEnabled)` для дорогих сообщений.

---

## 3. MCP tool descriptions

Уже задано в CLAUDE.md, явно фиксирую как правило:

1. **What** (одна строка, present tense, action + scope).
2. **Use this when** — конкретные интенты.
3. **Do NOT use this when** — указатели на альтернативные tools.
4. **Returns** — форма JSON, ключевые поля.
5. **Examples** — runnable invocations для нетривиальных tools.

Технические требования:
- Kotlin trim-margin (`""" |line… """`) — framework вызывает `trimMargin` через
  рефлексию.
- `@McpDescription` на **каждом** параметре (без исключений).
- Возвращаемый тип — `@Serializable data class` в `model/`.

---

## 4. Тесты

### 4.1 Стек
- **JUnit 5 (Jupiter) baseline** для всего нового. Текущий код на JUnit 4 —
  миграция оппортунистически, не massive refactor.
- **MockK > Mockito** — нативная поддержка `suspend`, extension functions,
  `mockkObject`.
- **Kotest** — опционально для чисто-Kotlin модулей (`core/`, `util/`). **Не
  смешивать со `BasePlatformTestCase`** — конфликт lifecycle.

### 4.2 Выбор test base

| Что тестируешь | База |
|---|---|
| Pure logic (XPathMatcher, ImageBudget, TtlCache) | plain JUnit/Kotest |
| PSI / fixture / completion | `BasePlatformTestCase` (быстрая, shared project) |
| Multi-module, real SDK | `HeavyPlatformTestCase` (только при необходимости) |
| Disposable без `Project` | `UsefulTestCase` |

Сейчас ~60% тестов — platform; пытайся выносить логику в pure-классы, чтобы
тестировать их без IDE.

### 4.3 Naming
- **Backticks**, описание поведения:
  `` `walker stops at maxDepth and reports truncation`() ``.
- Структура: либо `method_state_expectedBehavior`, либо
  `` `given X, when Y, then Z` `` — выбери одну и держись.
- `testFoo1` / `testCase2` — антипаттерн.

### 4.4 Структура теста
- **Arrange-Act-Assert**, три блока через пустую строку. Один Act per test —
  если их несколько, разбей.
- Тяжёлый setup — в `@BeforeEach` или factory-функции, не в каждом тесте.
- Никакой логики в тесте (`if`, `for` для решения, что ассертить) —
  параметризуй (`@ParameterizedTest` / Kotest `withData`).

### 4.5 Coroutines
- `runTest { … }` + `StandardTestDispatcher` для детерминизма. `advanceTimeBy`
  / `advanceUntilIdle`.
- **Инжектируй диспетчеры** — никаких хардкод `Dispatchers.IO` в production.
- Не миксуй `runTest` с `onEdtBlocking` — EDT real-threaded, virtual time не
  работает.

### 4.6 Testdata
- `src/test/testData/<feature>/before.kt` + `after.kt`, `<caret>`/`<selection>`
  маркеры, `myFixture.configureByFile(...)` / `checkResultByFile(...)`.
- Не assert pixel-layout в UI-тестах — assert на структуру дерева компонентов.

### 4.7 Антипаттерны
- **Mock everything** — мокаем только границы (FS, network, IDE-сервисы, время).
  Свои data classes и pure functions — нет.
- **Тестирование private через reflection** — извлеки в `internal` класс с
  публичным API.
- **Hidden dependencies** — никаких `System.getenv`, real clock, real network.
  Инжектируй.
- **Assertion roulette** — голые `assertTrue` без сообщений. Используй AssertJ
  или Kotest fluent assertions, либо `assertSoftly`.
- **`Thread.sleep` для синхронизации** — замени на
  `CountDownLatch.await(timeout)`, `advanceUntilIdle()`, `waitForCondition`.
- **Brittle full-JSON-equality** для EDT-collected trees — assert по стабильным
  структурным полям.

### 4.8 Coverage
- 70-80% на core logic — здраво. Выше — погоня за процентом.
- Исключи из Kover (уже сделано): `tools/` (McpToolset реестр), `model/`
  (data classes), `toolwindow/`, KSP-генерируемое.
- При желании — PIT mutation testing на core, только на changed files в CI.

---

## Куда применять

Эти правила — критерий для:
- Code review (само-проверка перед commit).
- Принятия решений в спорных моментах ("надо ли мокать сервис?", "куда положить
  функцию?").
- Контекста при работе с агентами (`/review`, `/security-review`).
