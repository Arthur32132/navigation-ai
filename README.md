# Navigation AI

Серверная библиотека для Fabric/Minecraft 26.2. Она создаёт fake-player на стороне сервера, сохраняет его состояние и предоставляет API для физического управления и навигации. Клиентский мод для работы библиотеки не нужен.

Текущий артефакт — `navigation-ai:0.1.0`. Проект находится на ранней стадии, поэтому публичный API уже пригоден для интеграции, а часть расширенных политик навигации пока зарезервирована под следующие реализации.

## Быстрый старт

Требования:

- Minecraft Java 26.2;
- Fabric Loader 0.19.5 или новее;
- Fabric API `0.161.0+26.2` или совместимая сборка для 26.2;
- JDK 25 или новее.

Собрать библиотеку:

```text
gradlew.bat clean build
```

Готовый файл находится в `build/libs/navigation-ai-0.1.0.jar`. Положите его в `mods` серверного экземпляра вместе с Fabric API. Для мод-потребителя добавьте этот JAR в compile/runtime classpath и вызывайте API из server thread.

Минимальный пример:

```java
BotService bots = NavigationAiMod.runtime(server).service();
BotHandle guard = bots.create(BotCreateOptions.at(
    "Guard",
    server.overworld(),
    new Vec3(0.5, 80, 0.5)
));

NavigationTask task = guard.navigator().goTo(
    new NavigationGoal.PositionGoal(new Vec3(100.5, 80, 100.5)),
    NavigationPolicy.safeDefaults()
);
```

Подробные примеры и правила потоков находятся в [руководстве по интеграции](docs/INTEGRATION.md). Описание всех интерфейсов — в [API](docs/API.md), устройство runtime — в [архитектуре](docs/ARCHITECTURE.md).

## Документация

- [Индекс документации](docs/README.md)
- [Публичный API](docs/API.md)
- [Интеграция в другой мод](docs/INTEGRATION.md)
- [Архитектура и потоки](docs/ARCHITECTURE.md)
- [Сборка и проверка](docs/BUILDING.md)
- [Текущие ограничения и план развития](docs/LIMITATIONS.md)

## Что работает сейчас

- fake-player регистрируется в `ServerLevel` и `PlayerList`, виден серверным системам как `ServerPlayer`;
- серверная физика вызывается каждый тик через `ServerPlayer.doTick()`;
- доступны движение, прыжок, спринт, sneak и поворот;
- доступны разрушение одного блока и сбор заданного количества предметов через `Miner`/`ResourceCollector`;
- доступен бой с мобами и игроками через `CombatController`: меч, топор и лук;
- есть профили `STANDARD` и `EXPERT` с задержкой реакции, ограниченной скоростью поворота и разной точностью прицеливания;
- навигация использует ограниченный A* по неизменяемому снимку загруженной области мира;
- есть цели `BlockGoal`, `PositionGoal` и `FollowGoal`;
- поиск выполняется в bounded executor максимум с четырьмя worker-потоками;
- состояние игрока сохраняется в `navigation_ai_bots.dat` в корне мира и восстанавливается после перезапуска.

Установка блоков, генерация чанков и разрушение препятствий как часть навигационного пути пока не подключены к исполнителю действий. Их текущий статус и последствия для интегратора описаны в [ограничениях](docs/LIMITATIONS.md).

Явная добыча ресурсов уже доступна отдельным API: `bot.miner().breakBlock(...)` и `bot.collector().gather(...)`. Поиск использует только загруженные чанки текущего измерения и не прокладывает проходы.

## Проверка

```text
gradlew.bat test
gradlew.bat clean build
```

`runServer` предназначен только для ручной smoke-проверки. Перед первым полноценным запуском тестового сервера примите EULA в `run/eula.txt`.
