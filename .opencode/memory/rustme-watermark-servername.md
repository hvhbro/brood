---
name: rustme-watermark-servername
description: Имя сервера в watermark РАБОТАЕТ (подтверждено юзером 09-08) — источник =
  заголовок таба (S47-аналог rustme/iIllilIIiI → netHandler.lIiiillliI → Tab GUI
  liIlliliiI поле iIIiIIil, plain-text lllIlliIIl()), читается каждый кадр без
  открытия таба через наш GuiIngame; ФОРМАТ хедера многострочный с §-кодами:
  строка 1 = 'RustMe » Имя-режима' (парсер: stripColorCodes → первая непустая
  строка → текст после » U+00BB), строка 3 = Онлайн; mapId = id карты/вайпа НЕ
  сервера; watermark: имя таба → mapId → часы; 22:38 вся диагностика (KEY-I/
  TAB-ROWS/probe) удалена, клавиша I свободна; 22:48 фикс центрирования —
  остров+бары строго по центру, лейбл слева с LABEL_GAP=4
metadata:
  node_type: memory
  type: project
  originSessionId: sess_38c38aa5-3399-499f-a421-f5ad08526a8c
---

Юзер (09-08, «пока что просто вопрос» — [[rustme-phase-gating]]): можно ли в watermark вместо времени HH:mm показывать, на каком сервере играет игрок (Песочница, duo-1, duo-2, unlimited-11). ОТВЕТ ДАН 09-08: ДА, реализуемо; цепочка чтения найдена и проверена javap -c. Кода нет — ждёт «делай».

**ГЛАВНАЯ ЦЕПОЧКА (проверено дизасмом):**
- GameSettings (rustme/iilliIliiI) держит ServerData в поле `iiIIllil:Object`: геттер `IiIiIilliI()` (getfield iiIIllil → checkcast lIIlIIliiI → areturn), сеттер `iiIliilliI(Lrustme/lIIlIIliiI;)V`.
- Заполняет GuiConnecting-аналог `rustme/iiIlIIliiI`: ctor(GuiScreen, gs, host:String, port:int) → `gs.liiliilliI(null)` → `new lIIlIIliiI(IliliiliII.liIiIiliII, IliliiliII.lliiIiliII, false)` → `gs.iiIliilliI(sd)`. Пишется ОДИН раз при коннекте и живёт ВСЮ сессию → нет timing-гонок (в отличие от state-полей, ZNANIA 11). В GameSettings есть путь, пишущий iiIliilliI(null) — при выходе в меню поле пустое → watermark обязан null-чекать и фоллбэк на часы.
- `lIIlIIliiI` ctor(String,String,Z): arg1 → поле `lIiilIIl:String` (ванильный serverName), arg2 → поле `liiilIIl:String` (serverIP). Геттеры не размечены — читать поля напрямую reflection-чтением (оно работает).
- У нас gs уже в GameContext → для watermark это тривиальное чтение вместо часов.

**Что в поле «имя» — УЖЕ ИЗВЕСТНО (дизасм статик-инициализации коннектора 09-08, вторая волна):** `iiIlIIliiI.IIiliIlliI()` пишет статики IliliiliII ХАРДКОДОМ ИЗ ДАМПА: `liIiIiliII="rustme"` (→ ServerData.name), `lliiIiliII="lplay.rustme.net:25565"` (→ ServerData.ip). Реальный хост коннекта — slctl.rustme.ru:25565: аргумент `--server` идёт в поток-коннектор llilIIliiI (InetAddress.getByName) и в ServerData НЕ попадает; лог игры подтверждает «Connecting to slctl.rustme.ru, 25565» (profiles/prod-b/logs/latest.log). ИТОГ: gs.IiIiIilliI() даст «rustme», красивых имён (Песочница/duo-/unlimited) в 9977 классах НЕТ (перепроверено дважды), servers.dat в профилях НЕТ (prod-b: только options.txt) → матч по ServerList МЁРТВ.

**План (дан юзеру, ждёт «делай»):** (1) ПРОБА — в Watermark раз в 5с логировать `gs.IiIiIilliI()` → lIiilIIl/liiilIIl (5 строк, едет с любой следующей сборкой); (2) если там сырой адрес — матч host→имя по ServerList `ilIlIIliiI` (servers.dat, ip-поле liiilIIl) или реверс HTTP-источника имён; (3) запасной вариант — захардкоденная мапа адресов в агенте (сломается при добавлении серверов).

