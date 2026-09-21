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
- `Miner` и `MiningTask` — разрушение одного блока через серверную механику;
- `ResourceCollector` и `GatherTask` — поиск, добыча и подсчёт предметов;
- `CombatController` и `CombatTask` — бой с мобом или игроком;
- `CombatPolicy`, `CombatSkill`, `CombatWeaponMode`, `CombatResult`, `CombatStatus` — ограничения и профиль поведения;
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
bot.combat();      // бой
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

`FollowGoal.repathDistance` задаёт расстояние, после которого путь пересчитывается. Цель должна находиться в том же измерении, что и бот. `BlockGoal.tolerance` и `PositionGoal.tolerance` задают радиус прибытия, а `FollowGoal.distance` — дистанцию удержания; follow остаётся активным после первого приближения и возобновляет движение, когда цель ушла за пределы дистанции перепланирования.

Runtime сохраняет активные навигационные цели и все поля `NavigationPolicy` в `<world>/navigation_ai_bots.dat`. После перезапуска задача запускается заново и получает свежий снимок мира; старый путь не используется. Завершённые, отменённые и застрявшие задачи не сохраняются. Для `FollowGoal` записывается UUID игрока, поэтому цель восстанавливается только если игрок снова доступен.

Одновременно у бота выполняется одна задача. Новый вызов `goTo` отменяет предыдущую, очищает старый путь и запускает новый поиск. `cancel()` останавливает ввод и переводит задачу в `CANCELLED`.

## Сражения

Бой запускается с явно выбранной живой целью. Целью может быть `Mob` или
`Player`; политика отдельно разрешает эти два типа. Одновременно выполняется
только одна боевая задача, а новый бой останавливает навигацию, добычу и сбор.

```java
CombatTask combat = bot.combat().fight(targetLivingEntity, CombatPolicy.standard());
combat.completion().thenAccept(result -> server.execute(() -> {
    if (result.status() == CombatStatus.SUCCESS) {
        // цель побеждена
    }
}));

CombatTask areaCombat = bot.combat().fightNearby(16.0, CombatPolicy.expert());
```

По умолчанию политика использует лучший доступный меч, затем лук; если нет ни
меча, ни лука, в режиме `AUTO` сохраняется запасной вариант с топором. Пустая
рука также автоматически заменяется оружием из обычного инвентаря. Для мода,
которому разрешён только один тип оружия, используйте копию политики:

```java
CombatPolicy swordOnly = CombatPolicy.standard().swordOnly();
CombatPolicy bowOnly = CombatPolicy.expert().bowOnly();
// То же самое: policy.withWeapon(CombatWeaponMode.SWORD/BOW)
```

`CombatWeaponMode.SWORD` и `BOW` являются строгими режимами: при отсутствии
подходящего оружия задача завершается с ошибкой и не переключается на другой
тип. Старый конструктор `CombatPolicy` из семи аргументов сохраняет режим
`AUTO` для совместимости.

`CombatPolicy.expert()` выбирает более быструю реакцию, плавный поворот и более
узкую погрешность прицеливания. `CombatPolicy.standard()` оставляет больше
ошибок и задержку реакции. Оба профиля обязаны соблюдать видимость, дальность,
ванильный cooldown и реальные правила оружия.

Контроллер распознаёт мечи по идентификатору предмета, топоры по `AxeItem`, а
луки по `BowItem`. Меч и топор атакуют через `ServerPlayer.attack`, лук проходит
через `BowItem` и потребляет реальные стрелы. Перед каждым ударом контроллер
плавно меняет yaw/pitch, проверяет луч до цели и атакует только если текущий
взгляд уже совпадает с целью. Поэтому мгновенное попадание в бок или за спину
невозможно. Смена оружия и натяжение лука прекращаются при `cancel()`, смерти,
удалении цели или выходе за пределы политики.

