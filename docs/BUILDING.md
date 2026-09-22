# Сборка и проверка

## Требования

| Инструмент | Требование |
| --- | --- |
| JDK | 25 или новее |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.5 или новее |
| Fabric API | 0.161.0+26.2 |
| Gradle | используется wrapper проекта, отдельная установка не нужна |

Версии находятся в `gradle.properties`. `loom_version=1.17-SNAPSHOT` разрешается Fabric Loom через Gradle Plugin Portal; для воспроизводимой сборки используйте wrapper и зафиксированный `gradle-wrapper.properties`.

## Команды Windows

```text
gradlew.bat test
gradlew.bat clean build
gradlew.bat runServer
gradlew.bat publishToMavenLocal
```

На Linux/macOS те же команды запускаются через `./gradlew`.

`test` запускает JUnit 5. `build` дополнительно собирает remapped JAR и sources JAR. Артефакты:

```text
build/libs/navigation-ai-0.1.0.jar
build/libs/navigation-ai-0.1.0-sources.jar
```

Публикация в локальный Maven-репозиторий использует координаты:

```text
dev.navigationai:navigation-ai:0.1.0
```

## Ручная smoke-проверка

`runServer` создаёт рабочий каталог `run`. Перед первым полноценным запуском примите EULA:

```text
run/eula.txt -> eula=true
```

Проверяйте в логах загрузку `navigation_ai`, отсутствие ошибок регистрации fake-player и корректное завершение сервера. Smoke-run не заменяет нагрузочный прогон с реальными ботами.

Проверка восстановления активной навигации на сервере: запустите `navtest spawn persist`, назначьте `navtest goto-pos persist ...`, остановите сервер во время `COMPUTING`, затем запустите его снова. После стартовой задержки runtime должен показать ту же `PositionGoal` в состоянии `RUNNING` или `SUCCESS`. Для `FollowGoal` сохранённый UUID цели проверяется после создания всех сохранённых ботов.

## Проверка перед релизом

Для повторяемых игровых проверок добычи и боя используйте
[серверную регрессию](../tools/regression/README.md). Она запускается командой
`navtest regression` в отдельном тестовом мире и проверяет 36 сценариев.
Последний серверный прогон после полного плана: 36/36, API selftest: 22/22; текущий JUnit-набор: 23/23.
Нагрузочная команда `navtest load <1|10|25|50>` также прошла для всех четырёх
профилей. [Сохранённый журнал](../tools/regression/last-run.txt).

1. `gradlew.bat clean build` на JDK 25.
2. Запуск unit-тестов A* через `gradlew.bat test`.
3. Smoke-сервер Minecraft 26.2 с Fabric API.
4. Создание, движение, отмена и удаление одного бота.
5. Перезапуск мира и проверка восстановления NBT: позиции, инвентаря и активной навигационной цели.
6. Нагрузочная сцена с 1, 10, 25 и 50 ботами с измерением TPS и времени A*.

Если потребитель использует другой mapping namespace, проверяйте, что он компилируется против того же набора mappings, который используется проектом.