**Дополнено сессией 09-08 (вторая волна дизасма, всё проверено):**
- У коннектора `iiIlIIliiI` ДВА ctor: (String,int) — пишет хардкод IliliiliII в ServerData; ВТОРОЙ `(GuiScreen, gs, lIIlIIliiI)` — кладёт переданный sd как есть (путь сервер-листа). Оба зовут `gs.iiIliilliI(sd)`.
- Реальный адрес живого соединения берётся из нетти-канала: NetworkManager-аналог `rustme/IllIlIIIiI` → метод `iIlIlllilI()Ljava/net/SocketAddress;` (channelActive пишет адрес) — источник «куда реально подключились».
- Игра-пингер серверов = `rustme/ilIIiIliiI` (строки "servers/", "/icon", Server Pinger) — клиент УМЕЕТ пинговать список серверов, но в нашей конфигурации (лаунчер, --server) не используется.
- Кириллических строк «Песочница/duo» в 9977 классах НЕТ (скан scan_db + named-классы; кириллица только в админ-дашборде/лотке/glb-логах). Если все серверы коннектятся через ОДИН адрес slctl.rustme.ru — по адресу не различить, источник имени ловить живым тестом (гипотеза: скорборд rust:sc:* / таб).
- GameSettings-поле ServerData НЕ поле-тип (Object) — полей типа lIIlIIliiI в gs нет, только iiIIllil:Object + геттер IiIiIilliI/сеттер iiIliilliI.

**ГАТЧИ:**
- НЕ ПУТАТЬ `gs.IiIIllil` (Object = настройки-холдер llIIiIiIiI, ZNANIA 12.3.10) и `gs.iiIIllil` (Object = ServerData) — различие ТОЛЬКО регистром первой буквы. Тот же класс ловушек, что lIliIiiIiI vs liIlIliIiI.
- Байтовый скан `liIiIiliII` по дампу дал 5 файлов, но в 4 из них это МЕТОД GL-обёртки `IiilIiiIiI.liIiIiliII(illIIiiIiI,int,FloatBuffer)` — коллизия имени поля и метода. Байтовый скан обфусцированных идентификаторов ловит ложные попадания — верифицировать javap'ом.

**ЛУЧШИЙ КАНДИДАТ НА ИМЯ — mapId (дизасм-проверено 09-08, третья волна):**
- Сервер при входе шлёт канал `rust:misc:itmap` → named-пакет `ru.meproject.rustme.vanilla.network.misc.InitMapPacketData` (поля mapId:String, mapSize, offshoreLength, xStart, zStart).
- Хендлер `rustme/llIllIiliI.liilIIlIil(InitMapPacketData)` (map-маркеры/мапдата) копирует пакет в поле-статик `rustme/llIllIiliI.IilIiiIIl:Lrustme/lIlIIlIliI;` (MapImageInput; синхрон `getstatic` — гонок нет, класс llIllIiliI ссылается 11 клиентов). У lIlIIlIliI mapId = поле `llillIlll:Ljava/lang/String;`, геттер `liiIiiiIIl()String` (+конструктор-копия IIiIiiiIIl(lIlIIlIliI,String,...)).
- mapId качает карту с CDN: `IIlIIlIliI` ctor строит URL https://cdn.rustme.net/maps/<...>/ + llIllIiliI пишет «generated/map-...png»; вариант fancy/fast выбирается флагом isFancy → поле illIlIlll.
- ЧТО ИМЕННО в mapId (человекочитаемое «duo-1»/«sandbox» или uuid) — статически НЕизвестно, значение шлёт сервер → решает один живой тест (лог из агента).
- Дополнительно найден singleton CLI-адреса: `rustme/liiiiiIliI.IilllIlIl` (public static, класс IiiiiiIliI) — геттеры `iIiIiIIlil()String` = mainServerIp, `IIiIiIIlil()I` = порт; заполняется из --server/--port в run-блоке GameSettings @512-535 (сначала llliiIIlil(String)/iiiIiIIlil(int), потом лог «Game server address found: {}»). Дублирует реальный адрес коннекта (IllIlIIIiI.iIlIlllilI()).

**Ложные цепи (не повторять):** поле Minecraft lIliIiiIiI `IiIIllll:iiIIiIliiI` = map-иконки (textures/map/map_icons.png); дашборд `IiilIliliI` (лейбл rustme.menu.dashboard.server, ctor(List,String,int) → поле IIIIilIIl) — АДМИНСКИЙ дашборд (inspections/ban_perm/report/cupboard_menu), его serverName приходит по сети и в обычной игре не живёт.

Связано: [[rustme-next-features]], [[rustme-msdf-font]] (watermark), [[rustme-network-c2s]].