`fightNearby(radius, policy)` последовательно выбирает ближайшие разрешённые
цели в радиусе. Игроки в creative и spectator автоматически пропускаются;
после смерти или выхода цели из области выбирается следующая. Задача завершается
успешно после короткого ожидания, если разрешённых живых целей больше не осталось;
это позволяет мобу появиться в области сразу после запуска команды.

Для ручной проверки в тестовом моде доступны:

```text
/navtest fight <bot> <entity> <standard|expert>
/navtest combat-status <bot>
/navtest cancel <bot>
```

## Состояния задачи

| Статус | Значение |
| --- | --- |
| `IDLE` | Задача ещё не запущена |
| `COMPUTING` | Снимок собран, A* выполняется или выполняется перепланирование |
| `RUNNING` | Путь принят, бот подаёт ввод каждый тик |
| `SUCCESS` | Цель достигнута |
| `FAILED` | Путь не найден или задача завершена с ошибкой |
| `CANCELLED` | Задача отменена вызывающим кодом или заменена новой |
| `STUCK` | Бот не продвинулся после трёх перепланирований и остановлен с причиной `stuck` |

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

## Добыча ресурсов

Простое разрушение блока выполняется через `bot.miner()`. Во время добычи бот каждый тик поворачивает голову к центру целевого блока и только после этого выполняет серверный прогресс разрушения:

```java
MiningTask mining = bot.miner().breakBlock(new BlockPos(100, 12, 100));
mining.completion().thenAccept(result -> server.execute(() -> {
    if (result.status() == MiningStatus.SUCCESS) {
        // блок уничтожен, ванильный дроп создан
    }
}));
```

Сбор заданного количества ресурса объединяет поиск блоков, подход к соседней свободной позиции, выбор подходящего инструмента, разрушение и ожидание подбора:

```java
GatherTask gathering = bot.collector().gather(
    new GatherRequest(Blocks.COAL_ORE, Items.COAL, 8),
    GatherPolicy.defaults());
```

Для нескольких вариантов блока или дропа используйте `GatherRequest.ofTags(blockTag, itemTag, count)`.

Поиск ограничен загруженными чанками и радиусом политики. Уже имеющийся в инвентаре предмет не входит в прогресс; результат считает только изменение количества выбранного предмета после запуска задачи. Вызов `cancel()` прекращает навигацию, добычу и движение. Задачи добычи и сбора не сохраняются в NBT; после перезапуска их нужно назначить снова.

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

Все поля политики участвуют в базовом исполнителе. `allowBreaking` разрешает разрушать проходимые после удаления препятствия клетки через общий `Miner`; `allowPlacing` разрешает устанавливать обычные полные блоки под поддерживаемыми переходами. `allowChunkGeneration` включает ограниченную загрузку/генерацию чанков в пределах снимка; один снимок при этом ограничен радиусом 16 блоков. `maxPathLength` ограничивает число шагов сегмента, а `dangerCost` добавляется к клеткам огня, магмы, кактуса, лавы и другим отмеченным опасным клеткам, включая клетки рядом с живыми сущностями.

Для дальних целей снимки строятся сегментами. Очередь поиска ограничена 64 запросами, поэтому массовое перепланирование завершается с причиной `search_queue_full`, а не создаёт неограниченный backlog.

`NavigationPolicy.safeDefaults()` не разрешает разрушение, установку и генерацию чанков, но разрешает движение по воде и ограничивает снимок радиусом 32 блока.

## Жизненный цикл и сохранение

Runtime создаётся на `SERVER_STARTED`, обновляет ботов на каждом `START_SERVER_TICK` и сохраняет их на `SERVER_STOPPING`. Файл сохранения: `<корень мира>/navigation_ai_bots.dat`.

Восстанавливаются UUID, имя, измерение, позиция, поворот, инвентарь, эффекты и остальные данные `ServerPlayer`. Активные цели навигатора и их `NavigationPolicy` также сохраняются; после запуска путь строится заново по текущему миру. Задачи добычи, сбора и боя не возобновляются автоматически. Для `FollowGoal` цель должна снова быть доступна по сохранённому UUID или имени.
