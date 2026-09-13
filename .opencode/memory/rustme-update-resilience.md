---
name: rustme-update-resilience
description: Анализ 09-08 — что сломается после апдейта игры (4 слоя), что
  автоматизировано (RVA-калибровка готова, find_validator.py НЕТ), план
  semantic-матчера имён; отпечатки классов снять ДО апдейта
metadata:
  node_type: memory
  type: project
  originSessionId: sess_61cf4d35-4ce6-4edb-a89e-3ec326d69db5
---

Полный анализ «что поменяется после апдейта игры» (2026-09-08, юзер спросил про AOB/скрипт автопоиска; КОД НЕ МЕНЯТЬ — отчёт в чат, ZNANIA не трогали; ждать «делай»). Продолжение дискуссии 09-05 из [[rustme-jni-offset-recovery]] (там нативная методика: calibrate.py/find_live3.py, emission-выравнивание слотов).

**4 слоя поломки:**
1. **4 RVA offsets.h** — ломаются при пересборке jvm.dll; методика calibrate.py/find_live3.py ГОТОВА (калибруется на старом дампе как ground truth). Слепые AOB для jvm.dll не вариант (emission сдвигается, линкер расставляет адреса заново) — рабочая комбинация «уникальная константа + структурный контекст».
2. **Карта слотов env-таблицы** — после апдейта верифицировать только ~14 функций, реально нужных dllmain (FindClass, GetObjectClass, NewStringUTF, GetStringUTFChars, GetJavaVM, ExceptionOccurred/Clear, Push/PopLocalFrame, GetMethodID, GetStaticMethodID, CallObjectMethodA@195, CallBooleanMethodA@200, CallStaticObjectMethodA@167, CallStaticVoidMethodA, DefineClass@69), не все 109. Killer-слоты сдвигаются вместе с картой — слепые сканы смертельны и на новом билде. Из-за лимита «1 инжект на запуск» верифицировать ПАЧКОЙ безопасных кандидатов за инжект (не слот-за-запуск).
3. **RVA валидатора 0x1C2D0 в dllmain.cpp** — ЕДИНСТВЕННОЕ место БЕЗ автопоиска. План find_validator.py: парсер protected-формата искать по 3 уникальным u16-константам major 40039/28200/59236 → валидатор = call парсера сразу после XOR-дешифровки блоба; самопроверка скрипта — обязан выдать 0x1C2D0 на старом дампе.
4. **Карта обфусцированных имён в агенте** — перемешается при пересборке. НЕ меняются: named-классы (ru.rustme.*, ru.meproject.*, 715 шт — FullBright/AutoSprint уже держатся на них: getGamma/FloatNode/getKeybindingSettings), семантические CP-строки («UPDATE_LATENCY», key.categories.*, GLSL, MpServer, generic.movementSpeed), vanilla-константы байткода (0.16277136F, 0.85, 0.3, 0.017453292), ваниль-математика 1.12.2.

**Точки зашивки имён по файлам (ремап при апдейте):** dllmain.cpp — пробный класс loader-цепочки `rustme.liIlIliIiI` (кандидат-замена: named `ru.rustme.settings.Settings`, загружен всегда даже в меню → заодно уберёт CNFE-окно); GameContext.java — синглтон IllilIIiIl, поле подмены HUD gs.liliiiIl, chain спринта gs.IiIIllil; Esp.java — все геттеры поз/prev/AABB/yaw/pitch/eye-height, статик-матрицы lliIilliiI, камера IlIIliiIiI, world.playerEntities=IlIiiiiil, GameProfile IlIiIiiilI; Watermark.java — пинг-цепочка iliiililiI→iiIilIliiI→illIillliI; CheatHud/CheatIngame — наследование liIIliliiI + iliIiiIliI(F)V, FontRenderer lilililiiI, ScaledResolution liIIiIliiI + 3 геттера; tools/pack_agent.py — пересобрать game_cp.jar из нового дампа + disk_name_map.json заново (коллизии (N)).

**План semantic-матчера имён (автопоиск слоя 4):** база есть — scan_all.py собрал поля/методы/строки/константы всех 9977 классов в scan_db.json. Отпечаток по убыванию надёжности: (1) семантические CP-строки, (2) числовые константы + нормализованные опкоды (CP-индексы→категории), (3) структурные счётчики (Entity root: 94 поля/9 пар double с единым XOR-декодером через AtomicLong; камера ESP: 3 FloatBuffer16+IntBuffer4+Vec3, заполняются из world-прохода; AABB: 6 double, ctor 3×Math.min+3×Math.max по осям), (4) граф наследования, (5) размеры. **ГЛАВНОЕ ДЕЙСТВИЕ ДО АПДЕЙТА: снять semantic-карту «смысловое имя → отпечаток» (~50-80 используемых классов) с ТЕКУЩЕГО билда — после апдейта матчить будет не с чем.** Открытый вопрос: детерминирован ли обфускатор между билдами — проверить сравнением MD5-мэппингов 19600 vs 26116 (старые дампы D:\1proekts\rustme\).

**Сценарии/цена:** А — обновился только jar (jvm.dll цел): с матчером 1-2 часа (ремап + game_cp.jar + пересборка DLL), без — вечер ручного реверса по javap. Б — + jvm.dll: плюс калибровка RVA (готово, минуты), слоты (1-2 инжекта проб), валидатор (со скриптом минуты, без него — ручной реверс парсера). В — переход на новый клиент (rockstar 1.21): заново ваниль-слой и GL, lwjglx-грабли уходят сами (нативный LWJGL3), DefineClass-путь переносим при том же протекторе.

**Гатчи апдейта:** game_cp.jar из нового дампа (иначе агент компилится против несуществующих имён); ScaledResolution-геттеры мапить ТОЛЬКО по дизасму, не по порядку полей (ловушка стреляла); проверка отсутствия java/lang/invoke в .class после любого ремапа (правило без лямбд/::); нативный хук-путь не предлагать. Связано: [[rustme-jni-offset-recovery]], [[rustme-night-map]], [[rustme-esp]].
