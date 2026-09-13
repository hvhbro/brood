# asmmapper — Java+ASM автомаппер (способ Антона)

Старый `tools/automapper.py` не используется и не является референсом.
Агент (`jni/agent/src`) на этом этапе не обновляется — сначала маппер.

Способ:
1. Грузим дамп классов через ObjectWeb ASM (`lib/asm-9.7.jar`, `asm-tree-9.7.jar`).
2. Строим достижимость от `main` (`Reach.java`) — фильтр фейков-дублей.
3. Ищем МЕТОДЫ по поведению, из методов тащим ПОЛЯ (`Roles.java`).
4. Старого дампа и `classmap` не надо — всё на одном (новом) дампе.

MVP-роли: `entityPos` (setpos X/Y/Z + геттеры/сеттеры), `scaledRes`
(SCALE/WIDTH/HEIGHT), `singleton` (info, на проверку), строковые якоря
`movementSpeedAttr`, `keyBinding`.

Структура (`src/asmmapper/`): `Role.java` (интерфейс роли),
`Ctx.java` (контекст прогона), `RoleHit.java` (результат),
`RoleUtil.java` (общие приёмы), `*Role.java` (по файлу на роль),
`Roles.java` (тонкий оркестратор порядка), `DumpIndex/Reach/ClassInfo`,
`Main` (CLI + `--selftest`). Новая роль = новый `*Role.java` + строка
в `Roles.runAll()`, общий файл не раздувается.

Сборка/запуск (только JDK):
```
cd tools/asmmapper
build.bat
run.bat ..\..\newdump\minecraft out
```

Выход в `out/`: `classmap.json`, `membermap.json`, `review.json`,
`reachable.json`, `report.txt`. `review.json` — ручная очередь
(сравнение в декомпиляторе только этих).
