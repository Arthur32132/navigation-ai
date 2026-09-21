# Публичный API

## Совместимость

| Компонент | Версия проекта |
| --- | --- |
| Minecraft | `26.2` |
| Fabric Loader | `0.19.5+` |
| Fabric API | `0.161.0+26.2` |
| Java | `25+` |
| Артефакт | `dev.navigationai:navigation-ai:0.1.0` |

Основные типы находятся в пакете `dev.navigationai.api`:

- `BotService` — создание и поиск ботов;
- `BotHandle` — ссылка на конкретного бота;
- `BotActions` — прямой ввод игрока;
- `Navigator` — постановка и отмена задач;
- `NavigationGoal`, `NavigationPolicy` — цель и ограничения поиска;
- `NavigationTask`, `NavigationResult`, `NavigationStatus` — наблюдение за задачей.

## Получение сервиса

`NavigationAiMod` регистрирует runtime на жизненном цикле Fabric-сервера. В обработчике серверного события сервис можно получить так:

```java
BotService bots = NavigationAiMod.runtime(server).service();
```

`runtime(server)` допускает ленивую инициализацию, но проверяет, что передан именно активный `MinecraftServer`.

## Создание бота

```java
BotCreateOptions options = BotCreateOptions
    .at("Guard", server.overworld(), new Vec3(0.5, 80, 0.5))
    .withRotation(180.0f, 0.0f);

BotHandle bot = bots.create(options);
```

`BotCreateOptions` проверяет имя длиной от 1 до 16 символов. UUID создаётся автоматически через `at`, либо может быть передан явно конструктору записи для стабильной идентичности. При повторном UUID `create` выбрасывает `IllegalArgumentException`.

После создания `BotHandle` предоставляет:

```java
bot.id();          // UUID
bot.name();        // имя
bot.player();      // ServerPlayer
bot.state();       // SPAWNING, ACTIVE, DEAD или REMOVED
bot.actions();     // управление вводом
bot.navigator();   // навигация
bot.remove();      // удаление из мира и PlayerList
```

Бот является `ServerPlayer`, поэтому его инвентарь, эффекты, позиция, поворот и другие данные игрока доступны через стандартный Minecraft API. Сетевого клиента у него нет: исходящие пакеты подавляются внутренним listener-ом.

## Потоки

`BotService.create`, `BotHandle.remove`, все методы `BotActions` и вызовы навигатора должны выполняться в server thread. Если код вызывается из worker-потока, перенесите его через `server.execute(...)`. Нарушение этого правила приводит к `IllegalStateException` или к гонкам с состоянием мира.

Сам A* выполняется асинхронно, но только над снимком мира, который был снят в server thread. Результат возвращается обратно в server thread перед изменением игрока.

## Цели навигации

```java
NavigationPolicy policy = NavigationPolicy.safeDefaults();

NavigationTask toBlock = bot.navigator().goTo(
    new NavigationGoal.BlockGoal(new BlockPos(100, 80, 100)),
    policy
);

NavigationTask toPosition = bot.navigator().goTo(
    new NavigationGoal.PositionGoal(new Vec3(100.5, 80, 100.5)),
    policy
);

NavigationTask follow = bot.navigator().follow(
    new NavigationGoal.FollowGoal(targetPlayer, 3.0, 4.0),
    policy
);
```

`FollowGoal.repathDistance` задаёт расстояние, после которого путь пересчитывается. Цель должна находиться в том же измерении, что и бот. `BlockGoal.tolerance`, `PositionGoal.tolerance` и `FollowGoal.distance` уже присутствуют в API, но в текущем runtime ещё не влияют на условие завершения; это задел для следующего навигационного исполнителя.

Одновременно у бота выполняется одна задача. Новый вызов `goTo` отменяет предыдущую, очищает старый путь и запускает новый поиск. `cancel()` останавливает ввод и переводит задачу в `CANCELLED`.

## Состояния задачи

| Статус | Значение |
| --- | --- |
| `IDLE` | Задача ещё не запущена |
| `COMPUTING` | Снимок собран, A* выполняется или выполняется перепланирование |
| `RUNNING` | Путь принят, бот подаёт ввод каждый тик |
| `SUCCESS` | Цель достигнута |
| `FAILED` | Путь не найден или задача завершена с ошибкой |
| `CANCELLED` | Задача отменена вызывающим кодом или заменена новой |
| `STUCK` | Зарезервировано для отдельного публичного результата; текущий runtime при отсутствии прогресса запускает перепланирование |

```java
NavigationTask task = bot.navigator().goTo(goal, policy);
task.completion().whenComplete((result, error) -> server.execute(() -> {
    if (error != null) {
        // обработать ошибку интеграции
        return;
    }
    if (result.status() == NavigationStatus.SUCCESS) {
        // продолжить серверную логику
    }
}));
```

`NavigationResult` содержит статус, строковую причину, число посещённых узлов и длительность поиска/движения в наносекундах. Для типичного успеха причина равна `reached_goal`, для отмены — `cancelled`, для недостижимой цели — `unreachable`.

## Прямые действия

```java
bot.actions().look(90.0f, 0.0f);
bot.actions().setMovement(0.0f, 1.0f, false, true, false);
bot.actions().stop();
```

`sideways` и `forward` ограничиваются диапазоном `[-1, 1]`; `pitch` ограничивается диапазоном `[-90, 90]`. Ввод передаётся в стандартную серверную физику во время тика бота. Прямой ввод и навигатор могут конфликтовать, поэтому после ручного управления обычно следует вызвать `navigator().cancel()`.

## Политика поиска

```java
NavigationPolicy policy = new NavigationPolicy(
    false, // allowBreaking
    false, // allowPlacing
    true,  // allowWater
    false, // allowChunkGeneration
    40_000,
    2_048,
    32,
    100.0
);
```

Сейчас реально применяются `allowWater`, `maxSearchNodes` и `snapshotRadius`. Снимок ограничен локальной областью вокруг бота; если цель выходит за этот радиус, поиск завершается как недостижимый. Остальные поля сохраняются в API и валидируются, но требуют следующих подсистем:

- `allowBreaking` и `allowPlacing` — исполнитель действий с проверкой инструментов, инвентаря и прав;
- `allowChunkGeneration` — безопасная выдача chunk tickets и работа с незагруженными чанками;
- `maxPathLength` — ограничение длины восстановленного пути;
- `dangerCost` — оценка опасных блоков и эффектов.

`NavigationPolicy.safeDefaults()` не разрешает разрушение, установку и генерацию чанков, но разрешает движение по воде и ограничивает снимок радиусом 32 блока.

## Жизненный цикл и сохранение

Runtime создаётся на `SERVER_STARTED`, обновляет ботов на каждом `START_SERVER_TICK` и сохраняет их на `SERVER_STOPPING`. Файл сохранения: `<корень мира>/navigation_ai_bots.dat`.

Восстанавливаются UUID, имя, измерение, позиция, поворот, инвентарь, эффекты и остальные данные `ServerPlayer`. Активные `NavigationTask` намеренно не сохраняются: после запуска вызывающий мод должен назначить их заново.
