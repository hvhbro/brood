---
name: rustme-config-system
description: Конфиг-система РЕАЛИЗОВАНА (09-11, DLL 12:01 89 кл — ждёт теста): вкладка CONFIG за нижним чипом-шестерёнкой рельсы меню, кнопки Save/Load/Delete/Reload/Open dir, файлы %LOCALAPPDATA%\RustMe\*.rm шифрованы (XOR-xorshift32 + magic RMCF + CRC32), load в 2 прохода; гатчи и формат внутри
metadata:
  node_type: memory
  type: project
---

**Конфиг-система РЕАЛИЗОВАНА 09-11 (DLL 12:01, 89 классов, java/lang/invoke нет — ждёт инжекта/теста).** Запрос юзера: «полная конфиг система во вкладке settings, кнопки reload save load delete open dir, файлы в C:\Users\Admin\AppData\Local\RustMe, папку создавать если нет, файл шифровался но загружался без проблем».

**utils/etc/ConfigManager.java (весь функционал):**
- Папка: `dir()` = `System.getenv("LOCALAPPDATA")\RustMe` (fallback литерал C:\Users\Admin\AppData\Local\RustMe — на этой машине то же); `ensureDir()` зовётся при КАЖДОЙ операции + при старте агента в RustClient (после создания модулей).
- Файлы `<имя>.rm`; list() = сортированный список без расширения (String.CASE_INSENSITIVE_ORDER).
- sanitize: ASCII буквы/цифры/пробел/-_., ≤24 симв., хвостовые ./пробел/_ срезаются, Windows-резервные имена (CON/PRN/AUX/NUL/COM1-4/LPT1-3) → префикс cfg_, пустое → "default".
- **ШИФРОВАНИЕ**: весь файл XOR-ится кейстримом xorshift32 с фиксированным сидом 0x524D4331 (функция самообратима — crypt() = decrypt(), отдельный decrypt не нужен). После дешифровки: магия "RMCF" + версия (byte 1) + CRC32 payload (8 hex-символов, String.format("%08x")) + payload. Битый/чужой файл → IllegalStateException("crc mismatch"/"bad magic"/"bad version") — меню показывает ошибку красным, тишины нет.
- Payload (построчно): `M;модуль;state 0|1;bindKey` / `F;модуль;настройка;float` (покрывает Bool/Mode — они наследники FloatSetting) / `U;модуль;multi;0101` (по символу на опцию) / `C;модуль;цвет;argb int` (может быть отрицательным — парсить Long.parseLong → cast int). Модуль "Menu" исключён из save/load (иначе state ON запишется, пока меню открыто).
- **Load в 2 прохода**: сначала все F/U/C (значения), потом M (bindKey + setState) — чтобы onEnable видел уже загруженные значения. setState из GUI-потока = тот же путь, что клик по ряду модуля — безопасно. Неизвестные модули/настройки скипаются молча (сопоставление по имени). Float-значения клампятся в [min,max]. Возврат load() = число применённых модулей (для статус-строки).
- openDir: `new ProcessBuilder({"explorer.exe", path}).start()`, fallback Runtime.exec.

**UI в CheatMenuScreen:**
- Нижний чип-«шестерёнка» рельсы (setChipY) = вкладка CONFIG: клик → enterConfigMode, повторный клик → home. Выход также: клик по чипу категории / полю поиска / RMB по модулю. В drawRail чип подсвечен ACCENT при configMode.
- Панель: заголовок "CONFIG" градиентом + статус-строка (результат операции зелёный 0x4DFF6E / красный 0xFF5040, 4с; иначе "N configs in folder"), поле имени (стиль поля поиска; ASCII ≤24, backspace, мигающий карет), кнопки Save/Load/Delete (ряд 1, w=(sw−8)/3) и Reload/Open dir (ряд 2, w=(sw−4)/2, h=13, текст 6px по центру), ниже список конфигов (ряды h13 шаг 15; выделенный = ACCENT-полоска слева; клик = select + имя в поле). Reload = перечитать папку; Save/Load/Delete берут имя из поля, при пустом поле — из выделенной строки.
- Скролл списка переиспользует scrollTarget/scrollCur (свободны при selected==null), wheel-условие расширено на configMode; ease тот же 1−exp(−dt/90), scissor по зоне списка.

**ГАТЧИ (важно):**
1. `enterConfigMode()` обязан обнулять `selectedAtLayout` — иначе при возврате в модуль drawPanel запишет конфиг-скролл в scrollStore последнего модуля (порча памяти скролла настроек).
2. Клик-блок CONFIG обрабатывает ТОЛЬКО зону панели ниже topH (`wy > topH && wx >= railW + modW`) — иначе умирает драг окна за верхнюю полосу.
3. `new String(byte[],off,len,"UTF-8")` / `getBytes("UTF-8")` кидают UnsupportedEncodingException — методы обязаны объявлять `throws Exception` (первая сборка упала именно на этом).
4. Геометрия панели — единый источник истины (статические cfgFieldY/cfgBtnY/cfgBtn2Y/cfgListY от headerY()); и рендер, и хит-тест считают от них — расхождение = мёртвые кнопки.

**Верификация (оффлайн JDK8, до инжекта):** scratch-тест (tools/cfg_test, удалён после): save → изменить все значения → load → сверка (все 5 типов настроек + state + bindKey восстановились); сырой файл на диске не содержит ни "RMCF", ни plaintext-имён; порча байта → crc mismatch. PASS. Компиляция против jni/agent/build (реальные Module/Modules), класс Module грузится без GameContext (tickBind не звать).

Связано: [[rustme-menu-elements]], [[rustme-next-features]], [[rustme-jni-dll]], [[rustme-no-file-versioning]].