**ЖИВОЙ ТЕСТ 09-08 (проба-лог реализована, отработала):**
- РЕЗУЛЬТАТ: mapId=rm202601b (технический wipe-id: rm+YYYYMM+буква; в map-images профиля prod-a лежат rm202511b/c, rm202512d, rm202601a/b), serverData=null (при --server коннектор-путь с ServerData НЕ вызывается вовсе), addr=? (первая проба взяла поле getField'ом — не public; v2 ищет getDeclaredField по иерархии lIlilIIl:Z на liIililiiI, тип Object).
- Решение: вшитая мапа mapId→имя в Watermark (MAP_NAMES, HashMap статик-блок, «rm202601b»→«Песочница» проставлено юзером 09-08). Fallback: mapId есть, но не в мапе → рисуем сырой id; mapId ещё не пришёл (меню) → часы. Добавление серверов = дописать строку в MAP_NAMES и пересобрать.
- Watermark v2 собрана 20:43 (40 классов; PacketFly/PacketHook юзер сознательно исключил из src — «вручную списки делаем», бэкап в jni/agent/backup_packetfly). Тест отрисовки Песочницы — за юзером.
- Ложная гипотеза закрыта: human-readable имя НЕ приходит ни по одному каналу (проверены wipe:state→WipeStatePacketData (только wipeDate+classic), wipe.info UI, регион-детект iiiIllIliI (dev-ники Bpex/Lisenochek/rkkm_/steplerxd/karaseeq/torifishy/RustME/helicopteris/IcePeach/desktopbro — разработчики, не серверы), /hub — серверная команда поверх одного сокета, адрес один на все серверы).
- **ПОПУТНО ПОЧИНЕН «фиолетовый экран» в лобби (09-08 вечер, отдельная жалоба юзера):** наш HUD оставлял ФИЗИЧЕСКИ привязанной MSDF-программу (CustomFont.restore был условный `if prevProgram!=0` — в лобби до нас fixed-pipeline, т.е. prev=0; drawRoundedRectShader наоборот всегда сбрасывал в 0) → их UI лобби (рендерится сразу после overlay в том же кадре, доверяет кэшу GL-состояния) шёл сквозь наш шейдер-маску по uniform quad → иконки невидимы + фиолетовая заливка (акцент #906BFF). Фикс: БЕЗУСЛОВНЫЙ `glUseProgram(prevProgram)` в CustomFont.drawString/drawGradientString и RenderUtil.drawRoundedRectShader (prevProgram читается до sh.start()) — детали в [[rustme-msdf-font]]. Итоговая DLL 21:10, 41 класс (PacketFly/Hook остаются вне payload). Тест за юзером: «Песочница» в watermark, лобби чистое, обход серверов с I.

**ИСТОЧНИК НАЙДЕН — ТАБ-ЗАГОЛОВОК (09-08, дизасм + подсказка юзера «имя в табе, RustMe - имя»):**
- Мапа mapId→имя МЕРТВА: живой обход 24 серверов (юзер, KEY-I) доказал — mapId = id карты/вайпа (rm+YYMM+вариант, всего 5 = папки map-images), ОДИН и тот же на разных серверах (rm202601b: песочница-1/соло-2/дуо-1/дуо-4/сквад-1/анлим-4/анлим-5; stale-объяснения не покрывают). MAP_NAMES из Watermark удалён.
- Настоящий источник: S47-аналог **rustme/iIllilIIiI** (хедер iiIiIliIlI(), футер IIIiIliIlI(), компоненты = rustme/ilIIlilIiI, plain-text = lllIlliIIl()String). Обработчик netHandler.**lIiiillliI**(iIllilIIiI) кладёт: хедер → GuiIngame.liIiiiIliI() (Tab GUI rustme/liIlliliiI) поле **iIIiIIil** (acc public), футер → iIIIliIliI() → поле liIiIIil. Пустой текст = null. Имя хранится в рантайме постоянно → читается каждый кадр БЕЗ открытия таба.
- В Watermark: tabHeaderText() читает через GameContext.ingameField/Owner (наш подменённый GuiIngame) → iIilliil → iIIiIIil → lllIlliIIl(); serverName() парсит 'RustMe - "имя"' → имя (первый '-', кавычки/пробелы срезаются). Приоритет leftLabel(): имя таба → сырой mapId → часы. KEY-I теперь логирует tabHeader=...
- Ложная цепь: GuiIngame.iIIiiiIliI(String) с шаблоном lilillIiII.lIiIliiiII = 'record.nowPlaying' (акшн-бар), НЕ таб. rustme/lIIIIIliiI (2 чат-поля, super lIlliIliiI) = крафт-пакет sign/board/stick, не S47.
- Watermark с именем сервера собрана 22:13 (50 классов, юзер параллельно добавил drag-фичу posX/posY/getRect в Watermark — сохранена). Тест отрисовки за юзером.
- Карта полей ctor netHandler iliilIliiI (для addr-проб): arg1 gs → `iIIllIIl:Object`, arg2 GuiScreen → `llillIIl:Object`, **arg3 NetworkManager → `illlillII:Object`** (реальный адрес = IllIlIIIiI.iIlIlllilI() от этого объекта), arg4 GameProfile. Поле netHandler у игрока = `lIlilIIl:Object` на liIililiiI (private final, читать getDeclaredField-проходом по иерархии — getField не берёт).

**ЖИВОЙ ОБХОД 24 СЕРВЕРОВ (данные юзера KEY-I, 09-08 вечер):**
- Формат строки: `KEY-I: mapId=... | addr=slctl.rustme.ru/45.157.163.205:25565 | serverData(name=null, ip=null) | nick=... | world=true` — addr ОДИН на все серверы (per-netty, ник перезаписывается между сессиями ererer34331/Rvidiceren), serverData всегда null. mapId-конфликты: rm202601b на песочница-1/соло-2/дуо-1/дуо-4/сквад-1/анлим-4(5); rm202512d на песочница-2/соло-1/дуо-3/анлим-3(6) → mapId = карта/вайп, вшитая мапа МЕРТВА, MAP_NAMES удалён.
- Клавиша I осталась в коде (edge-detect, без лимитов) как сервер-пробник; 22:21 добавлен второй лог `TAB-ROWS[0..2] of N: ник1 | ник2 | ник3` — первые 3 строки таба ровно тем же путём, что рисует таб: netHandler.ilIIlIlliI() → снапшоты iiIilIliiI → displayName `IIlIillliI()Lrustme/ilIIlilIiI;` → текст `lllIlliIIl()` (тот же геттер, что зовёт отрисовка строки таба ilIIliIliI).
- ПОДТВЕРЖДЕНО юзером: «фиолетового экрана больше нет» после prevProgram-фикса.
- Watermark: имя таба → mapId → часы; таб-хедер читается каждый кадр через наш подменённый GuiIngame (GameContext.ingameField/Owner → iIilliil → iIIiIIil → lllIlliIIl()). Тест отрисовки имён + TAB-ROWS за юзером (если формат хедера не «RustMe - имя» — поправить serverName() по логу). DLL 22:21, 50 классов, invoke нет.

**РЕАЛЬНЫЙ ФОРМАТ ХЕДЕРА (живой дамп 09-08, таб многострочный с кодами):**
'§r§cRust§fMe §7» §aПесочница-1
§r
§r§7Онлайн » §a90
§r§r' — строка 1 = режим (RustMe » Песочница-1), строка 3 = Онлайн » N. serverName(): stripColorCodes (пары §x) → первая непустая строка → текст после '»' (U+00BB). Парсер по '-' был неверен (даёт 'Онлайн » 90'). TAB-ROWS = центральный список игроков (ники), не режим — оставлен как диагностика. DLL 22:25. ЮЗЕР ПОДТВЕРДИЛ: работает.

**ЧИСТКА ДИАГНОСТИКИ (09-08 22:38, юзер подтвердил работу имени в watermark):**
- Удалено: KEY-I дамп, TAB-ROWS, probe[1..6]-логи и вся обвязка (netty-addr, ServerData, nick, ping-компаратор, findNetHandler/readField/resolveSrv) из Watermark; NoRecoil burn-логи (lastBurnLog/burnedCount/LOG_PERIOD_MS) — спам на каждый выстрел; Tracers diag-окно 20с (diagUntil/lastDiag/stat*) и «enabled (diag 20s)».
- Оставлены: стартовые one-time логи (registered/init OK), тоггл-фидбек (toggled by X), AutoSprint writing pressed=true (one-time), Svo swap-логи (по событию), GuiScale/GL-ошибки. Watermark: leftLabel() = имя таба → mapId (fallback, lенивая resolveMapId) → часы.
- Клавиша I теперь СВОБОДНА (дамп удалён).

**ФИКС ЦЕНТРИРОВАНИЯ (09-08 22:48):** причина сдвига — центрировалась ВСЯ группа (лейбл+остров+бары), длинное имя отталкивало остров вправо и само висело далеко (старый CLOCK_GAP=12). Новая схема: остров+бары (coreGroupW) СТРОГО по центру экрана, лейбл пристаёт слева с LABEL_GAP=4 (как часы раньше); posX драга = левый край лейбла, getX/getRect согласованы (render строит islandX = posX + leftW + LABEL_GAP при драге). DLL 22:48.
