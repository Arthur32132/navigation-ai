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

Получайте сервис после запуска сервера. Все обращения к API в этом примере выполняются в server thread:

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

`NavigationTask.completion()` — `CompletableFuture`. Не изменяйте мир непосредственно из неизвестного callback-потока: передайте продолжение в `server.execute`.

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

Навигация открывает только деревянные двери и люки и проверяет `ServerPlayer.mayInteract(...)`. Разрушение препятствий и установка опор также подчиняются этому разрешению; в защищённой области боту нужны соответствующие права. Вода (включая текущую) разрешается политикой `allowWater`, лава не используется как проходная клетка.

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

Планировщик использует общий bounded executor максимум с четырьмя worker-потоками. Это предотвращает создание потока на каждого бота, но не отменяет стоимость серверной физики и регистрации сущностей. Для 10–50 ботов следует ограничивать частоту перепланирования, размер `snapshotRadius` и `maxSearchNodes`, а затем проверять нагрузку на реальной сцене.

## Удаление

```java
guard.navigator().cancel();
guard.remove();
```

При остановке сервера runtime сам сохраняет и удаляет активных ботов. Активные навигационные цели (`BlockGoal`, `PositionGoal`, `FollowGoal`) и их `NavigationPolicy` сохраняются в NBT и после запуска назначаются снова по свежему снимку мира. Задачи добычи, сбора и боя автоматически не возобновляются; недоступная цель `FollowGoal` пропускается.

Сборщик ресурсов обрабатывает неудачный подход (`FAILED`, `CANCELLED` или `STUCK`) как повод заново просканировать область и выбрать другую позицию, поэтому интегратору обычно достаточно наблюдать за `GatherTask` и его итоговым `GatherStatus`.
