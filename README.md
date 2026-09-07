# rml-broodcheat

Research-проект по реверс-инжинирингу защищённой JVM-сборки (форк Minecraft
1.12.2 с протектором Astraea) и написанию JNI-агента поверх неё.

> Только для образовательных/исследовательских целей.

## Что это

Нативная DLL инъектируется в запущенный процесс игры через `jvm.dll` и
запускает Java-агент внутри JVM, минуя защиту протектора:

1. **Attach** — через валидную таблицу JNIEnv (RVA-оффсеты калибруются по дампам).
2. **DR0 + VEH bypass** — hardware-breakpoint на входе валидатора
   protected-классов (`jvm.dll+0x1C2D0`) подменяет результат проверки,
   память jvm.dll при этом не изменяется.
3. **DefineClass** — все классы агента грузятся из защищённого формата
   прямо из памяти DLL (payload зашит в `.h` на этапе сборки).
4. **Агент** — обычный Java-код на JDK 8 внутри игры: модули, HUD, рендер.

## Модули агента

| Модуль | Клавиша | Что делает |
|---|---|---|
| **ESP** | `G` | 2D-боксы игроков: проекция мир→экран через захваченную камеру игры + свой view-матрикс, псевдо-клип за плоскостью камеры |
| **AutoSprint** | `X` | Честный спринт через модовую систему клавиш (KeyBinding.pressed) |
| **FullBright** | `J` | Через гамму мода (Settings → FloatNode), значение 10.0 |

HUD: кастомные MSDF-шрифты (атласы зашиты в DLL), watermark, ArrayList в
стиле vanquish, SDF-шейдеры (GL20, рендер поверх `GuiIngame` подменой инстанса).

## Структура

```
jni/
  dll/            — нативная часть (C++): loader-цепочка, DR0+VEH, DefineClass
    build_dll.bat — СБОРКА ОДНОЙ КОМАНДОЙ (pack_agent.py → cl.exe → DLL)
  agent/src/      — Java-агент (JDK 8, без лямбд — invokedynamic запрещён протектором)
    client/RustClient.java      — entry, главный цикл 1мс
    modules/impl/               — Esp, AutoSprint, FullBright
    utils/render/               — MSDF-шрифты, SDF-шейдеры, HUD, ESP-проекция
    utils/etc/GameContext.java  — reflection-доступ к игровым объектам
  build/dll/      — собранная jni_rva_check.dll (регенерируется)
assets/           — расшифрованные шейдеры/шрифты игры
dump/             — распакованный дамп классов игры (для анализа)
tools/            — python-тулкит: CP-сканер, javap-хелпер, pack_agent, калибровка RVA
  pack_agent.py   — компиляция агента + protected-кодирование + генерация payload-хедера
  class_report/   — классификация 9977 классов дампа
  jv.py           — javap-верификация любого класса дампа одной командой
```

## Сборка

```bat
cd jni\dll
build_dll.bat
```

Требуется: Visual Studio (VC x64), JDK 8 (javac), Python 3.
На выходе — `jni\build\dll\jni_rva_check.dll` (шрифты и классы агента уже внутри).

## Инструменты анализа

- `tools/scan_all.py` — полный CP-скан дампа (9977 классов) → `scan_db.json`
- `tools/jv.py` — javap-дизасм любого класса дампа с резолвом имени
- `tools/disk_name_map.json` — карта «файл дампа → настоящее имя класса»
- `tools/calibrate.py` — методика обновления RVA после апдейта игры

## Заметки по реверсу

Полная карта классов, грабли протектора и технические детали — в `tools/class_report/report.md`.
Ключевое: иерархия игрока, XOR-кодирование координат, таблица JNI-слотов,
механика рендера форка (lwjglx-шимп, MSDF, SDF через `gl_FragCoord`).
