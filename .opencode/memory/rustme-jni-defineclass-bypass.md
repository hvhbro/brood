---
name: rustme-jni-defineclass-bypass
description: DefineClass взломан (DR0+VEH bypass); Java-агент в игре — AutoSprint
  (toggle X) И HUD (ArrayList) РАБОТАЮТ: подмена GuiIngame (gs.liliiiIl) на
  CheatIngame extends liIIliliiI, override iliIiiIliI(F), шрифт через
  наследуемый llliiiIliI(), drawRect = СТАТИК lIilIliliI(IIIII)V; шина событий
  мода мертва; ЛЮБАЯ рантайм-генерация классов (Proxy/лямбды) запрещена
  протектором; структура expensive-стиль (client/events/modules/utils);
  v31 = RenderUtil на lwjglx-шимпе (матрицы/квады/градиенты напрямую, без
  reflection) — ждёт инжекта
metadata:
  node_type: memory
  type: project
  originSessionId: sess_88cf13da-bee6-4c0a-b169-78788a0320ad
---

Сессия 2026-09-05 (проект rustme, JNI-инъекция): прорыв — произвольный Java-класс
инъектируется в JVM игры через native DefineClass.

**Рабочая цепочка** (dllmain.cpp, 269 строк, чисто):
1. Перебор потоков → лоадер с проверкой `loadClass("rustme.liIlIliIiI")`
   (первый ctxCL в мире — лоадер лаунчера, НЕ видит rustme.*!)
2. DR0-breakpoint на валидаторе protected-классов (jvm.dll RVA 0x1C2D0) + VEH →
   заглушка `mov al,1; ret`. Память jvm.dll не изменяется (VirtualProtect на
   .text заблокирован протектором — gle=87, NtProtectVirtualMemory хукнут).
3. DefineClass(env, NULL, игровой loader, protected-блоб) → install() → Java-поток.

**Ключевые факты:**
- Слот 69 = настоящий jni_DefineClass, не вырезан; ночные killers — мусорные аргументы.
- Парсер классов (RVA 0x223000-0x231600) принимает только protected-формат игры
  (XOR KEY[i%15], CAFEBABE @256); валидатор заголовка 0x1C2D0 привязан к телу
  (RSA-подпись?), но обходится DR0 — подпись не нужна.
- Энкодер: tools/encode_protected.py (round-trip 100/100).
- GetStaticMethodID работает на классах, определённых из native (блокировка 135
  распространяется только на классы из jar).
- Структура игры: mc = GameSettings.IllilIIiIl().<instanceof-поле>; player =
  mc.world(IlIiiiiil).playerEntities → instanceof liIililiiI; скорость для travel =
  поле liiliIliI:F (не dataWatcher!); гамма в форке мертва (FullBright невозможен).

**Почему:** [[rustme-jni-dll]] ночная сессия 2026-09-04 считала DefineClass убитым —
опровергнуто: killers были от мусорных аргументов.

**How to apply:** Для любой фичи: писать Java-класс, компилить JDK8, encode_protected.py,
вставлять в agent_bytes_enc.h, сборка build_dll.bat. Эффект требует реверса точки записи.
Полные детали — ZNANIA.md раздел 10.

**Финал сессии 2026-09-05 (на паузе, «пока ничего не делай»):**
- ZNANIA.md обновлён: добавлен раздел 10 (10.1–10.7: цепочка, protected-формат,
  что не работает, структура игры, тесты эффекта, состояние кода); раздел 8
  переписан под новое состояние; «10.8 Следующие шаги» удалены по требованию юзера.
- dllmain.cpp переписан начисто (269 строк): attach → лоадер с валидацией
  loadClass → DR0+VEH → DefineClass → install(). Все пробы/эксперименты удалены.
- Текущий агент в agent_bytes_enc.h = TitleTest (заголовок окна не меняется —
  GLFW-контекст привязан к главному потоку; сам класс define+install+run — ОК).
- **noslow v14 собран, но НЕ тестирован в мире.** Открытые вопросы:
  (1) race — мод может перезаписывать liiliIliI:F после тика агента →
  агрессивный цикл клампа без sleep; (2) sneak-флаг: iiiilIlIII()Z может не
  соответствовать реальному шифту (у input-холдера liIIIiIIiI свои 5 boolean —
  нужен diag-дамп всех 5 + cur); (3) attr=0.0 — инстанс атрибута выбирается
  неверно (брать static lIliiliIiI по имени lllilIiII, не первым попавшимся).
  Диагностика v13.2: cur=0.1 покой / 0.13 движение; baseSnapshot-механика работает.

**Автоматизация сборки агента — ВНЕДРЕНА И ПРОТЕСТИРОВАНА (2026-09-05, юзер одобрил: «делай аккуратно, ничего не ломай»; ранее был вопрос «просто вопрос пока ничего не делай»):**
- **Новая структура:** `jni/agent/src/noslow/NoSlowAgent.java` (Java-исходники агента — папка со всеми классами, любое число пакетов; перенос из tools/); `tools/pack_agent.py` — новый скрипт-конвейер; `jni/dll/src/agent_payload.h` — ГЕНЕРИРУЕТСЯ (не редактировать; старый agent_bytes_enc.h удалён).
- **pack_agent.py:** (1) javac JDK8 (`-encoding UTF-8 -source 8 -target 8`; javac ищется: --javac → env JDK8_JAVAC → дефолт Eclipse Adoptium) компилирует ВСЕ .java рекурсивно из jni/agent/src; (2) каждый .class кодируется encode_protected + ОБЯЗАТЕЛЬНЫЙ round-trip decode(encode(x))==x (падение = сборка падает при смене формата апдейтом); (3) внутреннее имя класса парсится из байткода (this_class, двухпроходный CP-парс — первый this по смещению до CP давал интерфейс Runnable); (4) генерит agent_payload.h: `struct AgentClassBlob {name, data, size}` таблица `g_agentClasses[]` (включая внутренние $1.class), `g_agentClassCount`, `g_agentEntryName` (default noslow/NoSlowAgent, --entry).
- **build_dll.bat:** шаг 1 `python %ROOT%..\..\tools\pack_agent.py --src "%ROOT%..\agent\src" --out "%ROOT%src"` (tools на 2 уровня выше jni/dll!), шаг 2 cl.exe как раньше. Один запуск = Java+кодирование+DLL.
- **dllmain.cpp:** инклуд agent_payload.h; DR0-armed ОДИН раз на весь пакет → DefineClass(env, null, loader, blob.data, blob.size) циклом по g_agentClasses[] (внутренние $1.class подхватываются) → entry-класс по `_stricmp(name, g_agentEntryName)`, fallback = первый определённый → снять DR0 → install().
- Грабли: python-heredoc портит `\\` в java-строках (путь через File.separator); this_class парсить после CP (доступ p+2 после access — легко съехать на интерфейс).
- Порядок DefineClass неважен (ленивая резолюция, <clinit> не выполняется до использования); имя передавать NULL; javac строго 8 (major 53+ → «Corrupted classfile»).

**Why:** [[rustme-jni-dll]] ночная сессия 2026-09-04 считала DefineClass убитым —
опровергнуто: killers были от мусорных аргументов.

**2026-09-05 вечер, noslow ОКОНЧАТЕЛЬНО архивирован, ТЕКУЩИЙ АГЕНТ = AutoSprint (собран, ждёт инжекта):**
- Юзер просил глубокий разбор noslow с подсказкой Lightning-Client-master (JNI-чит jvm-paper): CStrafeModule = strafe перезаписью motionX/Z по yaw — неприменим; CIntelligentMappings (FindClass ванильных имён) под протектором не работает. Снова закрыл noslow («видимо это трудно»).
- **NoSlow в бэкапе: `jni/agent/backup_noslow/NoSlowAgent.java`** (вся наработка: двойной кламп, diag, снапшот). Удалено `jni/agent/src/noslow/`. Папка jni/agent почищена (дубликаты NoSlowAgent.java, .idea/, build/; остался единственный jni/agent/src/).
- pack_agent корректно отчитался «no .java sources» — DLL в build/ пока содержит старый noslow-агент до пересборки.
- **Предложены варианты нового теста:** AutoSprint (выбран юзером), KeyBinding-модификация, TitleTest возврат (нужен HWND). Детали noslow-архива — [[rustme-jni-offset-recovery]] вечер-16/17 и финальный реверс.

**Why:** [[rustme-jni-dll]] ночная сессия 2026-09-04 считала DefineClass убитым —
опровергнуто: killers были от мусорных аргументов.

**How to apply:** Для любой фичи: править/добавлять Java-классы в `jni/agent/src/` →
запустить `jni/dll/build_dll.bat` (сам компилирует javac JDK8, кодирует в
protected-формат с round-trip-проверкой, собирает DLL). Эффект требует реверса
точки записи. Полные детали — ZNANIA.md раздел 10.

**2026-09-05, AutoSprint РЕЗУЛЬТАТ + КОРЕНЬ ВСЕХ ПРОВАЛОВ (TIMING):**
- AutoSprint у пользователя (в мире, W зажат): `sprint forced, flag=true` каждый тик — **но FOV/скорость не меняются**. Запись в поле работает (чтение после записи = true), эффект не виден.
- **iiiiIiIIiI(80) — ложный след:** `extends rustme/liiliiIIiI` — ДРУГОЕ дерево классов, НЕ управляет нашим игроком (наш: liIililiiI→iiIililiiI→IIiIIiIIiI→liIlIliIiI→IIlIIliIiI). Только liIlIliIiI(10) и iiiiIiIIiI(80) объявляют lIllilllII(Z) независимо.
- **КОРЕНЬ ВСЕХ 3 ПРОВАЛОВ (noslow/FullBright/sprint) — TIMING:** поллинг-поток пишет МЕЖДУ игровыми тиками; игра в начале КАЖДОГО тика перезаписывает поля (sprint=false из ванильной логики, gamma не читается рендером, liiliIliI=attr.getValue()), ПОТОМ читает для travel/FOV/движения. Наше значение никогда не доходит до game logic. Это НЕ гипотеза — следует из diag-данных (flag=true после записи + нет эффекта) и полного реверса.
- Полные детали анализа — ZNANIA.md раздел 10 (10.1–10.7).
- **Предложенные решения:** (А) motionY=1.0 тест — докажет/опровергнет запись в живой объект (motion не перезаписывается в начале тика, а потребляется физикой); (Б) реверс event bus мода (Kotlin, PlayerSneakEvent) + регистрация слушателя через наш DefineClass — код выполняется ВНУТРИ тика; (В) DR0 exec на игровом потоке в нужной точке + VEH вызывает Java. Решение не принято — юзер остановил тесты после AutoSprint провала.

**Вопрос юзера «не отменяется ли encode_protected после патча валидатора?» —
ОТВЕТ: кодирование ОСТАЁТСЯ обязательным.** DR0 обходит только валидатор
заголовка (шаг 5); формат — отдельный барьер (шаги 1–4): XOR-дешифровка всего
блоба KEY[i%15] → пропуск 256 байт → CAFEBABE @256 → OPS/тег-ремап. Plain-класс
умирает на шаге 3/4 ещё до валидатора (pre-XOR не спасает — OPS-декод
применяется к структурным полям и без кодирования даёт мусорные версии/CP).
Также подтверждено: кодирование НЕ причина неработающего noslow (round-trip
точен, класс полностью загрузился, все reflection-резолвы сработали — проблема
была в точке компенсации). Ссылка на noslow-реверс: [[rustme-jni-offset-recovery]].

