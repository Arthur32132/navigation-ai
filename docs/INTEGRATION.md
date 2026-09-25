# Интеграция в другой серверный мод

## Подключение JAR

Для локальной разработки положите `navigation-ai-0.1.0.jar` в каталог `libs` своего мода и добавьте его в Gradle classpath. Для Fabric Loom обычно достаточно:

```groovy
dependencies {
    modCompileOnly files("libs/navigation-ai-0.1.0.jar")
    modRuntimeOnly files("libs/navigation-ai-0.1.0.jar")
}
```

В `fabric.mod.json` мода-потребителя добавьте зависимость, чтобы Fabric загрузил библиотеку раньше вашего entrypoint:

```json
"depends": {
  "navigation_ai": ">=0.1.0"
}
```

На сервере библиотека должна находиться в `mods` вместе с Fabric API. Fabric Loader проверит Minecraft 26.2, Java 25+ и наличие Fabric API по `fabric.mod.json`.

## Инициализация

Получайте сервис после запуска сервера. Все изменяющие состояние обращения к API — создание/удаление, прямые действия, запуск и отмена задач — выполняются в server thread:

```java
public final class GuardMod implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BotService bots = NavigationAiMod.runtime(server).service();
            BotHandle guard = bots.create(BotCreateOptions.at(
                "Guard",
                server.overworld(),
                new Vec3(0.5, 80, 0.5)
            ));

            guard.navigator().goTo(
                new NavigationGoal.PositionGoal(new Vec3(100.5, 80, 100.5)),
                NavigationPolicy.safeDefaults()
            );
        });
    }
}
```

В реальном моде удобно хранить `BotHandle` по UUID или по собственной роли. `BotService.find(...)` возвращает `Optional`, а `bots()` возвращает снимок коллекции, который можно безопасно перебирать в текущем тике.

## Обработка результата

`NavigationTask.completion()` — `CompletableFuture`. `NavigationTask.cancel()`, `MiningTask.cancel()`, `GatherTask.cancel()`, `CombatTask.cancel()` и методы `BotActions` также изменяют мир и требуют server thread. Не изменяйте мир непосредственно из неизвестного callback-потока: передайте продолжение в `server.execute`.

```java
NavigationTask task = guard.navigator().goTo(goal, policy);
task.completion().whenComplete((result, error) -> server.execute(() -> {
    if (error != null || result.status() != NavigationStatus.SUCCESS) {
        guard.actions().stop();
        return;
    }
    // Следующая серверная команда после прибытия.
}));
```

## Следование

```java
NavigationTask follow = guard.navigator().follow(
    new NavigationGoal.FollowGoal(targetPlayer, 3.0, 4.0),
    NavigationPolicy.safeDefaults()
);
```

`repathDistance` определяет, насколько далеко должен переместиться target, чтобы запустить новый поиск. Следование между измерениями не поддерживается текущим runtime.

Навигация открывает вручную открываемые двери и люки, включая медные варианты с ручным открытием, и проверяет `ServerPlayer.mayInteract(...)`. Разрушение препятствий и установка опор также подчиняются этому разрешению; в защищённой области боту нужны соответствующие права. Вода (включая текущую) разрешается политикой `allowWater` и получает повышенную стоимость, поэтому сухой путь предпочтительнее, но длинный водный маршрут допустим; лава не используется как проходная клетка. Спринтерские прыжки через ямы до трёх блоков, включая диагональные, и через стену высотой в один блок выполняются автоматически; опасные клетки, заборы, закрытые двери и более высокие препятствия не считаются безопасным прыжком. В низких проходах бот удерживает sneak.

## Несколько десятков ботов

Создавайте ботов пакетно в server thread, например во время `SERVER_STARTED` или через `server.execute`. Не создавайте их из собственного worker-потока.

```java
for (int i = 0; i < 50; i++) {
    BotHandle bot = bots.create(BotCreateOptions.at(
        "worker_" + i,
        server.overworld(),
        new Vec3(0.5 + i, 80, 0.5)
    ));
    bot.navigator().goTo(target, NavigationPolicy.safeDefaults());
}
```

Планировщик использует общий priority scheduler максимум с четырьмя worker-потоками и очередью на 64 запроса. Прямые цели получают приоритет над перестроениями `FollowGoal`, отменённые запросы удаляются из очереди. Снимок мира собирается на серверном потоке порциями: максимум 4096 позиций за тик для всех ботов и 2048 за вызов одного бота; одновременно стартует не более четырёх новых снимков за тик. Первичный горизонтальный радиус снимка не превышает 16 блоков и для близкой цели уменьшается до расстояния до неё плюс четыре блока, но не ниже восьми при достаточно большой политике. Если путь к близкой цели не найден, радиус расширяется до `snapshotRadius`; по вертикали снимок может охватывать до 32 блоков в сторону цели. `snapshotRadius` принимается только в диапазоне 4..64 как верхний предел, `maxSearchNodes` — 128..200000, `maxPathLength` — 1..16384. Для 10–50 ботов следует проверять нагрузку на реальной сцене.

Для проверки нагрузки интегратор может читать `NavigationAiMod.runtime(server).metrics()` и
логировать `averageTickMillis()`, `averagePathSearchMillis()`, `queuedSearches()` и
`replans()`. Снимок безопасно сохранять или передавать в собственную систему мониторинга.

## Удаление

```java
guard.navigator().cancel();
guard.remove();
```

При остановке сервера runtime сначала останавливает контроллеры, сохраняет активные навигационные цели и журнал уже размещённых блоков атомарно в NBT, а затем удаляет fake-player через vanilla `PlayerList.remove`. Обычная отмена/ошибка откатывает журнал; после перезапуска он восстанавливается вместе с целью до построения нового пути. Откат меняет только те клетки, где сохранилось состояние блока, установленное ботом. Активные навигационные цели всех типов (`BlockGoal`, `PositionGoal`, `FollowGoal`, `LookAtGoal`, `ReturnGoal`, `PatrolGoal`, `EscortGoal`, `MultiGoal`, `DimensionGoal`) и их `NavigationPolicy` после запуска назначаются снова по свежему снимку мира. Задачи добычи, сбора и боя автоматически не возобновляются; недоступная цель игрока для follow/escort пропускается.

Сборщик ресурсов обрабатывает неудачный подход (`FAILED`, `CANCELLED` или `STUCK`) как повод заново просканировать область и выбрать другую позицию, поэтому интегратору обычно достаточно наблюдать за `GatherTask` и его итоговым `GatherStatus`.