**2026-09-05 вечер, ВАРИАНТ А (JumpTest) ПРОЙДЕН — запись в живой объект ДОКАЗАНА:**
- Агент каждые 3с пишет `motionY=1.0` (поле `IiiIIiliI:D` в Entity root IIlIIliIiI(23); motionX=`iIIlIiliI`, motionZ=`IIiIIiliI` — имена из travel-дизаса getfield #181/#213/#223).
- Результат у пользователя (в мире): `before=0.0 -> after=1.0` каждый раз, **физика живая** — before-значения = гравитация (-0.106/-0.117/0.036), motionX/Z меняются от движения и коллизий; игрок подлетал и «телепортировался вбок» (сервер корректировал быстрые изменения).
- **КОРЕНЬ 3 ПРОВАЛОВ ПОДТВЕРЖДЁН ЭКСПЕРИМЕНТОМ:** мы пишем в ЖИВОЙ объект; единственная проблема — TIMING state-полей (sprint/speed/gamma перезаписываются игрой в начале тика ДО чтения; motion — потребляется физикой, поэтому тест сработал).
- Решение юзера: «удали тест, и давай сделаем event bus» — создать собственную архитектуру чита (как EventSystem в LightningClient).

**2026-09-05 вечер, НОВАЯ АРХИТЕКТУРА ЧИТА (9 классов, все pack OK, [build] OK):**
- `jni/agent/src/noslow/NoSlowAgent.java` — entry: install() → поток → `GameContext.init()` (до 60 попыток по 1с — меню) → модули → цикл 1мс: `GameContext.update()` → `EventBus.post(new TickEvent(inWorld))`.
- `noslow/util/Log.java` — synchronized PrintWriter (путь через File.separator).
- `noslow/util/GameContext.java` — init(): forName+loader-scan → gs (IllilIIiIl) → mc (instanceof-перебор нестатических полей GS) → world (IIIiiiiiI) → playerEntities (IlIiiiiil) → movementInput (wrapper.iiliiIiII) → sprint/motion/isSneaking; `update()`: world/player/movementInput/sneaking каждый тик. Все поля public, модули читают напрямую.
- `noslow/event/EventBus.java` — `subscribe(Class<T>, Listener<T>)` / `post(T)`; CopyOnWriteArrayList; события: TickEvent(inWorld), SneakStartEvent/SneakStopEvent(player); база Event с cancelled.
- `noslow/module/AutoSprint.java` — onTick: если !sneaking и |moveForward|>0.01 → setSprint(true) + sprintField=true; setEnabled(false) сбрасывает спринт.
- Гатчи сборки: EventBus — задвоенный import Log + потерянные java.util импорты; NoSlowAgent — второй `Class wrapper` в init() (первый объявлен в playerEntities-блоке — переиспользовать).
- **dllmain.cpp:** инклуд agent_payload.h (agent_bytes_enc.h удалён); DR0-armed ОДИН раз на весь пакет → DefineClass циклом по `g_agentClasses[]` (name=null) → entry-класс по `g_agentEntryName` (fallback первый определённый) → снять DR0 → install() на entry.
- **Реверс event bus МОДА (для истории, не пригодился):** база событий `lilliiiliI` (cancel IlliIilil + enum IIlliiiliI — 3 значения, фаза/тип, НЕ шина), 38+ классов-событий (PlayerSneakEvent = liIlliiliI: поля bus+player); подписка `iliIIlIlil(Class<T extends lilliiiliI>, Function1<T,Unit>)` найдена в iiIliiIliI(36) — UI-базовый класс (пер-экранная подписка, НЕ глобальная шина); глобальный диспетчер не найден → создан СВОЙ EventBus.

**Ожидание от инжекта AutoSprint (в мире, W зажат):** FOV расширяется + скорость выше = Java-архитектура полностью работает; в логе `[AutoSprint] sprint forced, flag=true`.

**2026-09-05 вечер, ПЕРВЫЙ ИНЖЕКТ НОВОЙ АРХИТЕКТУРЫ: конвейер работает, НО найден корневой баг → ИСПРАВЛЕН (v17, ждёт инжекта):**
- Инжект у пользователя: **10/10 классов define OK** (NoSlowAgent + 5 EventBus-классов + AutoSprint + GameContext + Log), entry jclass получен, `install() called` — конвейер DefineClass-пакета полностью жив.
- **НО init() упал: `[Ctx][ERROR] init exception exc=java.lang.NoSuchMethodException: rustme.lIliIiiIiI.lIllilllII(boolean)`** → AutoSprint не тикал (sprint не форсился, FOV/скорость не менялись у пользователя при зажатом W).
- **КОРНЕВАЯ ПРИЧИНА (в GameContext.init()): `playerClass = mcC;` — в playerClass записали класс MINECRAFT (lIliIiiIiI), а не EntityPlayer (liIlIliIiI)!** Все player-методы резолвились от Minecraft: `setSprint = mcC.getMethod("lIllilllII", Boolean.TYPE)` → NoSuchMethod (lIllilllII реально объявлен в liIlIliIiI(10) — EntityPlayer). Тот же баг касался isSneaking/getSpeed и поиска sprint-поля IilIiIliI:Z (искалось от mcC).
- **Фикс v17 (собран, ЖДЁТ ИНЖЕКТА):** `playerClass = gameLoader.loadClass("rustme.liIlIliIiI")` после резолва лоадера; `setSprint/isSneaking/getSpeed` резолвятся от playerClass; поиск sprintField IilIiIliI:Z — подъём по иерархии от playerClass.
- Урок: **в obfuscated-форке классы легко перепутать** (lIliIiiIiI = Minecraft, liIlIliIiI = EntityPlayer — отличаются на 1 символ!): при NoSuchMethod первым делом проверять, от какого класса резолвишь метод. Также init() падал, но цикл ретраев печатал «context initialized» после попыток — лог агента надо читать вместе с ошибками.
- Возможное следствие для истории: ранние провалы (sprint/noslow в старых агентах) могли частично быть из-за того же перепутанного класса в других местах кода — при возврате к noslow перепроверить резолвы.

**Ожидание от инжекта v17:** `[Ctx] init OK` БЕЗ NoSuchMethod → `[AutoSprint] sprint forced, flag=true` → при зажатом W FOV расширяется + скорость растёт. Если снова не сработает при зелёном init — вернуться к TIMING-анализу (event bus мода: подписка iliIIlIlil(Class,Function1) в iiIliiIliI(36), но это пер-экранная подписка UI-класса; глобальный диспетчер мода не найден).

**2026-09-05 вечер, v17 РЕЗУЛЬТАТ: init зелёный, НО AutoSprint снова без эффекта → МЕХАНИКА ПОНЯТА ОКОНЧАТЕЛЬНО (motion vs state) → SpeedBoost (собран, ждёт инжекта):**
- v17 у пользователя (в мире): `[Ctx] init OK` (NoSuchMethod исчез — playerClass-фикс верен), `[AutoSprint] sprint forced, flag=true` каждый тик — **но FOV/скорость опять без изменений** (юзер держал W).
- **Окончательный вывод (два независимых эксперимента): motionY (физика) работает, sprint (state-флаг) — нет. Разница:**
  - **motionY/position = ФИЗИЧЕСКИЕ величины**: игра их НЕ перезаписывает в начале тика, а ПОТРЕБЛЯЕТ для расчёта движения → наша запись живёт и применяется (JumpTest).
  - **sprint/speed/gamma = STATE-ПОЛЯ**: игра в начале КАЖДОГО тика перезаписывает их своей логикой (setSprint(forward>0 && food>6 && !sneaking)), потом читает. Наша запись между тиками умирает до чтения.
- **Решение — писать в ПАРАМЕТР, а не в STATE: атрибут movementSpeed (IAttributeInstance.setBaseValue).** Атрибут читается игрой каждый тик для расчёта liiliIliI:F, но перезаписывается ТОЛЬКО сервером (EntityProperties, редко). SpeedBoost: при первом тике сохранить originalBase=attr.getValue(), каждый тик `attrSetBase.invoke(attrInstance, originalBase*1.6)` → скорость ×1.6, FOV расширяется (velocity-based).
- **GameContext расширен:** movementSpeedAttr (SharedMonsterAttributes static lIliiliIiI, первый найденный), attrGetValue (`illiiliIiI.iIIiIlIIII()D`), attrSetBase (`iIliIlIIII(D)V`), getAttrInstanceMethod (`playerClass.IiIlIiilII(lIliiliIiI)` — ВАЖНО от playerClass=EntityPlayer, не от Minecraft!). AutoSprint.java переписан в SpeedBoost: буст ×1.6 с сохранением originalBase.
- Нослоу для мультиплеера по той же схеме: сервер шлёт slowdown-атрибут при шифте → кламп через `attrSetBase(base)` каждый тик (сервер перезапишет — мы вернём за ≤1мс; travel прочитает базу в большинстве тиков).
- **Ожидание от инжекта SpeedBoost:** `[SpeedBoost] activated, original base=0.1` → скорость ×1.6 (как speed potion) → доказательство контроля движения через атрибуты → затем noslow той же техникой.

**2026-09-05 вечер, v17 РЕЗУЛЬТАТ + МОТИОН-ТЕСТ ДОКАЗАЛ ЖИВОЙ ОБЪЕКТ + МОТИОН vs STATE РАЗЛИЧИЕ:**
- v17 у пользователя (в мире): `[Ctx] init OK` (NoSuchMethod исчез — playerClass-фикс верен), `[AutoSprint] sprint forced, flag=true` при зажатом W — **но FOV/скорость опять без изменений**.
- **Motion-тест (вариант А) ПРОЙДЕН:** агент каждые 3с пишет `motionY=1.0` (поле `IiiIIiliI:D` в Entity root IIlIIliIiI(23); motionX=`iIIlIiliI`, motionZ=`IIiIIiliI` — имена из travel-дизаса getfield #181/#213/#223). Результат: `before=0.0 -> after=1.0`, физика живая — before-значения = гравитация (-0.106/-0.117/0.036), motionX/Z меняются от движения/коллизий; игрок подлетал и «телепортировался вбок» (сервер корректировал быстрые изменения).
- **КОРЕНЬ ВСЕХ 3 ПРОВАЛОВ ПОДТВЕРЖДЁН ЭКСПЕРИМЕНТОМ — МОТИОН vs STATE РАЗЛИЧИЕ:** мы пишем в ЖИВОЙ объект; но motionY/position = ФИЗИЧЕСКИЕ величины (игра НЕ перезаписывает их в начале тика, а ПОТРЕБЛЯЕТ для расчёта движения → наша запись живёт и применяется). sprint/speed/gamma = STATE-ПОЛЯ: игра в начале КАЖДОГО тика перезаписывает их своей логикой (setSprint(forward>0 && food>6 && !sneaking)), потом читает. Наша запись между тиками умирает до чтения.
- Диагностика v15/v16 дополнительно показала: input-значения `fwd=0.05, str=0.1` — НЕ клавиатурные (клавиатура = ±1.0); при шифте fwd не менялся — игра перезаписывает input раньше агента.
- Решение юзера после провала v16: «давай сделаем что то другое не NoSlow видимо это трудно» → noslow в бэкап `jni/agent/backup_noslow/NoSlowAgent.java`, выбран AutoSprint.

**2026-09-05 вечер, НОВАЯ АРХИТЕКТУРА ЧИТА (как EventSystem в LightningClient, по просьбе юзера «мы могли вызывать ивенты из отдельного класса»):**
- 9 классов, все pack OK: `noslow/NoSlowAgent.java` (entry: install() → поток → GameContext.init() до 60 попыток по 1с → модули → цикл 1мс: GameContext.update() → EventBus.post(new TickEvent(inWorld))); `noslow/util/Log.java` (synchronized PrintWriter, путь через File.separator); `noslow/util/GameContext.java` (кэш: forName+loader-scan → gs IllilIIiIl → mc instanceof-перебор полей GS → world IIIiiiiiI → playerEntities IlIiiiiil → movementInput wrapper.iiliiIiII → sprint/motion/isSneaking методы; update() каждый тик: world/player/movementInput/sneaking); `noslow/event/EventBus.java` (subscribe(Class<T>,Listener<T>) / post(T), CopyOnWriteArrayList; TickEvent(inWorld), SneakStart/Stop); `noslow/module/AutoSprint.java`.
- dllmain.cpp: инклуд agent_payload.h (agent_bytes_enc.h удалён), DR0-armed ОДИН раз на пакет, DefineClass циклом по g_agentClasses[] (name=null), entry по g_agentEntryName.
- Первый инжект: **10/10 классов define OK** — конвейер полностью жив.
- Гатчи сборки: EventBus задвоенный import Log; NoSlowAgent второй `Class wrapper` в init() (первый в playerEntities-блоке).

**2026-09-05 вечер, v17 баг (playerClass = mcC) НАЙДЕН И ИСПРАВЛЕН:**
- Инжект v16-архитектуры: init упал `NoSuchMethodException: rustme.lIliIiiIiI.lIllilllII(boolean)` → AutoSprint не тикал. **КОРНЕВАЯ ПРИЧИНА в GameContext.init(): `playerClass = mcC;` — записали класс MINECRAFT (lIliIiiIiI), а не EntityPlayer (liIlIliIiI)!** Все player-методы резолвились от Minecraft. Классы различаются на 1 символ — легко перепутать в obfuscated-форке; при NoSuchMethod первым делом проверять, от какого класса резолвишь метод.
- Фикс v17: `playerClass = gameLoader.loadClass("rustme.liIlIliIiI")`; setSprint/isSneaking/getSpeed от playerClass; sprintField поиск от playerClass. Результат: init зелёный, sprint forced flag=true — но эффект снова не виден (см. выше motion/state).

**2026-09-05 вечер, SpeedBoost (attr.setBaseValue) — путь для state-эффектов; v18 баг+фикс (собрана, ЖДЁТ ИНЖЕКТА):**
- Юзер спросил про альтернативы атрибуту скорости (считает костылём) — атрибут это ЕДИНСТВЕННЫЙ известный путь для state-эффектов без хука внутрь тика; альтернативы: event bus мода (правильная архитектура, но глобальный диспетчер не найден — подписка iliIIlIlil(Class,Function1) в iiIliiIliI(36) пер-экранная).
- Первый прогон SpeedBoost: **`NoSuchMethodException: rustme.lIliIiiIiI.IiIlIiilII(rustme.iiiIiliIiI)`** — баг: `attrKeyClass = smaSpeed.getClass()` возвращал `iiiIiliIiI` (конкретная реализация IAttribute), а `IiIlIiilII` объявлен принимающим `lIliiliIiI` (IAttribute ИНТЕРФЕЙС). getMethod ищет по точной сигнатуре — интерфейс ≠ конкретный класс.
- **Фикс v18 (собрана, 10 классов OK, ЖДЁТ ИНЖЕКТА):** `iAttrClass = gameLoader.loadClass("rustme.lIliiliIiI")` для getMethod-сигнатуры. SpeedBoost логика: первый тик `originalBase = attrGetValue(attrInstance)` (iIIiIlIIII()D), каждый тик `attrSetBase.invoke(attrInstance, originalBase*1.6)` (iIliIlIIII(D)V) → скорость ×1.6, FOV от velocity.
- Ожидание: `[SpeedBoost] activated, original base=0.1` → скорость ×1.6. Атрибут — ПАРАМЕТР (не state): читается игрой каждый тик, но перезаписывается только сервером (EntityProperties, редко) → наша запись живёт.
- Если SpeedBoost сработает → noslow той же техникой: при шифте клампить `attrSetBase(base)` каждый тик 1мс (сервер перезапишет — вернём за ≤1мс; travel прочитает базу в большинстве тиков).

**2026-09-05, AutoSprint v19 = НАСТОЯЩИЙ спринт через клавишу (собран, ждёт инжекта):**
- Юзер заметил: файлы дампа имеют суффиксы `liIlIliIiI(10)` — **расшифровка: (N) = артефакт распаковки на Windows** (case-insensitive FS: obfuscated имена iIiI/IiIi коллапсируют, 9625 из 9977 файлов получили «(1)»..«(311)»; в jar суффиксов НЕТ; снятие (N) даёт 1:1 биекцию с jar). Постоянный map «файл дампа → настоящее имя класса» построен по this_class constant pool: `tools/disk_name_map.json` (9977/9977 уникально). mini_dis.py/dump_members.py работают по stem-имени до «(».
- **Полностью отреверсен спринт-путь мода (вместо ванильного KeyBinding.keyBindSprint — его в форке нет):** патченный `liIililiiI.liIIIiiilI()` (onLivingUpdate) в тике читает `gs.IiIIllil` (Object, public) → checkcast `rustme.llIIiIiIiI` (мод-холдер) → поле `lliliiIiI` (public, ru.rustme.settings.Settings) → `Settings.getData()` → `MainSettingsCategory.getKeybindingSettings()` → `KeyBindingsCategory.getKeySprint()` → `KeyBindNode.isKeyDown()` → `OptionNode.getValue()` → KeyBinding `rustme.IilIiIiIiI` → `lIIIiIiIII()Z` = чтение поля **`iilIIiIiI:Z` (pressed)**; при true → `llllIiiilI(true)` (переопределённый setSprinting: super + serverSprintState `IlIliiil:I`). Второй вызов (791) — ветка стопа при отпускании/препятствиях.
- **КЛЮЧЕВОЕ: pressed — НЕ state-поле тика.** Писатели `iilIIiIiI:Z` = только сам KeyBinding (setKeyBindState `iIlIiIiIII(IZ)` из клавиатурных событий). Игра между событиями его НЕ перезаписывает → запись агента ЖИВЁТ до тика → TIMING-проблема 11.1 не применяется. Игра сама решает условия (еда>6, не sneaking, W зажата) — честный спринт, FOV нативный, сервер видит корректный sprint.
- GameContext: +resolveSprintKey() public (троттлинг 2с, ленивый вызов из тика; поля keyBindingsCategory/getSprintNode/nodeGetValue/keyBindingClass/kbPressedField), вызывается в конце init(). AutoSprint.java переписан: forceSprintKey(true/false) через reflection; setEnabled(false) отпускает клавишу.
- Сборка OK (10 классов).

**2026-09-05, v19 ИНЖЕКТ: init ЗЕЛЁНЫЙ, ЭФФЕКТА НЕТ (диагностика на паузе — юзер сказал «пока ничего не делай», только анализ):**
- Лог юзера: `[Agent] installed` → `[Ctx] movementSpeed attr set: true` → `[Ctx] sprintKey resolved: node=true, getValue=true` → `[Ctx] init OK` → тишина. Спринт не виден (как именно юзер проверял — уточнить: FOV или скорость).
- **Догадка юзера про ленивую загрузку классов / инжект в меню (по образцу других читов «запускать до запуска игры или в меню») ОПРОВЕРГНУТА:** init OK = инжект был в мире (GameContext.init находит playersField только по непустому playerEntities со wrapper'ами — в меню крутится retry); классы загружены; ранний инжект другим читам нужен для ранних хуков, наш DefineClass-путь от него не зависит (JumpTest доказал запись в живые объекты).
- **СЛЕПОЕ ПЯТНО: в новом AutoSprint нет пер-тиковых логов** — не различить: (а) тихий return по null node/keyBinding в forceSprintKey («resolved» в логе = резолв МЕТОДОВ; данные node/keyBinding берутся в тике молча), (б) поле пишется но перезаписывается игрой, (в) пишется и читается, но эффект слабый/невидимый. Лог пуст и без ошибок — под все три варианта подходит одинаково.
- Гипотезы по приоритету: (1) **pressed перезаписывается каждый тик (poll вместо событий)** — скан ВЫЗЫВАЮЩИХ setKeyBindState (`iIlIiIiIII(IZ)`) не докончен (фоновый таск убит); в ванилле событийно (GLFW-колбэки), но форк с lwjgl3ify мог перейти на опрос → тайминг-проблема 11.1 снова; (2) внешние условия ветки включения спринта в onLivingUpdate (блок 663–707: enum-константа, IIIliiil:I, движение) не разобраны до конца — игра видит isKeyDown=true, но setSprinting(true) не зовёт; (3) эффект может быть, но незаметен: спринт ×1.3 против привычного SpeedBoost ×1.6 + FOV-эффект может быть выключен в настройках мода (GraphicSettingsCategory). JMM/видимость boolean из другого потока — практически не причина (x86).
- План при разрешении юзера: (1) доскан вызовов setKeyBindState по jar (быстро закроет гипотезу №1 — главный развилка); (2) одноразовый diag-лог в forceSprintKey (identityHashCode node/keyBinding, pressed before/after + периодическая строка «still writing»); (3) полный дизасм блока 640–860 onLivingUpdate; (4) юзер-тест: виден ли ручной спринт по клавише из настроек мода (FOV/скорость).

**2026-09-05, РАЗВИЛИКА ЗАКРЫТА (скан jar + дизасм) → diag v20 собран, ЖДЁТ ИНЖЕКТА:**
- jar-энтри minecraft_FULL_DEOBF.jar = расшифрованные CAFEBABE (identical dump). **Скан jar: писатели pressed `iilIIiIiI:Z` ВСЕ внутри KeyBinding:** (1) `IliIiIiIII()V` = unpressKey (pressTime=0 + pressed=false), вызывают: статич. `IIiIiIiIII(llIIiIiIiI)` — mouse-handler unpress SecondaryAttack/PrimaryAttack/PickBlock; `lliIiIiIII()V` — unpressAll; (2) `iIlIiIiIII(IZ)` setKeyBindState — вызывают: GameSettings.lIIiiilliI/IIIIiilliI (save/load), `IiIIiIiIII()V` (перебор всех bindings; сначала `lllIiilliI.iiililIIIl` — вероятно время/таймер), UI-лямбда `lllIllIliI.lilIiiIIIl` (меню настроек). **ТИКОВОГО ПЕРЕЗАПИСАТЕЛЯ НЕТ — событийная модель подтверждена.** НО `IiIIiIiIII()V` может по таймеру снести pressed всем → следить за flips.
- ГАТЧ: в jar-скане (чистый struct-парсер) нельзя сваливать 4-байтовые теги в один тип — проверка e[0] in (9,10,11) молча даёт 0 ссылок.
- **Sprint-блок onLivingUpdate (640-845):** ВЕТКА1 (663-721): `IiIiiIilII(iiIiliIIiI.lIIIiIIII)` — enum-check (вероятно inWater/Blindness enum из iiIiliIIiI — MobEffects) → `getKeySprint().isKeyDown()` → `IIIliiil:I = pressed` (pf@713, кэш/таймер) → `llllIiiilI(Z)` @721. ВЕТКА2 (760-842): та же цепочка; НЕ pressed → `llllIiiilI(false)` @802; иначе поле `IliIIiliI:Z` (свое) → `llllIiiilI` @842. `llllIiiilI(Z)` = setSprinting. ВНЕШНИЕ условия ветки (enum, IliIIiliI) не расшифрованы — если diag покажет pressed=true/flips=0/sprintFlag=false, копать их.
- **Diag v20 (AutoSprint, 4248 байт, сборка OK):** первая запись: node/kb identityHashCode + kbClass/nodeClass + pressedBefore; каждые 5с: `pressedNow` (false при нашей true → перетирают), `flips/5s` (тысячи = перезапись каждый тик), `sprintFlag` (IilIiIliI:Z), sneaking, fwd/strafe (оба float: IiIiIIiII + iIIiIIiII — какой forward не уточнено, логируются оба). MovementInput liIIIiIIiI поля: 4 bool (lliiIIiII, iiIiIIiII, liIiIIiII, IIIiIIiII, lIIiIIiII) + 2 float.
- **Интерпретация v20:** pressedNow=true+flips=0+sprintFlag=false → блокируют УСЛОВИЯ ветки (копать enum-check); pressedNow=false+flips большой → кто-то перетирает (найти период); pressedNow=true+sprintFlag=true → спринт работает, вопрос видимости (×1.3 vs ×1.6, FOV-настройка).

**2026-09-05, v20 инжект: НАЙДЕНА ПРИЧИНА ТИШИНЫ — поток агента умирал до создания модуля (v21 собран, ждёт инжекта):**
- diag-строк v20 в логе НЕТ ВООБЩЕ (agent log 251 байт, кончается на "context initialized"; строка `[AutoSprint] subscribed` из конструктора модуля отсутствует). Вывод: `new AutoSprint()` кидал исключение → поток умирал молча (вокруг создания модуля не было try/catch, исключение уходило в невидимый stderr).
- **Главный подозреваемый — invokedynamic (LambdaMetafactory) в защищённой JVM:** `this::onTick` в AutoSprint, лямбда `k -> new CopyOnWriteArrayList<>()` в EventBus.computeIfAbsent, setUncaughtExceptionHandler-лямбда. **Фикс v21: ВСЕ лямбды/method reference заменены на анонимные классы** (AutoSprint$1 и т.п. — pack_agent подхватывает автоматически, стало 12 классов); EventBus.subscribe без computeIfAbsent; вокруг `new AutoSprint()` try/catch + лог; добавлен `[Agent] modules created` и heartbeat каждые 10с (`[Agent] heartbeat, inWorld=...`); uncaughtExceptionHandler логирует смерть потока. Проверка: в скомпилированных классах 0 ссылок на java/lang/invoke.
- **ПРАВИЛО для будущих агентных классов: НЕ использовать лямбды/method reference/streams в коде агента — только анонимные классы** (javac 8 без invokedynamic-конкатенации строк, это ок).
- Ожидание от инжекта v21: `[AutoSprint] subscribed` + `modules created` + heartbeat; затем diag-строки каждые 5с → интерпретация из предыдущего пункта.

**2026-09-05, v21: АВТОСПРИНТ РАБОТАЕТ (юзер: «У НАС ВСЕ ПОЛУЧИЛОСЬ НАКОНЕЦ ТО РЕАЛЬНО РАБОТАЕТ») — ЗАКРЫТО:**
- Инжект 12/12 классов define OK (включая AutoSprint$1, NoSlowAgent$1 — анонимные классы). Полная цепочка зелёная: subscribed → modules created → writing pressed=true → diag каждые 5с.
- Лог подтвердил: `pressedNow=true` всегда (запись живёт, тикового перезаписателя нет), `flips/5s` обычно 0 (редко 1-2; всплески 251/146 в момент старта движения — нормальные игровые unpress-события, агент мгновенно возвращает true), `sprintFlag` периодически true (игра включает спринт по нашей виртуальной клавише, когда условия выполнены — юзер тогда не жмёт W: fwd=0.05, клавиатура = ±1.0).
- **ФИНАЛЬНЫЙ УРОК (критичный для всех будущих модулей): лямбды/method reference (invokedynamic→LambdaMetafactory) в агентных классах МОЛЧА УБИВАЮТ агентный поток в защищённой JVM** — исключение летело в невидимый stderr. Симптом: лог обрывается на последней строке до первого `new Module()`. Лечение: только анонимные классы + try/catch вокруг init модуля + uncaught handler + heartbeat. Проверка: в .class не должно быть ссылок на java/lang/invoke.
- diag-лог (каждые 5с + одноразовая запись) в AutoSprint ОСТАВЛЕН в коде — при желении можно убрать/уменьшить частоту.
- Рабочий паттерн модуля: TickEvent → держать input-поле (не state-поле!) → игра сама применяет в своём тике. Следующие кандидаты: noslow (та же идея? slowdown через input/атрибуты — закрыт ранее), autoclicker, antiAFK, chest-stealer и т.п.

**2026-09-05, КОНСУЛЬТАЦИЯ: рендеринг (меню/HUD) — следующий фронт (юзер спросил, «пока ничего не делай»):**
- GL-контекст привязан к главному потоку игры (TitleTest: glfwGetCurrentContext()=0 с агентного потока) — весь рендер ТОЛЬКО на потоке игры; нужна точка, где ИГРА вызывает НАС на своём потоке.
- МЕНЮ (просто): свой класс extends игровой GuiScreen (искать в дампе по drawScreen(IIF)V / initGui()V / mouseClicked(DDI)V), открыть через mc.displayGuiScreen (reflection) — игра сама зовёт drawScreen и маршрутизирует ввод на рендер-потоке; клики/клавиатура бесплатно; чисто клиентское, сервер не видит.
- HUD (сложнее): нужен хук в InGameGUI.renderGameOverlay-тракт. Мод рисует свой HUD (zoom/freelook/admin-menu) → точка есть: (а) найти рендер-событие/список слушателей мода (база событий lilliiiliI, 38+ классов-событий, у событий поле bus; глобальный диспетчер ранее не найден — теперь есть jar-сканер + disk_name_map) и reflection'ом дописать свой listener в List; (б) fallback: патченный InGameGUI мода → список HUD-элементов → добавить свой компонент с тем же интерфейсом.
- addScheduledTask НЕ для рисования (очередь разгребается в тик-фазе кадра ДО отрисовки мира — затрётся); полезен для тик-фазной логики (например, ставить sprint-флаг прямо внутри тика).
- ПОРТИРОВАНИЕ из ванильного 1.12.2 — ДА, ключевой трюк: **minecraft_FULL_DEOBF.jar = compile classpath для javac** (все классы под реальными obf-именами с правильными сигнатурами) → типизированный код `extends rustme.GuiScreen` без reflection; ванильные клиенты 1.12.2 копипастятся с заменой имён; Java 8 совпадает; lwjgl3ify = GLFW3 под LWJGL2-шимпом, GL11.* работают как в ваниле; нюанс: форк мод-патчен (EntityPlayerSP точно отличается) — сигнатуры сверять с дампом, не с памятью о ваниле. Наши классы по-прежнему идут через protected-кодирование + DefineClass, просто с типизированными ссылками в CP.
- План по команде юзера: (1) статический реверс UI-фреймворка мода (GuiScreen форка + точка HUD-рендера) по дампу без инжекта; (2) compile-classpath (дамп-jar + библиотеки) + пробное меню, открывающееся по клавише; (3) HUD через рендер-точку мода (первый watermark).

**2026-09-05, КОНСУЛЬТАЦИЯ-2: рендер без главного потока / SwapBuffers / библиотека («пока ничего не делай»):**
- **Рисовать В кадре игры без потока игры физически невозможно** — framebuffer собирает только поток с привязанным GL-контекстом; исключений нет ни в Java, ни в нативе.
- **SwapBuffers-хук скорее закрыт:** детур требует записи в память (VirtualProtect/NtProtectVirtualMemory заблокирован протектором, gle=87 на jvm.dll; на glfw3.dll/opengl32.dll — не проверено, гарантий нет). Теоретический вариант DR0+VEH на glfwSwapBuffers (VEH выполняется на том же потоке, контекст уже привязан) — технически возможен, НО отклонён как нативный хук-путь (правило юзера) + исключение на каждый кадр, хрупко.
- **Без главного потока работает только окно-оверлей ВНЕ кадра:** AWT/Swing окно прямо из агента (это обычный Java) — прозрачное, alwaysOnTop, без рамки, рисуется на своём EDT. Ноль хуков, готовый UI-фреймворк. Минусы: OBS/ShadowPlay в режиме game capture оверлей НЕ видят (захват экрана — видят); клики — переключать режим окна (перехват для кликабельного меню / WS_EX_TRANSPARENT-подобный пропуск); если игра в exclusive fullscreen — не видно, нужен borderless/windowed; рассинхрон FPS с игрой.
- **Решение — рендер-библиотека двухуровневая:** (1) in-game typed-обёртка над игровым рендером (drawRect/drawString/ScaledResolution поверх классов форка, компиляция против дамп-jar, вызывать ТОЛЬКО на главном потоке — из GuiScreen или рендер-события мода); (2) опционально Swing-оверлей для внешнего UI (сложные меню, настройки). Модули пишут против одного API. Старт с уровня 1 (статический реверс UI мода по дампу), Swing-оверлей в запасе — самый быстрый способ увидеть свой UI на экране без всякого реверса.

**2026-09-05, КОНСУЛЬТАЦИЯ-3: вход в главный поток + EventBus на главном потоке («пока ничего не делай», только ответы):**
- **Зайти в главный поток ЛЕГАЛЬНО можно: `Minecraft.addScheduledTask(Runnable)`** (штатный механизм ванили 1.12.2, хаки с приостановкой чужого потока — нет): агент с CheatMain дёргает через reflection → Runnable выполняется НА потоке игры (очередь разгребается каждый кадр; isCallingFromMinecraftThread = сравнение Thread.currentThread() с сохранённым — найти в дампе за минуту). **ОГРАНИЧЕНИЕ: очередь разгребается в ТИК-фазе кадра ДО отрисовки мира** → рисовать из scheduled task бесполезно (затрёт мир), а менять state-поля — ИДЕАЛЬНО: это тот самый «внутрь тика» из раздела 11.1, правки без гонок.
- **EventBus и потоки: слушатель выполняется на потоке того, кто вызвал post()**, не того, кто подписался. Сейчас post() дёргает CheatMain → все слушатели на нашем потоке. Решение — постить события С главного потока через два моста: (а) addScheduledTask-мост → `MainThreadTickEvent` (state-правки ВНУТРИ тика без гонок); (б) GuiScreen.drawScreen / HUD-точка мода → `RenderEvent` (рисование на рендер-фазе). EventBus остаётся той же шиной, модули просто подписываются — без хуков, только «приглашения» игры.
- **Fallback для HUD без реверса рендер-точки: ванильные тосты** (`GuiToast.add`) — свой IToast с бесконечной длительностью рендерится игрой каждый кадр в правом верхнем углу; грубое ограничение зоны, но ещё одно «приглашение» без хуков. Держать в запасе.
- План по команде юзера: (1) статика по дампу — метод scheduled-task в Minecraft (lIliIiiIiI; сигнатура: принимает Runnable + сравнение потока + вставка в Queue), GuiScreen-класс форка (по drawScreen(IIF)V), HUD-точка мода; (2) код: MainThreadBridge, CheatGuiScreen + открытие по хоткею, RenderEvent в EventBus; (3) сборка → инжект → тест.

**2026-09-05, РЕВЕРС UI/RENDER-ТОЧЕК ВЫПОЛНЕН (статика по дампу; код НЕ писан — юзер скажет когда):**
- **Scheduled-task (главный поток) — ПОЛНАЯ КАРТА:** `iilliIliiI` = НЕ чистый GameSettings, а СЛИТЫЙ класс GameSettings+Runnable+IThreadListener (`implements Runnable + rustme/llilIiIiI`-интерфейс). Его `run()V` = ГЛАВНЫЙ ЦИКЛ ИГРЫ: создаёт Minecraft-объект `lIliIiiIiI` через `IiiIiilliI()V`, крутит `IllliilliI()V` = runGameLoop (nanoTime, `ru/rustme/NativeAPI.call`, fps-limit из ScreenSettingsCategory.getFpsLimit, VSync, Thread.yield). IThreadListener = интерфейс `rustme/llilIilIiI`: `IllIIiiiil(Runnable)ListenableFuture` (addScheduledTask) + `iIIIIiiiil()Z` (isCallingFromMinecraftThread = Thread.currentThread==поле lIllllil). Реализация в iilliIliiI: addScheduledTask → Executors.callable → `lliIIilliI(Callable)` → ListenableFutureTask.create → очередь (поле `ilIIllil:Object`). Задачи исполняются ВНУТРИ runGameLoop на главном потоке. 4 класса с addScheduledTask = реализации IThreadListener (наш — iilliIliiI, у нас уже есть его instance как gs!).
- **Minecraft-класс `lIliIiiIiI` УРЕЗАН** (55 методов, НЕТ IThreadListener/runGameLoop — их перенесли в iilliIliiI). mc.iIliIlilI (final) = ссылка на iilliIliiI.
- **GuiScreen = `rustme/IlIlliliiI`** (abstract, super = `rustme/iIlililiiI` = Gui: drawRect `lllIIliliI(IIII)V`, drawTexturedModalRect-семейство (IIFFIIFF)V и т.п.). drawScreen = `IiIIIlIlil(IIF)V`; 42 класса-экрана наследуют. `IliIIlIlil(iilliIliiI,II)V` = init(mc, w, h) — принимает СЛИТЫЙ класс! Screen-события мода: `illliiiliI`/`IiIiIiiliI` (поле типа IlIlliliiI). «Открыт ли экран» в моде = `IlIlliliiI.lliIilIliI()Z`.
- **HUD/RENDER-ТОЧКА МОДА НАЙДЕНА:** GuiIngame = `rustme/liIIliliiI` (super Gui); renderGameOverlay = `iliIiiIliI(F)V` (1197 байт; хвост патчен модом — getKeyPlayerList). **Рендер-событие мода: `liIiIiiliI extends lilliiiliI`** (поля: ScaledResolution `iiIIIilil:liIIiIliiI`, partialTicks `illiIIlil:F`) СОЗДАЁТСЯ в renderGameOverlay @404-410 и диспатчится `lIliliiIiI.IlIIilliII(event)`. Диспетчер событий мода = класс `lIliliiIiI` (List `iIillIiiI`, Map `lIillIiiI`/`IlillIiiI`, post=`IlIIilliII`, subscribe-подобные `lliIilliII`/`iIIIilliII`). Подписавшись туда, наш код будет вызываться НА ГЛАВНОМ ПОТОКЕ в рендер-фазе — рисуем HUD без единого хука.
- ГАТЧ: `class_analyzer.utf8` возвращает '' на Class-константах — использовать свой utfx-резолвер. Новый инструмент `tools/disasm_range.py` (полный дизасм метода с диапазоном; мусорные CP-индексы обфускатора — cpget-защита).

**2026-09-05, HUD v22 СОБРАН (15 классов, ждёт инжекта): AutoSprint toggle по X + ArrayList HUD:**
- **Клавиши БЕЗ Keyboard/GLFW-классов:** мод имеет статический хелпер `rustme.lllIiilliI`: `iiililIIIl(I)Z` = glfwGetKey(свой статический window-handle в поле liIliIiiiI, key)==key. GameContext.isKeyDown(glfwKey). GLFW_KEY_X=88 → тап = toggle. ВАЖНО: чтобы юзер мог сам спринтить при выключенном модуле — писать pressed=false только ОДИН раз после выключения (lastWritten-флаг), при выходе из мира — отпускать.
- **Consumer-подписка на рендер: `rustme.lIllIilliI.iIlIIIlIIl(Class, java.util.function.Consumer)V`** — ГЛОБАЛЬНАЯ шина мода НАЙДЕНА окончательно (Kotlin Function1 + Consumer варианты; dispatch = статик ilIIIIlIIl(Object): HashMap lliiliIiiI → toList → invoke). Наш HUD = класс implements java.util.function.Consumer (JDK-интерфейс, компиляция без classpath!), подписан на liIiIiiliI (рендер-событие из renderGameOverlay @404). accept() вызывается игрой на главном потоке в правильной фазе кадра.
- **CheatHud рисование:** Gui.drawRect = `iIlililiiI.lllIIliliI(IIII)V` (x1,y1,x2,y2,color); FontRenderer = `rustme.lilililiiI`: drawString `lIlIIliliI(String,FFIZ)I`, getStringWidth `IIIIIliliI(String)I`, FONT_HEIGHT=9 (константа). FontRenderer instance: в поле gs (iilliIliiI) как Object-поле (рендер мода кастует gs-поле) → скан нестатических полей gs instanceof fontRendererClass. ScaledResolution из события (поле iiIIIilil, методы getScaledW/H = lIllIlIliI/IIllIlIliI).
- **Modules.java:** реестр функций (name+state) — HUD рисует включённые. Новые файлы: noslow/util/Modules.java, noslow/util/CheatHud.java. AutoSprint toggle-логика + Modules.set. NoSlowAgent: hud подписка после modules created (лог 'hud subscribed to render event' / 'FAILED').
- 15 классов pack OK, [build] OK, invokedynamic 0. Ожидание от инжекта: в мире справа-сверху чёрный бокс со строкой 'AutoSprint'; X тогглит (лог 'toggled by X -> ON/OFF'), список анимированно исчезает/появляется; 'hud subscribed to render event' в логе.

**2026-09-05, v23: NPE подписки найден и исправлен (16 классов, ждёт инжекта):**
- v22 лог: `subscribe failed NPE: Cannot invoke Object.getClass() because obj is null` — мод-диспетчер `ilIIIIlIIl(Object)` делает `obj.getClass()`, а модовый Consumer-метод `iIlIIIlIIl(Class,Consumer)` — ЭТО **unsubscribe** (HashMap.remove + «Listener is not registered» throw)! Регистрация ТОЛЬКО через `IIlIIIlIIl(Class, Function1)Function1`: `lliiliIiiI.computeIfAbsent(eventType, лямбда->new ArrayList)` → `List.add(fn)`. Пост: `ilIIIIlIIl(event)`: `map.get(event.getClass())` → toList → для каждого `Function1.invoke(event)` с try/catch(printStackTrace).
- **Фикс v23:** наш Consumer оборачивается в `kotlin.jvm.functions.Function1` через `java.lang.reflect.Proxy.newProxyInstance` (invoke → accept(event), return null); регистрация `IIlIIIlIIl(renderEventClass, proxy)`. Proxy-класс создаётся в рантайме (не DefineClass) — должен жить. 16 классов, build OK, invokedynamic 0.
- Toggle X в v22 работал у юзера (ON/OFF в логе), HUD не рисовался только из-за подписки. Ожидание v23: `[HUD] subscribed via Function1 bridge` → в мире справа сверху бокс с AutoSprint, X тогглит.
- УРОК: у Kotlin-шин Consumer-методы могут быть unsubscribe; для регистрации искать computeIfAbsent/compute на Map-поле шины. Сылка: шина мода = lIllIilliI (статик HashMap lliiliIiiI: Class→List<Function1>).

**2026-09-05, v24: НАСТОЯЩАЯ архитектура шины мода разобрана (v23 NPE был не там):**
- v23 упал с тем же NPE: метод `iIlIIIlIIl(Class,Consumer)` в lIllIilliI — unsubscribe; Function1-карта регистрация OK, НО NPE шёл из другого места — **IlIIilliII(event) в lIliliiIiI — ЭТО настоящий хаб-диспетчер событий мода**: `lIillIiiI:Map<Class,listener>` → `map.get(eventType)` → если null: `new IlIiliiIiI(eventType)`-обёртка → `lliIilliII(eventType, listener)Z` регистрирует; далее `listener.iliiIlliil()I` (priority?) + сохранение в поля liillIiiI/ilillIiiI. NPE v23: `IIilliIiil()Z` (статик-флаг IlllillIiI) → ветка `IIlIliIiil(listener, map)` — проверка instanceof IlIiliiIiI: если наш Proxy не instanceof — там `listener.iIiilIiiI` (поле обёртки) → NPE на null-обёртке!
- **Слушатели хаба = интерфейс `rustme.iiIiliiIiI`** (4 метода: lIiiIlliil()V register-колбэк, iliiIlliil()I priority, IliiIlliil(ZZ)V, iIlIllliil(settings)V), а НЕ Function1. Function1-карта (lIllIilliI) — отдельный статический механизм поста: renderGameOverlay @413 → iIlliiiliI.ilIillliil(event) → lIllIilliI.ilIIIIlIIl(event) → HashMap.get(event.getClass()) → Function1.invoke(event).
- **Фикс v24 (17 классов, build OK, 0 indy): subscribeRender = (1) resolveFontRendererInstance() из gs-полей; (2) hub-инстанс: static-поле нет → FontRenderer.IIlliIiiI (hub живёт в поле FontRenderer!) → instanceof-поиск по gs; (3) Proxy слушателя iiIiliiIiI (no-op); (4) hub.llIiiIlliII... = lliIilliII(eventType, listener)Z регистрация; (5) Function1-прокси в статич. карту IIlIIIlIIl(renderEventClass, fn) — данные пойдут в accept().
- Ожидание v24: '[HUD] hub instance found' + 'registered listener, ok=...' + 'hub wiring done' → HUD в мире.

**2026-09-05, v24 инжект: КОРЕНЬ НАЙДЕН ОКОНЧАТЕЛЬНО — «Corrupted classfile» на Proxy (план v25 согласован, юзер ещё не сказал «делай»):**
- Лог v24: `[HUD] hub instance found: rustme.lIliliiIiI` (хаб-резолв сработал!), затем `[HUD][ERROR] subscribe failed exc=java.lang.RuntimeException: Corrupted classfile` — упал первый `java.lang.reflect.Proxy.newProxyInstance`.
- **КОРЕНЬ: в форке ВСЕ пути определения классов завёрнуты через защищённый парсер (принимает только protected-формат).** Proxy = JVM генерирует байты в рантайме (plain CAFEBABE) → парсер отвергает → «Corrupted classfile». ТЕМ САМЫМ объясняется и смерть от лямбд (v20): invokedynamic → LambdaMetafactory → рантайм-генерация класса → парсер → исключение. **Правило: работает ТОЛЬКО заранее скомпилированный класс через encode_protected + наш DefineClass; ЛЮБАЯ рантайм-генерация классов (Proxy, LambdaMetafactory, Groovy/CGLIB и т.п.) запрещена протектором.**
- Решения (обсуждены, юзер в фазе «просто вопрос»):
  - **A (рекомендовано, план v25): настоящий класс `HudFn implements kotlin.jvm.functions.Function1`** — компилируется pack_agent'ом (нужно добавить classpath `--cp` в pack_agent.py: kotlin-stdlib-2.1.20.jar из `rustme-launcher/profiles/prod-a/libraries/`; Function1 из stdlib, загружен в игре), регистрируется в статик-карту `IIlIIIlIIl(renderEventClass, fn)`. **Хаб-регистрацию (lllIIliil/lliIilliII/Proxy слушателя iiIiliiIiI) ВЫБРОСИТЬ целиком** — она не нужна: рендер-событие диспатчится через статик-карту (подтверждено дизасмом: renderGameOverlay @413 → iIlliiiliI.ilIillliil(event) → lIllIilliI.ilIIIIlIIl(event) → map.get(event.getClass()) → Function1.invoke(event)).
  - B (запасная): синтез class-байтов вручную + encode_protected + DefineClass.
  - C: Consumer-мост мода `lIlIIIlIIl(Consumer,Object)` — всё равно требует настоящий Consumer-объект → сводится к A.
- При «делай»: (1) pack_agent.py += classpath; (2) новый `noslow/util/HudFn.java` implements Function1 (invoke → CheatHud.accept, без лямбд); (3) subscribeRender урезать: только FontRenderer-резолв + IIlIIIlIIl; убрать findHubInstance/Proxy/hub-регистрацию.

**2026-09-05, v25: ROOT CAUSE Proxy/лямбд осознан + настоящий Function1-класс (16 классов, ждёт инжекта):**
- **Корень «Corrupted classfile» (Proxy v24) и смерти от лямбд (v20) — ОДИН:** всё, что JVM генерирует классами в рантайме (Proxy.newProxyInstance, LambdaMetafactory), создаётся PLAIN CAFEBABE и идёт мимо нашего encode_protected-конвейера → защищённый парсер отбрасывает («Corrupted classfile»). Всё, что компилирует pack_agent, шифруется и грузится OK. Правило: НИКАКОЙ генерации классов рантаймом в агенте.
- **Решение — компиляционный стаб:** `tools/compile_stubs/kotlin/jvm/functions/Function1.java` (интерфейс с invoke) → `tools/kotlin_stub.jar`; pack_agent получил `--cp` аргумент, build_dll.bat передаёт `--cp "%ROOT%..\..\tools\kotlin_stub.jar"`. Эрейзер: стаб совместим с настоящим Function1 игры (тот же метод).
- **HudFn = настоящий класс implements Function1<Object,Object>** (667 байт, проходит protected-кодирование, round-trip OK): invoke(event) → consumer.accept(event), return null (диспетчер результат игнорирует). GameContext.subscribeRender: НЕТ Proxy, НЕТ хаба — только регистрация HudFn в статической карте `lIllIilliI.IIlIIIlIIl(renderEventClass, fn)` (рендер-событие постится именно через эту карту: renderGameOverlay@413 → iIlliiiliI.ilIillliil → lIllIilliI.ilIIIIlIIl → HashMap.get → Function1.invoke на главном потоке). isInstance-проверка Function1 от gameLoader.
- kotlin-stdlib.jar в лаунчере ТОЖЕ защищён (MD5-имена энтрей, xor даёт CAFEBABE@256, но тело не парсится чистым парсером — по-видимому, полный protected-формат; не нужно — стаб решил).
- v25: 16 классов pack OK, build OK, invokedynamic 0. **Финальные правки при сборке:** (1) у юзера подтвердилась догадка «у нас всё само шифруется перед компиляцией» — Proxy шифроваться не мог, т.к. генерируется JVM в рантайме мимо конвейера; (2) HudFncompile-фикс: поле `private final java.util.function.Consumer consumer` (не CheatHud-тип — cast в GameContext ((Consumer) consumer)); (3) в invoke return null (не kotlin.Unit.INSTANCE — стаб не содержит Unit). **Ожидание от инжекта v25: `[HUD] subscribed via real Function1 class` → в мире справа сверху бокс со строкой AutoSprint, X тогглит (ON/OFF в логе уже работал в v22-v24).**
- **v25 ИНЖЕКТ: ПРОВАЛ тем же NPE — шина ОКОНЧАТЕЛЬНО мертва.** Лог: `[HUD][ERROR] subscribe failed exc=java.lang.NullPointerException: Cannot invoke "Object.getClass()" because "obj" is null` + `hud subscription FAILED` — хотя Proxy/хаб убраны и регистрация шла настоящим HudFn через IIlIIIlIIl. Вывод: NPE внутри модового/защищённого пути регистрации (obj.getClass() на null где-то в обфусцированном коде), не в нашем коде. Юзер: «давай лучше сделаем просто через матрицы, а не вот это всё очень трудное» → шина отброшена навсегда, переход к v26 (подмена GuiIngame).

**2026-09-05, КОНСУЛЬТАЦИЯ-4: «берёшь матрицу и рисуешь» — правда/нюансы (юзер спросил, ничего не делал):**
- Матрица есть всегда: любой GL-вызов рисует внутри текущей modelview/projection; к моменту renderGameOverlay игра УЖЕ настроила ortho-2D (ScaledResolution) → базовые элементы (drawRect, drawString) рисуются в готовой матрице без манипуляций — текущий HUD так и работает.
- **MatrixStack/Vector4f/Vector4i из референса юзера (Expensive-подобный клиент) — концепция MC 1.16+ (GL core убрал fixed-function стек); в 1.12.2 форке этого класса НЕТ.** Аналог = стек матриц OpenGL через GlStateManager: `pushMatrix() → translated(x,y) → scaled(1, animation, 1) → рисуем → popMatrix()` — именно так в референсе анимируется появление строк ArrayList'а.
- Когда матрица нужна: анимации масштаба/сдвига/поворота. Не нужна: прямоугольники/текст/градиенты в готовой 2D-матрице.
- Текущий CheatHud масштаб-анимацию делает АРИФМЕТИЧЕСКИ (пересчёт высоты строки и позиции, без матриц) — проще и достаточно. Если захочет true-масштаб/повороты как в референсе — GlStateManager форка дёргать через reflection (класс существует).
- **Юзер явно отказался от референса Expensive-клиента («про референс который я скидывал забудь, мы можем сделать по своему»)** — визуал ArrayList делаем СВОИМИ примитивами (drawRect/FontRenderer + арифметическая анимация), НЕ копировать его rounded-corners/Vector4i/тени; не предлагать портирование его утилит.
- Юзер ушёл инжектить v25 («ну щас проверю»). Ожидание: `[HUD] subscribed via real Function1 class` → бокс с AutoSprint справа сверху; если бокс не появится при зелёной подписке — в логе будет `[HUD] render exception` (копать accept()) либо тишина (пост событий идёт по другому пути — дизаснить дальше).

**2026-09-05, v26: HUD ЧЕРЕЗ ПОДМЕНУ GuiIngame (юзер отказался от шины — «слишком трудно», выбран путь «через матрицы/рендер напрямую»; 16 классов, ждёт инжекта):**
- Отказ от шины/Function1/Proxy совсем. **Классика 1.12.2: свой `rustme.CheatIngame extends rustme.liIIliliiI (GuiIngame)`**, override `iliIiiIliI(F)V` (renderGameOverlay, public, не final): super → затем CheatHud.renderFrame(). Игра сама зовёт на главном потоке каждый кадр.
- **Подмена:** GameContext.ensureHud() — найти поле с инстансом GuiIngame (скан нестатических полей gs → mc; instanceof liIIliliiI) → field.set(owner, new CheatIngame(gs)). Идемпотентно (проверка класса в поле), лениво доустанавливается каждый тик из главного цикла.
- **Компиляция против игровых классов:** `tools/game_cp.jar` = все 9977 rustme-классов из minecraft_FULL_DEOBF.jar (уже под чистыми именами!). build_dll.bat --cp = game_cp.jar. Наш класс в пакете `rustme` (jni/agent/src/rustme/CheatIngame.java), pack_agent подхватывает.
- CheatHud: static renderFrame(scaledW, scaledH, partialTicks) — Gui.drawRect + FontRenderer.drawString, ScaledResolution создаётся `new liIIiIliiI(gs)` прямо в CheatIngame (cast (iilliIliiI) ctx.getGsForRender()); GameContext.getGsForRender() отдаёт gs. GL11-матрицы убраны (lwjgl-opengl не в classpath) — анимация сделана «высотой строки» (y += LINE_H * a), визуально похоже. GL11-матрицы (push/translate/scale/pop) можно вернуть, добавив lwjgl-opengl jar в --cp.
- HudFn удалён, шина/стаб больше не нужны. 16 классов, build OK, 0 invokedynamic.
- Ожидание: `[HUD] ingameGUI swapped to CheatIngame` → в мире справа сверху бокс AutoSprint, X тогглит. Риски: (1) поле GuiIngame не в gs/mc — добавить сканы holder'а; (2) игра может пересоздавать GuiIngame — перезапись на тике покрывает; (3) чат-история сбросится (новый инстанс) — визуальный минус, не критично.

**2026-09-05, v26 инжект: СВАП ЗЕЛЁНЫЙ, HUD НЕ РИСУЕТСЯ; юзер спросил «нельзя ли проще без CheatIngame, просто отрисовкой» (ответ дан, ничего не менялось):**
- Лог v26: `[HUD] ingameGUI swapped to CheatIngame` + `hud installed` — поле найдено, инстанс поставлен, НО бокс на экране не появился. Toggle X продолжает работать.
- **Диагноз: подменили НЕ ТО поле (или мод рисует свой HUD не через этот GuiIngame).** Наш `iliIiiIliI(F)` не зовётся игрой — либо поле у gs копия/неиспользуемый инстанс, а живой GuiIngame в другом месте (holder мода `llIIiIiIiI`, mc), либо renderGameOverlay форка перекрыт модовым рендер-путём.
- **Следующий шаг (предложен, ждёт «делай»): (1) одна строка лога в CheatIngame.iliIiiIliI** — если зальётся (~каждые 50мс) → метод зовут, проблема внутри отрисовки (координаты/шрифт); если тишина → подменённый объект никем не вызывается, искать живой GuiIngame по ВСЕМ полям holder'а мода llIIiIiIiI (не только gs/mc).
- **Архитектурный ответ юзеру (зафиксировать): «просто отрисовкой без всего этого» не бывает** — вариантов ровно два: (а) быть частью игрового объекта, который игра вызывает (подмена GuiIngame — v26, GuiScreen для меню); (б) подписаться на чужой вызов (шина мода — v22-v25, отвергнуто). Рисовать из своего потока нельзя (GL-контекст у главного). CheatIngame = самый простой из возможных путей, свап-механика верна — просто искали поле не там.

**2026-09-05, v27: диагностическая сборка (16 классов, ждёт инжекта): v26 свап зелёный, но HUD не рисуется.**
- Лог каждого шага: ensureHud — 'ingameClass loaded' / 'FOUND GuiIngame: tag.field (class)' / 'no field found yet' (раз в 10с) / 'swapping field ... owner ... cur ...' / 'swap done, verified=...' / 'swap confirmed in place'. CheatIngame: 'constructed' / 'FIRST CALL: our iliIiiIliI invoked' / 'render alive, calls/5s-since-start=...' (heartbeat рендера каждые 5с) / ошибки super и нашего draw раздельно.
- Скан GuiIngame расширен: gs, mc, holder мода gs.IiIIllil (llIIiIiIiI) — ВСЕ нестатические поля, все найденные логируются (может быть несколько).
- Heartbeat агента уже был (каждые 10с '[Agent] heartbeat, inWorld=...'). CheatIngame heartbeat покажет, жив ли рендер-поток (краш игрового потока = лог остановится).
- Интерпретация: 'FIRST CALL' есть + HUD не виден → проблема в отрисовке (font/coords/res); 'FIRST CALL' нет → игра не зовёт наш объект (подменённое поле не используется — искать, кто реально зовёт renderGameOverlay, либо мод рисует поверх своим путём); 'swap done verified=true' но потом поле перезаписалось → игра пересоздаёт GuiIngame.

**2026-09-05, КОНСУЛЬТАЦИЯ-5: «вариантов больше нет? нельзя ли зайти в главный поток» (ответ дан, ничего не делалось):**
- Фундамент: кадр рисует главный поток → наш код должен исполниться на нём. Способов оказаться на чужом потоке в JVM ровно два: (1) поток сам вызывает твой код (сидеть в объекте, который он дёргает), (2) вмешаться в исполнение извне (натив). Третьего нет ни в Java, ни в натив.
- **Категория 1 (Java, «сиденья»):** (1) `mc.currentScreen` = наш GuiScreen — идеален для МЕНЮ (drawScreen + клики/клавиатура бесплатно, одно поле), но для постоянного HUD ломает геймплей (открытый экран перехватывает мышь — стрельба/копание); (2) подмена рендер-объектов (v26 GuiIngame) — не провал окончательно, ждёт диагностики v27; (3) ванильные тосты 1.12.2 — рендерятся на главном потоке в правом верхнем углу (там же, где наш ArrayList), свой IToast с бесконечной длительностью = постоянная зона рисования.
- **Категория 2 (натив) — прямой ответ на «зайти в главный поток»: DR0+VEH на потоке игры.** Аппаратный брейкпоинт на нативную функцию, зовущуюся каждый кадр на главном потоке (вход glfwSwapBuffers), VEH выполняется В КОНТЕКСТЕ того потока → рисуем в конце кадра перед свапом (ничего не перезатрёт), наш Java-рендер дёргается через приатаченный JNI. **ЭТО НЕ запрещённый «env-vtable/трамплины»-путь: память не патчится, таблицы не меняются — только debug-регистры потока; тот же DR0+VEH механизм уже работает у нас (обход валидатора).** Риски: протектор может мониторить SetThreadContext на чужих потоках (проверить первым тестом); исключение каждый кадр (расход мизерный); код в VEH не должен поднимать исключений.
- **Рекомендованный порядок (юзер пока не выбрал):** (1) диагностика v27 (уже собрана); (2) если подменённое поле мимо — DR0+VEH на glfwSwapBuffers как универсальное место на главном потоке (закрывает HUD+меню+всё сразу, независимо от того, куда игра кладёт свои объекты); (3) меню в любом случае — через currentScreen.

**2026-09-05, v27 ДИАГНОСТИЧЕСКАЯ СОБРАНА (юзер: «добавь везде логи на каждом шаге + heartbeat», 16 классов, ЖДЁТ ИНЖЕКТА):**
- CheatIngame: 'CheatIngame constructed' (в конструкторе) / 'FIRST CALL: our iliIiiIliI invoked, partialTicks=...' / 'render alive, calls/5s-since-start=...' — рендер-heartbeat каждые 5с (остановка = краш игрового потока; общий агентный heartbeat 10с уже был) / раздельные ошибки 'super overlay failed' vs 'our overlay failed'.
- ensureHud: лог каждого шага — 'ingameClass loaded' / 'FOUND GuiIngame: tag.field (класс)' для КАЖДОГО найденного поля (владельцы: gs, mc, holder мода gs.IiIIllil=llIIiIiIiI — расширенный скан ВСЕХ нестатических полей) / 'no GuiIngame field found yet' (раз в 10с) / 'swapping field X owner=... cur=...' / 'swap done, verified=true' (проверка чтением) / 'swap confirmed in place'.
- Интерпретация будущего лога: FIRST CALL есть + HUD не виден → чинить draw (font/coords/res); FIRST CALL нет → подменённое поле мёртвое (мод рисует своим путём) → переходить к DR0+VEH на glfwSwapBuffers (план из КОНСУЛЬТАЦИИ-5) или тостам; swap done=true но поле потом перезаписано игрой → GuiIngame пересоздаётся.

**2026-09-05, КОНСУЛЬТАЦИЯ-6: imgui для рендера (юзер спросил «можно ли рендерить просто с помощью imgui»; ответ дан, ничего не делалось):**
- ImGui напрямую НЕ работает, три причины: (1) imgui сам не рисует — каждый кадр генерит draw-data (вершины/команды), а рисует их внешний бэкенд (ImGui_ImplOpenGL3_RenderDrawData и т.п.), вызываемый в правильный момент кадра на главном потоке → упираемся в ту же проблему «кресла на главном потоке», которую решает v27; (2) imgui = C++ библиотека, Java-порты (imgui-java) тянут нативные библиотеки, а протектор (Astraea.dll) контролирует загрузку нативов; (3) imgui меняет GL-стейт (шейдеры/текстуры/blending), конфликт с фиксированным конвейером 1.12.2 без стейт-сэндвича.
- Актуален только для БОЛЬШИХ меню с контролами и только ПОСЛЕ получения места на главном потоке; для ArrayList (список строк) не нужен.
- Рекомендация: (1) сначала довести путь до кадра через v27-диагностику; (2) красота UI — использовать UI-фреймворк самого мода (Kotlin: rounded rects, свои шрифты — уже в игре и доступны из нашего кода), НЕ imgui; (3) ImGui/Swing в отдельном окне поверх игры — запасной вариант для сложных меню (виден в захвате экрана, не в game capture).

**2026-09-05, КОНСУЛЬТАЦИЯ-7: imgui-java (SpaiR) + «snuffy: пиши на плюсах» (юзер показал релизы imgui-java: java-libraries.zip 29.6MB + native-libraries.zip 9.4MB; ответ дан, ничего не делалось):**
- **imgui-java: красивое меню, но не решает нашу текущую проблему.** (1) ImGui только генерит draw-data — рисует внешний бэкенд (LWJGL3/GLFW-ориентированный), который нужно звать в правильный момент кадра на главном потоке = та же нерешённая задача «кресла» (v27 ищет её); (2) грузит нативные dll (imgui-java-natives-windows + JNI-биндинг) — протектор может проверить (нанативы LWJGL проходят, imgui — неизвестно); (3) +30МБ библиотек в процесс; (4) меняет GL-стейт — конфликт с fixed-function 1.12.2 без стейт-сэндвича. Вывод: imgui = «кожа», точка входа на главный поток = «кнопка» — сначала v27, потом imgui можно прикрутить НА найденной точке.
- **Ответ snuffy [XTZ] («нахуя джава под растми, на плюсах пиши»): прав для 99% читов (нативные хуки + C++ imgui), но rustme = 1% исключение:** нативная запись в память JVM заблокирована (VirtualProtect gle=87), env-vtable хуки закрыты решением юзера, а DefineClass+Java-агент — ЕДИНСТВЕННЫЙ доказанно работающий путь (JumpTest, AutoSprint). C++-чит под rustme всё равно идёт через JNI (MC = Java-объекты в GC-куче, оффсетов нет), причём GetFieldID/GetStaticFieldID скрыты протектором — из C++ наши примитивы недоступны. «Функции» (спринт/noslow/aim) в rustme делаются через Java-объекты независимо от языка; C++ слой нужен только как нативный рендер (DR0-хук на glfwSwapBuffers + C++ imgui), если решим делать ImGui-меню.
- Структура ответа snuffy: наш C++ слой УЖЕ есть (dllmain: attach, offsets, DR0+VEH, DefineClass); Java-часть переписывать не нужно; разделение ролей: C++ = отрисовка (опционально), Java = игровая логика.

**2026-09-05, v28: найдены 2 бага невидимого HUD (v27-лог: FIRST CALL есть, ~120 вызовов/сек — точка входа работает!):**
- v27-лог подтверждает: `FIRST CALL: our iliIiiIliI invoked` + `render alive, calls/5s=601..4671` (~120 FPS) — игра зовёт наш CheatIngame каждый кадр на главном потоке. GuiIngame нашёлся в `gs.liliiiIl` (единственное поле).
- **Баг 1: drawRect вызывался на INSTANCE-методе с null-приёмником и 5 аргами при (IIII) 4-аргах → silent IllegalArgumentException.** Правильно: **статик `iIlililiiI.lIilIliliI(IIIII)V`** = vanilla `Gui.drawRect(left, top, right, bottom, color)` (дизасм подтверждил: color>>24=alpha, >>16=red, >>8=green, &255=blue; 4 quad-вершины из (left,top),(left,bottom),(right,top),(right,bottom)). GameContext.drawRect теперь зовёт lIilIliliI.
- **Баг 2: ctx.fontRenderer никогда не резолвился** (resolveFontRendererInstance() звался только из удалённого subscribeRender) → renderFrame рано возвращался. Теперь ensureHud() зовёт resolveFontRendererInstance() при установке.
- Добавлен лог 'first frame DRAWN: rect (...) font=...' — одноразовый, покажет первый успешный кадр.
- ScaledResolution: lIllIlIliI()I читает iiiIIlil (1-е int поле = scaledWidth), IIllIlIliI()I читает IIiIIlil?? (по дизасму: IIllIlIliI читает III-поле — гм, IIIiIIlil? порядок полей: iiiIIlil, IiiIIlil, IIiIIlil; IIllIlIliI читает IIiIIlil = scaleFactor?!). ВНИМАНИЕ: IIllIlIliI()I читает поле #3 (IIiIIlil — вероятно scaleFactor!), а illlIlIliI()I читает IiiIIlil (scaledHeight). В CheatIngame сейчас вызов res.lIllIlIliI() (width ✓) и res.IIllIlIliI() (ВОЗМОЖНО scaleFactor — если ширина не та, заменить на res.illlIlIliI()).
- v28: 16 классов, build OK, 0 invokedynamic. Ожидание: 'first frame DRAWN' + бокс в мире.

**2026-09-05, v29: fontRenderer через наследуемый геттер (сборка OK, ждёт инжекта):**
- v28-лог: FIRST CALL есть, но 'first frame DRAWN' НЕТ и render exception НЕТ → renderFrame рано выходил на fontRenderer==null (скан gs-полей не нашёл FontRenderer — он в gs под Object-полем, но скан при свапе его не поймал, возможно ещё не создан/в другом поле).
- **Фикс: CheatIngame.iliIiiIliI зовёт наследуемый геттер `llliiiiIliI()` (GuiIngame.getFontRenderer, public) и GameContext.setFontRenderer(font)** — ровно так же берёт шрифт сам мод при рендере. Плюс исправлен height: ScaledResolution lIllIlIliI()=width (поле iiiIIlil), **illlIlIliI()=height** (поле IiiIIlil), а IIllIlIliI()I = scaleFactor (поле IIiIIlil — дизасм геттеров по полям подтверждён)! Раньше передавали scaleFactor как height.
- Ожидание: '[HUD] fontRenderer resolved via GuiIngame getter' + 'first frame DRAWN' + бокс AutoSprint справа-сверху.

**2026-09-05, v29: HUD РАБОТАЕТ — ПОЛНАЯ ПОБЕДА РЕНДЕР-АРХИТЕКТУРЫ (юзер: «урааа! Получилось!!!»):**
- Лог-подтверждение: `FIRST CALL` → `fontRenderer resolved via GuiIngame getter` → `first frame DRAWN: rect (441,4)-(505,16) font=true` → `render alive ~1152` (~230 FPS), X тогглит ON/OFF, ArrayList виден.
- **Итоговая рабочая архитектура рендера (эталон для всех будущих HUD/меню):**
  1. `rustme.CheatIngame extends rustme.liIIliliiI (GuiIngame)` — пакет rustme, компиляция против game_cp.jar (9977 классов из minecraft_FULL_DEOBF.jar, уже чистые имена).
  2. Override `iliIiiIliI(F)V` (renderGameOverlay): super.iliIiiIliI → setFontRenderer(llliiiiIliI()) — наследуемый геттер шрифта → CheatHud.renderFrame(scaledW, scaledH, partialTicks). v33-ПОПРАВКА: lIllIlIliI()=HEIGHT(509), illlIlIliI()=WIDTH(1018) — прежний маппинг был перевёрнут; в CheatIngame scaledW=max(ga,gb), scaledH=min(ga,gb).
  3. GameContext.ensureHud(): findIngameField (скан gs → mc → holder: поле gs.liliiiIl) → field.set(owner, new CheatIngame((iilliIliiI) gs)) — идемпотентно, лениво каждый тик.
  4. CheatHud: static renderFrame — Gui.drawRect (СТАТИК lIilIliliI(IIIII)V = vanilla left,top,right,bottom,color) + FontRenderer.drawString (lIlIIliliI(String,FFIZ)I, instance) + getStringWidth (IIIIIliliI(String)I) + анимации по dt.
- **СВОДКА ЗАПРЕТОВ в агенте (все причины):** (1) лямбды/:: → LambdaMetafactory → «Corrupted classfile»/тихая смерть потока; (2) Proxy.newProxyInstance → то же; (3) любая генерация классов рантаймом JVM мимо pack_agent — запрещена; (4) события мода/шины — не нужны, подмена объектов надёжнее; (5) drawRect — СТАТИК 5-арг (lIilIliliI), НЕ инстанс 4-арг (lllIIliliI); (6) height = illlIlIliI(), НЕ IIllIlIliI() (это scaleFactor).
- Следующие шаги рендера (по запросу юзера): меню по клику (GuiScreen-путь: mc.currentScreen swap — известна сигнатура), перенос ArrayList-стиля юзера (скругления через GL11-матрицы — нужен lwjgl-opengl в --cp или reflection), imgui-java как опция (нужна та же точка входа + нативы в процесс).

**2026-09-05, v30: СТРУКТУРА expensive-стиль (16 классов, build OK, 0 invokedynamic, ждёт инжекта):**
- `client/RustClient.java` — entry (pack_agent DEFAULT_ENTRY = client/RustClient), install() + главный цикл + heartbeat.
- `events/EventBus.java` — шина (impl-события остаются вложенными: TickEvent/Sneak*).
- `modules/api/Module.java` — базовый класс: name, state, toggle(), setState с хуками onEnable/onDisable, onTick(TickEvent); register(this) в конструкторе.
- `modules/api/Modules.java` — реестр List<Module> (get/set/all).
- `modules/impl/AutoSprint.java` — extends Module: handleKey (toggle X) отделён от onTick (спринт только когда isState()).
- `utils/etc/GameContext.java` — контекст + инъекция HUD (ensureHud); `utils/etc/Log.java` — логгер.
- `utils/render/CheatHud.java` — ArrayList (state через Module.isState()); `rustme/CheatIngame.java` — подменный GuiIngame (остаётся в пакете rustme — наследование игрового класса).
- ВАЖНО: old noslow/ удалён. Новые модули = новый класс в modules/impl extends Module + добавить в конструктор RustClient. HUD сам подхватит по реестру.

**2026-09-06, АНАЛИЗ expensive (юзер скачал в rustme/expensive for yougame) + GL-возможности форка (код не писан — юзер скажет):**
- **Expensive стек:** 1.12.2+forge, но с backport'ом blaze3d (com.mojang.blaze3d.* — в РУСТМЕ ЭТОГО НЕТ). Кастомный рендер = ShaderUtil (ARBShaderObjects/GL20, fragment-шейдеры: RoundedGlsl/RoundedOutGlsl/OutlineGlsl/FontGlsl/KawaseBlur/Bloom), DisplayUtils (Tessellator+BufferBuilder quads, drawGradientRound/drawRoundedCorner/drawShadow через FBO+Gaussian), шрифты = MSDF (multichannel SDF: TTF → PNG-atlas + JSON metrics, MsdfFont/Font/Fonts, рендер квадами с текстурным атласом, GL_LINEAR фильтрация), ColorUtils, Animation (ru.hogoshi).
- **ВАЖНО про GL в форке rustme:** ДВА GL-пути: (1) ванильный код идёт через org.lwjglx.* (lwjgl3ify шимп LWJGL2-поверх-LWJGL3), (2) МОДОВЫЕ классы (8 шт) используют НАТИВНЫЙ org.lwjgl.opengl.GL15/GL20/GL30 — шейдерный пайплайн доступен! Найден класс-программа шейдеров мода (rustme/iIIliIiliI: start/stop/uniform(String,F)/(String,FFF)/(String,FloatBuffer)/getUniformLocation, компиляция vsh+fsh из текстовых источников через BufferedReader, GL20). Но GLSL-исходников в классе НЕТ (загружаются из ресурсов jar — ресурсы зашифрованы).
- **Вывод для нашего кастомного рендера:** шейдеры ВОЗМОЖНЫ (контекст GL2.1+/GL3.x есть, мод сам их использует). Компилировать шейдеры мы можем СВОИ (GLSL-строки прямо в Java-коде, как expensive RoundedGlsl). Рисование квадов — через ванильный Tessellator/BufferBuilder (org.lwjglx) или напрямую GL11/GL20. Шрифты: MSDF-атлас (как expensive) требует TTF→атлас пайплайн (msdf-atlas-gen на этапе сборки) + загрузку PNG-текстуры; АЛЬТЕРНАТИВА дешевле — java.awt.Font рендер глифов в BufferedImage → DynamicTexture → свои квады (классический «custom font» 1.12.2 читов, качество почти MSDF при 2x суперсэмплинге).
- **План (когда юзер скажет):** (1) utils/render/RenderUtil + ShaderUtil (GL20 direct, свои GLSL-строки): drawRoundRect с скруглением (SDF-шейдер RoundedGlsl адаптировать), градиенты, шадоу через FBO; (2) CustomFont: java.awt.Font → атлас → drawString(x,y,size,color); (3) ArrayList/HUD/menu переписать на новый рендер. Риски: FBO в ванильном пайплайне 1.12.2 (GlStateManager-состояния), загрузка DynamicTexture (registerTexture — нужен вызов в GL-потоке), протектор vs natively созданные GL-объекты (мод сам создаёт — путь проверен).

**2026-09-06, org/lwjglx — можно ли звать напрямую (ответ юзеру, код не писан):**
- **ДА.** org.lwjglx.opengl.GL11/GL14 — это lwjgl3ify-шимп (LWJGL2-статик-API поверх LWJGL3), в рантайме классы СУЩЕСТВУЮТ в JVM (ванильные 20 классов их активно зовут: glEnable/glDisable/glScissor/glTexParameteri/glTexImage2D/glColorPointer/glDrawArrays...). Классов в дампе-джарах нет под чистым именем (они в защищённом boot-наборе), но loadClass из агента их найдёт (загружены boot/system лоадером — Class.forName сработает).
- Для КОМПИЛЯЦИИ нужен стаб: org.lwjglx.opengl.GL11 — статические методы-обёртки над GL (сигнатуры повторяют LWJGL2: glEnable(int), glPushMatrix(), glTranslatef(f,f,f), glBegin/glEnd, glTranslatef и т.д. — полный список LWJGL2 GL11 известен, стаб можно сгенерить на нужное подмножество).
- ВАЖНО: GL11-шимп = fixed pipeline (тот же ванильный). Скругления/тени всё равно требуют шейдеры (GL20 native — мод сам так делает) или SDF-хитрости. Но lwjglx решает: матрицы (glPushMatrix/Translatef/Scalef), glBegin-квады, scissor, прозрачность — весь рендер-фундамент для кастомных элементов БЕЗ нативных шейдеров.
- Компиляция: стаб org/lwjglx/opengl/GL11.java + GL14 в compile_stubs → kotlin_stub.jar переименовать в game_stubs.jar; класс-путь pack_agent --cp уже есть.

**2026-09-06, v31: lwjglx-рендер подключён (17 классов, build OK, ждёт инжекта):**
- Стаб `tools/compile_stubs/org/lwjglx/opengl/GL11.java` (константы + статик-методы LWJGL2-сигнатур: push/popMatrix, translatef/scalef, begin/glEnd/vertex2f/color4ub, blendFunc, scissor, texture) → `tools/game_stubs.jar`; build_dll.bat --cp = game_cp.jar;game_stubs.jar.
- **`utils/render/RenderUtil.java` — рендер-утилиты НАПРЯМУЮ через org.lwjglx.opengl.GL11 (без reflection!):** drawRect (квады color4ub+blend), drawGradientRectV (полосный градиент), drawRoundedRect (имитация скругления ступеньками — честное скругление этапом 2 через GL20-шейдер), scaleStart/scaleEnd (матричная анимация), scissorStart/End (scale по screenHeight/240 — проверить на практике).
- CheatHud переписан на RenderUtil: скруглённый фон с вертикальным градиентом, акцентные полоски строк, матричное «выезжание» строк (scale вокруг центра строки), лог 'first frame DRAWN (RenderUtil)'.
- РИСК: org.lwjglx.GL11 в рантайме резолвится boot-лоадером — linker должен найти; если NoClassDefFoundError/NoSuchMethod — смотреть лог 'render exception' и сверять сигнатуры (шимп может иметь другие внутренние имена, но публичный API LWJGL2-стиля стандартен).
- Этап 2 (когда попросит): GL20-шейдеры (native org.lwjgl.opengl — мод сам использует 8 классов) — честные скругления/тени как expensive.

**2026-09-06, v31 РАБОТАЕТ (юзер: «Отлично все работает!»):** lwjglx-шимп резолвится boot-лоадером, RenderUtil рисует напрямую: `first frame DRAWN (RenderUtil): rect (439,4)+[66x13]`, ~1180 вызовов/5с (~236 FPS), toggle X ON/OFF анимируется. Эталон подтверждён: стаб-LWJGL2-сигнатуры совпали с настоящим шимпом. Следующий этап: GL20-шейдеры (ShaderUtil) + честное drawRoundedRect.

**2026-09-06, v32: GL20-шейдеры подключены (сборка OK, ждёт инжекта):**
- Стаб GL20 (org/lwjgl/opengl/GL20.java: glCreateProgram/AttachShader/LinkProgram/CompileShader/ShaderSource/GetUniformLocation/glUniform1f/2f/4f/1i/glDetachShader/glDeleteShader/glUseProgram + константы GL_FRAGMENT_SHADER/GL_VERTEX_SHADER/GL_COMPILE_STATUS/GL_LINK_STATUS) добавлен в game_stubs.jar (там же GL11 + glTexCoord2f).
- **utils/render/ShaderUtil.java** — компиляция GLSL-программ (конструктор compile+link+detach+delete, start/stop, uniformF/2F/4F/I), лог ошибок компиляции/линка в Log.
- **utils/render/Shaders.java** — GLSL-строки: VERT (#version 120, ftransform + texcoord) и ROUND (SDF roundedBox + поканальный градиент color1..color4 TL,TR,BL,BR + smoothstep alpha). Как RoundedGlsl в expensive.
- **RenderUtil.drawRoundedRectShader(ctx,x,y,w,h,radius,c1..c4)** — честный скруглённый: квад в ЭКРАННЫХ пикселях (scaled × guiScale), texcoord через glTexCoord2f, uniforms size/radius/colors, blend, shader start/stop. GuiScale (utils/render/GuiScale.java) — кэш; вычисляется в CheatIngame: framebufferWidth/scaledWidth; resolveFramebufferWidth() = максимальное «разумное» int-поле mc (эвристика displayWidth; 100..16384).
- CheatHud: фон ArrayList теперь drawRoundedRectShader(BG_COLOR→BG_COLOR2 градиент, radius 3).
- v32: 18 классов (ShaderUtil/Shaders/GuiScale +), 0 invokedynamic. РИСКИ: (1) shader compile error на реальном GL (GLSL 120 должен поддерживаться контекстом, где мод использует GL20+); (2) guiScale-эвристика может взять displayHeight вместо width — если скругления «гигантские/микро», поправить резолвер; (3) discard в шейдере + fixed-pipeline квада — состояние GL: вернуть TEXTURE_2D/blend как было.
- Ожидание: 'first frame DRAWN (RenderUtil)' + скруглённый градиентный ArrayList; при compile failed — в логе 'compile failed: ...' с логом GLSL.

**2026-09-06, v33: шейдеры РАБОТАЮТ (v32-лог: свап/шрифт/`first frame DRAWN` все зелёные), исправлена позиция:**
- v32-жалоба юзера: текст по центру сверху, плашка справа. ПРИЧИНА: у ScaledResolution геттеры lIllIlIliI/illlIlIliI дают 509/1018 — lIllIlIliI = ВЫСОТА (не ширина, как я предполагал по порядку полей). Текст рисовался на x=446 из реальных 1018 (43% = центр), а плашка случайно попала в угол (guiScale-компенсация ×2, размер плашки задваивался).
- **Фикс v33: в CheatIngame ga=lIllIlIliI, gb=illlIlIliI; scaledW=max(ga,gb), scaledH=min(ga,gb) (landscape-эвристика); guiScale = framebufferWidth/scaledW — теперь честный (2.0), плашка в правильном масштабе.**
- Стабы: GL20 стаб изначально без glDetachShader, GL11 без glTexCoord2f — добавлены (javac-ошибки указали).
- ПРАВИЛО: обфусцированные поля нельзя мапить «по порядку декларации как в ваниле» — форк мог переупорядочить; семантику геттеров проверять по дизасму/значениям в рантайме.

**2026-09-06, v34: СИСТЕМА КООРДИНАТ унифицирована (v33 фикс не помог: x=439 тот же):**
- Корень: к моменту нашего draw (после super.iliIiiIliI) ванильный GL-стейт (масштаб guiScale=2) уже снят модом → мы рисуем в ФИЗИЧЕСКИХ пикселях: текст x=446/1018=центр, шейдерная плашка 878/1018=справа — точно жалоба юзера v32. В v29 «работало», потому что ctx.drawRect звал ВАНИЛЬНЫЙ lIilIliliI, который... рисовал в scaled-стейте? НЕТ — v29-плашка (441,4)-(505,16) была справа = тоже физическая? (441..505 из 1018 = слева-центр!) — юзер тогда не жаловался на позицию плашки, но вероятно она тоже была смещена/мелкая.
- **Фикс v34: весь наш рендер в SCALED-пикселях, но с СОБСТВЕННОЙ матрицей:** drawRoundedRectShader ставит glPushMatrix → glTranslatef(x*scale, y*scale) → glScalef(scale) → квад в локальных (0,0)-(w,h) → popMatrix. Uniform size/radius — в scaled-юнитах (шейдеру всё равно, он работает по texcoord). Текст drawString — как раньше, scaled.
- ПРАВИЛО: наш HUD-рендер живёт в SCALED-пространстве; физические пиксели нужны только шейдерным uniform'ам через guiScale, если рисуем без матрицы.
- v34: сборка OK ([build] OK), 18 классов, ждёт инжекта. Ожидание: плашка+текст вместе справа сверху в честном масштабе; если сядет — рендер-база закрыта (дальше: меню, кастомный шрифт, новые модули).

**2026-09-06, v35: вся отрисовка переведена в ФИЗИЧЕСКИЕ пиксели + диагн-лог значений (ждёт инжекта):**
- v34 (матрица под шейдером) — плашка ИСЧЕЗЛА, текст остался по центру. Вывод из истории: наш рендер живёт в ФИЗ системе (ванильный guiScale-стейт снят), v32-плашка (439*2=878) была справа ✓, текст (446) — центр ✗.
- v35: (1) плашка = v32-стиль (ex=x*scale вручную, без матрицы); (2) ТЕКСТ теперь тоже в физике: RenderUtil.drawStringScaled (glPushMatrix+glScalef(scale)+drawString(scaled)+popMatrix); (3) CheatIngame логирует ga/gb/fbw/guiScale раз при первом кадре — снимет догадки о размерах.
- Порядок разбора следующего лога: если ga=509,gb=1018 → scaledW=1018, x=948 scaled → текст 948*2=1896 справа; если ga=509,gb=509 → оба геттера одинаковые, нужно искать ТРЕТИЙ геттер (illlIlIliI третий?) или другой источник ширины. guiScale=fbw/scaledW.

**2026-09-06, v36: КАРТА ГЕТТЕРОВ ScaledResolution доказана дизасмом ctor — финальный фикс позиций (сборка OK, ждёт инжекта):**
- Юзеров v35-лог (ga=509 gb=2 fbw=9638→растёт на 1/кадр guiScale=19) вскрыл: (1) gb=illlIlIliI() = GUI SCALE (не height!); (2) fbw-эвристика ловила счётчик кадров, не displayWidth; (3) текст пропал из-за матрицы ×19.
- **Дизасм ctor liIIiIliiI (ScaledResolution) дал КОНЕЧНУЮ карту:** поля: IIiIIlil=fbW (статик ililiilliI.iIilIlIIIl), iiiIIlil=fbH (iillIlIIIl), IiiIIlil=guiScale (старт=1, переопределяется настройкой мода getVanillaGuiScale(), потом vanilla-цикл по ширине>320). Затем scaledW=floor(fbW/gs) пишется ОБРАТНО в IIiIIlil, scaledH=floor(fbH/gs) в iiiIIlil. **ГЕТТЕРЫ: IIllIlIliI()=scaledWidth, lIllIlIliI()=scaledHeight, illlIlIliI()=guiScale.**
- Юзеров ga=509 = scaledHEIGHT (fbH=1018/2=509) — сходится: fbW=2036/2=1018=scaledWidth (IIllIlIliI).
- **v36: CheatIngame: scaledW=res.IIllIlIliI(), scaledH=res.lIllIlIliI(), GuiScale.set(res.illlIlIliI()) — одноразовый лог.** GameContext.resolveFramebufferWidth удалён. Плашка (v35-физика) и текст (drawStringScaled) обе через честный guiScale=2: x=(1018-66-4)*2=1896 из 2036 = правый край ✓.
- УРОК: обфусцированные int-геттеры мапить ТОЛЬКО по дизасму ctor (порядок putfield+арифметика), не по «разумным» значениям — 509 выглядело как width.

**2026-09-06, v37: СОГЛАСОВАНИЕ систем координат по эмпирике (сборка OK, ждёт инжекта):**
- v36-лог: scaledW=960 scaledH=509 guiScale=2 (ВРОДЕ честно), rect (890,4)+[66x13] — но юзер видит ТОЛЬКО линию (акцентная полоска по центру-справа?). Вывод-эмпирика:
  (1) ВАНИЛЬНЫЕ вызовы (drawRect lIilIliliI + drawString FontRenderer) работают в SCALED-системе — в v29-31 плашка/текст сидели правильно;
  (2) НАШИ сырые GL-квады/шейдеры (lwjglx/lwjgl3) — в ФИЗИЧЕСКОЙ системе (v32: 439*2=878 — юзер видел справа).
- Причина разницы: у FontRenderer/Gui СВОЙ масштаб внутри (они сами умножают на guiScale или рисуют через glScale-стейт, который у них живёт), а сырой GL11/GL20-вызов lwjglx идёт мимо этого.
- **v37: плашка — шейдер в физике (ex=x*gs, uniforms size=(w*gs,h*gs) в физ.юнитах — как v32, где было видно); текст — drawString scaled БЕЗ матриц (как v29-32); акцент — ванильный drawRect (drawRectVanilla) scaled.**
- v35-матрица (glScalef для drawString) удалена — с MSDF-шрифтом мода она не работает (шрифт сам управляет матрицами через свои шейдеры).
- Ожидание: плашка справа сверху (1780..1912 из 1920), текст НА ней (x=897 scaled → модовый FontRenderer ×2=1794 — совпадает с плашкой!), акцент справа. ВСЁ СОЙДЁТСЯ в одном месте впервые.

**2026-09-06, v38: шейдерная плашка на gl_FragCoord (сборка OK, ждёт инжекта):**
- v37-лог: текст ВИДЕН (справа, на месте), плашка-шейдер НЕТ (только акцентная линия). Причина (гипотеза №1, главная): через lwjglx glTexCoord2f значение НЕ доходит до gl_MultiTexCoord0 в шейдере (VAO/fixed-атрибуты LWJGL3) → texcoord=(0,0) → SDF dist от угла квада → alpha=0 → discard всюду.
- **Фикс v38: ROUND-шейдер полностью на gl_FragCoord** (физ.пиксели): uniform rect=(x,y,w,h) в физике + fbHeight (переворот Y: p.y = fbHeight - p.y), локальные координаты, SDF roundedBox, градиент по local/rect.zw. texcoord изъят. RenderUtil: uniforms rect/fbHeight вместо size; radius в физ.пикселях. GameContext.fbHeight = scaledH*guiScale (заполняется в CheatIngame раз).
- Плюс убрать setTex/texU/texV при успехе (пока оставлены, не мешают).
- Ожидание: плашка справа сверху с честным скруглением и градиентом; текст на ней.

**2026-09-06, v39: КОРЕНЬ невидимости шейдерной плашки найден (сборка OK, ждёт инжекта):**
- v38: текст виден, плашка нет (gl_FragCoord-шейдер). Сравнение с v31 (юзер: «отлично работает» — фон GL11-квадом был виден!) вскрыло: **в момент нашего вызова ModelView-матрица ВАНИЛЬНОГО guiScale ЖИВА и масштабирует наши вершины ×2.**
- Эволюция: v31 вершины scaled (890..956) → экран (1780..1912) ВИДНО. v32+: я подавал «физ»-вершины (1780..1912) → матрица ×2 → 3560..3824 ЗА ЭКРАНОМ → плашка исчезла (в v32 юзер видел её «справа», вероятно это был акцент/или window больше). Текст виден всегда — FontRenderer сам выставляет свои матрицы.
- **ПРАВИЛО v39: сырой GL-квад рисуем вершинами в SCALED-координатах (матрица guiScale применит ×2 сама); uniform'ы шейдера (rect/fbHeight/gl_FragCoord) — в ФИЗИЧЕСКИХ пикселях (fragcoord от матрицы не зависит).** Т.е. вершины и фрагментные координаты — в разных системах, и это норма.
- RenderUtil.drawRoundedRectShader: вершины (x..x+w scaled), uniforms rect=(x*gs, y*gs, w*gs, h*gs), fbHeight=scaledH*gs.
- Диагн-логи в шейдере пока не добавлены (ошибки компиляции логируются); если снова пусто — добавить лог program id + glGetShaderi/GL_LINK_STATUS.

**2026-09-06, v39 инжект-результат: ВСЁ ЗАРАБОТАЛО — текст + шейдерная скруглённая плашка на месте (юзер: «теперь всё работает»). РЕНДЕР-АРХИТЕКТУРА ЧИТА ПОЛНОСТЬЮ ЗАВЕРШЕНА:**
- Финальная конфигурация, которая работает: вершины шейдерного квада в SCALED-координатах (живая ванильная ModelView-матрица guiScale сама ×2), uniform'ы (rect, fbHeight, radius) в ФИЗИЧЕСКИХ пикселях (gl_FragCoord от матриц не зависит), текст — модовый FontRenderer в scaled без наших матриц, акцент — ванильный drawRect в scaled. fbHeight = scaledH × guiScale (одноразово в CheatIngame), guiScale = res.illlIlIliI() — прямой геттер ScaledResolution.
- Работающий HUD = CheatIngame (подмена gs.liliiiIl) → RenderUtil.drawRoundedRectShader (SDF roundedBox, градиент 4 угла, smoothstep сглаживание, discard вне) + drawRectVanilla (полоски) + drawStringScaled (просто drawString). ShaderUtil (GL20 native) компилирует GLSL из строк Shaders.java.
- Дублирующие/устаревшие версии шейдерных функций (имитация скругления ступеньками, drawGradientRectV полосный, size-uniform версия) остаются в коде, но не используются — при чистке не удалить случайно то, что вызывается из CheatHud.
- Следующие шаги (не начаты): меню (mc.currentScreen подмена GuiScreen), новые модули в modules/impl, чистка диагн-логов (scaledW/scaledH/guiScale лог в CheatIngame каждый кадр когда guiScale<=0 — убрать условие или оставить), кастомный шрифт.

**2026-09-06, v39 РАБОТАЕТ (юзер: «даааа! Ура теперь все видно!!») + v40 чистка логов (сборка OK):**
- **КАСТОМНЫЙ РЕНДЕР ПОЛНОСТЬЮ ЗАРАБОТАЛ:** GL20-шейдер (SDF roundedRect + 4-цветный градиент через gl_FragCoord) + модовый MSDF-шрифт + ванильный drawRect — всё на своих местах справа сверху. Эталонная система координат: вершины сырого GL — SCALED (ванильная ModelView ×guiScale жива), uniform'ы/gl_FragCoord — ФИЗИЧЕСКИЕ пиксели, текст — drawString scaled (FontRenderer сам масштабирует), ванильный drawRect — scaled.
- v40: убран per-frame спам (scaledW=... из-за бага: ctx.guiScale не заполнялся — кэш GuiScale заполнялся, а поле ctx нет; теперь оба), убран render-alive heartbeat. Одноразовые диагн-логи (FIRST CALL, first frame DRAWN, scaledW=) оставлены.
- Рендер-стек готов к: меню (GuiScreen-путь), шейдерные тени/обводки (Kawase/Bloom из expensive при желании), кастомный шрифт (java.awt → атлас), новые модули.

**2026-09-06, v41: FullBright (toggle J) добавлен (сборка OK, ждёт инжекта):**
- **LightTexture найден окончательно: `rustme/llilIiiIiI`** (11 полей; mc=lIIIliiiI; IIiIliiiI/IiIIliiiI = AbstractTexture liIIIIIIiI). Инстанс в GameRenderer (lIliIiiIiI) поле `llIlllll` (единственное типа llilIiiIiI). liIIliliII(F)V = updateLightmap: при ночном зрении (liIililiiI.iiiiiIiII → illIlilliI.IIiiliIlIl) РАННИЙ RETURN (форк не пересчитывает).
- texId: AbstractTexture.liIIIIIIiI.lIiiIililI()I (лениво glGenTextures; не iiiiIililI!).
- Ванильная гамма мертва подтверждена: GameSettings.IilIllil (единственный float) читается/пишется ТОЛЬКО своим геттером/сеттером (iilliIliiI.IllliilliI) — рендер не читает.
- **FullBright (modules/impl/FullBright.java): toggle J (GLFW 74); каждый кадр (из TickEvent при isInWorld, главный цикл агента) → apply(): GameRenderer.llIlllll → lightTex → texId → glBindTexture(3585, texId) + glTexSubImage2D(3585,0,0,0,16,16,6408=GL_RGBA,5121=GL_UNSIGNED_BYTE, белый 16×16×4) + unbind.** GL-вызовы через reflection org.lwjglx.opengl.GL11 (GameContext.glBindTexture/glTexSubImage2D static-обёртки, resolve once). whiteBuf — direct ByteBuffer 1024 байта 0xFF.
- GameContext: +ByteBuffer import. RustClient: new FullBright() рядом с AutoSprint. HUD сам подхватит из реестра.
- ОЖИДАНИЕ: тап J → FullBright ON в HUD → экран светлеет (лампы/пещеры). Если нет — варианты: (1) lightmap перезаписывается ПОСЛЕ нас (сдвинуть apply в тик до рендера), (2) glTexSubImage2D-обёртка lwjglx имеет другую сигнатуру (проверить NoSuchMethod в логе).
- LightTexture-ГАТЧ: updateLightmap при night vision ретёрнит → если юзер выпьет ночное зрение, наш белый lightmap останется (даже лучше).

**2026-09-06, v42: FullBright фиксирован (найдена причина NPE-стиля) — ждёт инжекта:**
- v41 ошибка: `IllegalArgumentException: object is not an instance of declaring class` — звали liIIIIIIiI.lIiiIililI() на llilIiiIiI-объекте, но **llilIiiIiI (fog/свет-класс форка) НЕ extends AbstractTexture, а ДЕРЖИТ ДВА AbstractTexture-поля: IIiIliiiI и IiIIliiiI** (super = Object!).
- Дизасм llilIiiIiI.liIIliliII(F)V — НЕ updateLightmap (это fog-туман: lIillIiliI = fog-стейт). НО: **при night vision (liIililiiI.iiiiiIiII → illIlilliI.IIiiliIlIl) — РАННИЙ RETURN**, как vanilla NV. llilIiiIiI.iilIliliII()V — создаёт/обновляет обе текстуры из текстур игрока (iIilIiilII/iliIIiilII у Player).
- **Фикс v42: apply() заливает белый 16×16 в ОБЕ текстуры llilIiiIiI (texFields массив).** Одна из них — lightmap (вероятно), вторая — fog/погода-оверлей (зальётся тоже, не страшно). Лог: 'resolved: N texture fields' + 'white lightmap applied to tex N (field ...)'.
- Если и после этого нет света — lightmap в форке вообще не используется (например, шейдерный рендер мира), и FullBright придётся делать через подписку на fog-ветку (llilIiiIiI.liIIliliII — там ещё есть ветка с llIililiII/GL-вызовами; можно mirror-ить наш белый залив). НО сначала тест v42.
- NativeAPI (ru/rustme) — invokedynamic-обёртка (LambdaMetafactory), не API света. строки 'lightMap'/'lightTex' в lIliIiiIiI — ключи НАСТРОЕК/профайлера, не классы.

**2026-09-06, v43: НАСТОЯЩИЙ updateLightmap форка найден — FullBright переписан (сборка OK, ждёт инжекта):**
- **Гамма живёт в НАСТРОЙКАХ МОДА** (юзер был прав!): `ScreenSettingsCategory.getGamma` → FloatNode; читается в `GameRenderer.lIliIiiIiI.IiIIllIiII(F)V` (code 967 байт) = НАСТОЯЩИЙ updateLightmap форка: пересчёт RGB (sun brightness + NV + gamma из FloatNode.getValue) → clamp → **запись в int[256] массив `lIliIiiIiI.IIiiiiiiI`** (единственный int[] в классе; lightmap = int-массив, не текстура напрямую!).
- GameSettings.IilIllil (единственный float) — мёртв (reader'ы только его own getter/setter) — подтверждено сканом readers.
- **v43 FullBright: apply() пишет 0xFFFFFFFF в int[256] IIiiiiiiI каждый тик (из TickEvent, в мире).** resolve: единственное нестатическое int[] поле в mcClass. GL-заливка v41/v42 убрана (она же вызывала краш — glTexSubImage2D с ByteBuffer на чужой текстуре).
- Краш v41/v42 объясним: glTexSubImage2D (ByteBuffer) на fog-текстуре не 16×16 → GL-ошибка → падение рендера.
- ОЖИДАНИЕ: J тап → FullBright ON → мир светлеет. Если мод пересчитывает массив ПОСЛЕ нашего тика (в его рендер-фазе) — свет мигает/не виден; тогда перенести apply в CheatIngame (рендер-фаза, перед super) ИЛИ заливать прямо после IiIIllIiII — но его вызов нам не перехватить без хука; наш TickEvent крутится 1мс циклом — свет должен перезаписываться чаще, чем мод успевает перерисовать.

**2026-09-06, v44: FullBright ЧЕРЕЗ ГАММУ МОДА (сборка OK, ЖДЁТ ИНЖЕКТА — это текущее состояние!):**
- v43 (int[256] white overwrite) — мигает: мод пересчитывает lightmap каждый кадр через gamma-слайдер. ПРАВИЛЬНЫЙ путь: **не перезаписывать выход, а крутить ВХОД**.
- **НАСТОЯЩИЙ updateLightmap форка = GameRenderer.lIliIiiIiI.IiIIllIiII(F)V (967 байт)**: читает ГАММУ МОДА `ScreenSettingsCategory.getGamma()` → FloatNode → getValue() → float, затем пересчёт RGB → запись в `lIliIiiIiI.IIiiiiiiI` (int[256], единственный int[] в классе). GameSettings.IilIllil — МЁРТВА (единственный float в gs, читается только собственным геттером/сеттером — скан по jar).
- **v44: FullBright тап J → gamma FloatNode.setValue(100.0f)** (OptionNode.setValue пишет storedValue при tempState=false БЕЗ клампа validateValue — кламп только в temp-ветке). onDisable → восстановление прежнего значения (сохранено при resolve). resolve: ctx.settingsObj (заполняется в resolveSprintKey) → getData() → getScreenSettings() → getGamma().
- GameContext.settingsObj — public field, заполняется в resolveSprintKey() (вызывается из init + лениво из тика). РЕНДЕР-БАЗА работает (v40: HUD/ArrayList/шейдеры/координаты). NoSlow-модуль прежний (v44-архитектура modules/impl).
- СТАТУС: v44 собрана, НЕ инжекчена. Полный контекст сессии перенесён в ZNANIA.md раздел 12.
