# RUSTME — ПОЛНАЯ ПАМЯТЬ ПРОЕКТА (прочитай это ПЕРВЫМ)

> Этот файл — единый источник правды по проекту rustme JNI.
> Он написан для нового чата ZCode, чтобы не терять контекст.
> Дата последнего обновления: 2026-09-07 (раздел 14 — ночной анализ 9977 классов).

---

## 1. ЧТО ЭТО ЗА ПРОЕКТ

RustMe — защищённая Minecraft 1.12.2 сборка (форк JVM с протектором Astraea).
Наш проект: JNI-инструменты для инъекции в запущенную игру через jvm.dll.

**Структура проекта:**
```
C:\Users\Admin\Desktop\rustme\
├── rustme_26116_1788379393.dmp    # минидамп СВЕЖЕГО билда игры (2026-09-02)
├── decryptor.py                    # дешифровщик rustme-классов (работает!)
├── dump\                           # распакованные дампы классов игры
│   ├── minecraft\rustme\*.class    # 9977 обфусцированных игровых классов
│   ├── classes\                    # JDK/библиотеки из(classes.jar)
│   └── classes\minecraft_FULL_DEOBF.mapping.csv  # mapping (MD5→clean)
├── tools\                          # Python-анализ (см. раздел 5)
├── jni\
│   ├── dll\                        # C++ JNI DLL (главный проект)
│   │   ├── src\dllmain.cpp         # attach → loader-цепочка → DR0+VEH → DefineClass → install()
│   │   ├── src\agent_payload.h     # ГЕНЕРИРУЕТСЯ tools/pack_agent.py (классы агента, protected-формат)
│   │   ├── src\offsets.h           # 4 RVA в jvm.dll (обновляются при апдейте игры)
│   │   ├── src\jvm\jvm_offsets.h   # КАРТА СЛОТОВ env-таблицы (109 функций)
│   │   ├── src\jni_types.h         # FindClassFn и др. типы
│   │   ├── src\logger.cpp/.h       # Логгер C:\Logs\jni_rva_check.log
│   │   ├── src\memory_utils.cpp/.h # readable_ptr и пр.
│   │   └── build_dll.bat           # СБОРКА ВСЕГО ОДНОЙ КОМАНДОЙ (pack_agent.py → cl → DLL)
│   ├── agent\src\noslow\           # JAVA-АГЕНТ: NoSlowAgent, event/, module/, util/ (см. 10.7, 11)
│   ├── injector\                   # Инжектор DLL
│   └── build\dll\jni_rva_check.dll # Собранная DLL
└── D:\1proekts\rustme\             # СТАРЫЕ дампы (ground truth для калибровки)
```

**Инжект и проверка (пошагово):**
```
# 1. ЗАПУСК ИГРЫ (если не запущена через лаунчер):
cd "C:\Users\Admin\AppData\Roaming\rustme-launcher\profiles\prod-a"
"C:\Users\Admin\AppData\Roaming\rustme-launcher\java\prod-a\bin\rustme.exe" ^
  -cp "RMLLoader.jar;classes.jar;minecraft.jar;libraries/*" ^
  -XX:+DisableAttachMechanism -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC ^
  -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 ^
  -XX:G1HeapRegionSize=32M -XX:+DisableExplicitGC -XX:+ParallelRefProcEnabled ^
  -Dsun.java2d.dpiaware=true -Xverify:none -Ddynamic.name=true -Ddynamic.dump=true ^
  --add-opens=java.base/ru.rustme=ALL-UNNAMED ^
  --add-exports=java.xml.crypto/com.sun.org.slf4j.internal=ALL-UNNAMED ^
  -Drustme.api.url=https://launcher.rustme.net ^
  "-Drustme.api.urls=https://launcher.rustme.net;https://launcher.rustme.ru" ^
  -Drustme.profile.name=prod-a ^
  -Djava.library.path=C:\Users\Admin\AppData\Roaming\rustme-launcher\profiles\prod-a\bin\windows-x64 ^
  -Dorg.lwjgl.librarypath=C:\Users\Admin\AppData\Roaming\rustme-launcher\profiles\prod-a\bin\windows-x64 ^
  ru.meproject.Main --server slctl.rustme.ru --port 25565 --username vilka44324 ^
  --accessToken b53bf9b8b915febf69bd5f7ef67cc194814ebf0c45f58bfcf2eaa24aae4c4cf0 ^
  --uuid e55ab6ae-203f-3f90-a109-d804eb55c4af --gameDir ..\prod-a --assetsDir assets ^
  --version 1.12.2 --assetIndex 1.12.2
# Подождать 20-25 секунд пока игра загрузится до главного меню

# 2. ЗАКРЫТИЕ ИГРЫ (если нужно перезапустить):
powershell -Command "Get-Process rustme -ErrorAction SilentlyContinue | Stop-Process -Force"
sleep 3

# 3. ИНЖЕКТ DLL (после запуска игры, ждать 20-25 сек):
del "C:\Logs\jni_rva_check.log" 2>nul
"C:\Users\Admin\Desktop\rustme\jni\build\injector\inject.exe" --once --no-pause ^
  "C:\Users\Admin\Desktop\rustme\jni\build\dll\jni_rva_check.dll" ^
  "c:\users\admin\appdata\roaming\rustme-launcher\java\prod-a\bin\rustme.exe"

# 4. ЧТЕНИЕ ЛОГА:
type "C:\Logs\jni_rva_check.log"

# 5. СБОРКА DLL (после изменения кода):
cd C:\Users\Admin\Desktop\rustme\jni\dll
build_dll.bat
```

**КРИТИЧНО:**
- Инжект только ОДИН раз на один запуск игры! Повторный виснет на AttachCurrentThread
- Перед повторным инжектом — ЗАКРЫТЬ ИГРУ и ЗАПУСТИТЬ ЗАНОВО
- Путь к rustme.exe в НИЖНЕМ регистре
- Путь к DLL всегда ЯвНО
- Если игра запущена через лаунчер — путь тот же (проверить через Get-Process)
- Сборка build_dll.bat: запускать из jni\dll\ или указать полный путь

---

## 2. ЧЕТЫРЕ RVA В jvm.dll (offsets.h) — РАБОЧИЕ

Дамп rustme_26116 (свежий билд, base 0x7FFBA2720000 в дампе):
- kJavaVmRva               = 0xE6D248   (main_vm, JavaVM*)
- kInvokeTableRva          = 0xC5DCD0   (JavaVM vtable, 3 NULL + 5 fn)
- kNativeTableRva          = 0xBD2898   (pristine JNIEnv template, .rdata)
- kObservedLiveEnvTableRva = 0xE6CAF0   (живая JNIEnv таблица, .data)

Старый билд (rustme_19600, ground truth): 0xDEBF68 / 0xBEBB10 / 0xB628F8 / 0xDEC0F0.

Методика обновления (если игра обновится) — `tools/calibrate.py`, `tools/find_live3.py`:
- invoke: паттерн «3 NULL + 5 fn» в .rdata
- main_vm: qword в .data, указывающий на invoke
- native(.rdata): layout [0]=end-of-text, [1]==[2], [3]=NULL, [4]=0xB914
- live(.data): JavaThread-сигнатура: qword[site]=таблица, qword[site-0x40..-0x28] = 4 восходящих ptr
- Калибровка: тот же код должен воспроизвести старые 4 офсета на старом дампе

---

## 3. КАРТА СЛОТОВ env-ТАБЛИЦЫ (jvm_offsets.h) — РАНТАЙМ-ВЕРИФИЦИРОВАНО

**КРИТИЧНО:** enum `JniOffset` хранит БАЙТОВЫЕ офсеты. Индексация `envVTable[...]` требует `/8`.
В этой форке порядок слотов НЕ стандартный (не jni.h) и МЕНЯЕТСЯ между билдами игры!

Runtime-подтверждённые (пробы в dllmain, «[probe]» в логе):
| Функция | Байт | Слот |
|---|---|---|
| FindClass | 1840 | 230 |
| GetObjectClass | 1192 | 149 |
| NewStringUTF | 208 | 26 |
| GetStringUTFChars | 1168 | 146 |
| GetJavaVM | 1096 | 137 |
| ExceptionOccurred | 1728 | 216 |
| ExceptionClear | 368 | 46 |
| PushLocalFrame | 984 | 123 |
| PopLocalFrame | 888 | 111 |
| GetMethodID | 1864 | 233 |
| GetStaticMethodID | 1080 | 135 |
| CallIntMethodA | 416 | 52 |
| **CallObjectMethodA** | **1560** | **195** |
| **CallBooleanMethodA** | **1600** | **200** |

Всего 109 функций в enum. Методика восстановления после апдейта игры:
1. Trampoline-декод хуков (геттеры Get*Field)
2. Позиционное выравнивание emission .text между билдами (difflib, mean 0.86)
3. Varargs Call*A семья: порядок типов emission: Bool,Byte,Char,Short,Object,Int,Long,Float,Double
4. Span-интерполяция между соседними якорями
5. Если сомнение — runtime-скан под SEH (только целевые кандидаты, НЕ брутфорс всех 234)

**Исправленные ошибки (были в интерполяции):**
- CallObjectMethodA: было 528/66, стало 1560/195 (ключевая ошибка)
- CallBooleanMethodA: было 432/54, стало 1600/200 (54 давал false-мусор)
- GetArrayLength (584/73): ЕЩЁ НЕ верифицирован — подозрительно (SEH на валидном byte[])
- GetFieldID / GetStaticFieldID / SetObjectField / SetStaticObjectField / RegisterNatives: НЕ восстановлены

---

## 4. ЧТО РАБОТАЕТ СЕЙЧАС (runtime, 2026-09-04)

Полный лог из игры:
1. Инжект → AttachCurrentThread через invoke[4] → JNIEnv получен
2. invoke vtable match YES, live env vtable match YES
3. FindClass@230("java/lang/String"/"Throwable") → ОК
4. 9 базовых проб JNI → OK
5. **loader-цепочка → RML-загрузчик найден, loadClass работает!**

**Loader-цепочка (рабочая, в dllmain):**
```
FindClass("java/lang/Thread")
→ GetStaticMethodID(Thread, "currentThread", "()Ljava/lang/Thread;")
→ CallStaticObjectMethodA@167(слот 1336/8=167) → threadObj
→ GetMethodID(Thread, "getContextClassLoader", "()Ljava/lang/ClassLoader;")
→ (наш native-поток: ctxCL = null!)
→ Thread.getAllStackTraces() → keySet → iterator
→ перебор потоков: hasNext через слот 200, next через слот 195
→ первый non-null getContextClassLoader = RML-loader
→ GetMethodID(loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;")
→ loadClass("rustme.liIlIliIiI") — ИЛИ ДРУГОЙ КЛАСС
```

**Важные факты про loader:**
- FindClass("rustme/...") из native → null (system loader не знает minecraft.jar)
- Через ctx ClassLoader (RML) — все игровые классы находятся
- **Имена классов — ТОЛЬКО В ТОЧКАХ для loadClass: `rustme.liIlIliIiI`, НЕ `rustme/liIlIliIiI`** 
  (getHashedName в RML = MD5(dot-имени); slash-формат даёт CNFE, dot — находит класс)
- Runtime подтверждено: EntityPlayer (`rustme.liIlIliIiI`) НАХОДИТСЯ при игроке В МИРЕ!
  В главном меню CNFE — класс ещё не загружен (это нормально).
- Игровые классы в jar под MD5-хешами: minecraft.jar = 11052 entry, classes.jar = 19109
- Mapping подтверждён: MD5(dotted_clean_name) == jar_entry для всех 11052 классов
- `ru.rustme.settings.Settings` (мод) — загружен ВСЕГДА (даже в меню)
- Слоты-двойники Boolean: 200/117/57/66/52 — все дают корректный bool (al-конверсия);
  слот 54 из старой карты даёт false-мусор

---

## 5. КАРТА КЛАССОВ ИГРЫ (обфускация → назначение)

Полный справочник: `tools/class_report/report.md` + `classes.csv` (9977 классов)

Ключевые якоря (многие важные для noslow):
| Обфусцированное | Ванильное | Примечания |
|---|---|---|
| rustme/IIlIIliIiI | Entity (root) | 229 методов; lliililiI:Z = isSneaking; iIIlIiliI/IIiIIiliI/IiiIIiliI:D = motion x/y/z |
| rustme/liIlIliIiI | EntityPlayer | 157 методов; ctor(World,GameProfile); travel=IililiiilI(FFF) |
| rustme/IIiIIiIIiI | EntityPlayerSP wrapper (mod) | Inventory=iIliiIiII; liIIIiiilI()=onLivingUpdate-подобный |
| rustme/iiIililiiI | AbstractClientPlayer | abstract, extends IIiIIiIIiI |
| rustme/liIililiiI | EntityPlayerSP (mod-patched) | travel override iIilliiilI; кидает PlayerSneakEvent |
| rustme/liIlliiliI | PlayerSneakEvent (mod) | player=IIiIIiIIiI, sneaking=Z; getSneaking/setSneaking |
| rustme/iIIlliiliI | PlayerSprintEvent (mod) | аналогично |
| rustme/lIiililliI | ModPlayer facade (mod) | getSneaking/setSneaking/elytraFlying |
| rustme/lillilIIiI | PlayerCapabilities | walkSpeed=iiiiliilI:F, flySpeed=iIiiliilI:F, isFlying=llllIiilI:Z |
| rustme/IIlllIlIiI | World | 229 методов; rayTraceBlocks |
| rustme/iilliIliiI | GameSettings | 91 поле, 120 методов |
| rustme/iiIIIiIIiI | InventoryPlayer | slots; getCurrentItem |
| rustme/IlilIiIIiI | SharedMonsterAttributes | generic.movementSpeed |
| rustme/liiililIiI | MathHelper | cos/sin/sqrt/clamp |
| rustme/lIliIiiIiI | Minecraft (main) | ctor(GameSettings,...); 55 методов |
| rustme/lllliIiliI | ModelPlayer render | transformIfSneaking |

**Механика sneak slowdown (1.12.2 + rustme-модификации):**
1. В `rustme/IIiIIiIIiI.liIIIiiilI()` (клиентский onLivingUpdate-подобный):
   - if isSneaking → IIIIiIliI:F += 0.3 * IliIiIiII  ← ЭТО И ЕСТЬ ЗАМЕДЛЕНИЕ
   - IIIIiIliI — friction speed (по умолчанию 0.02)
   - затем вызывается liiiliilII(F) = setSpeed (поле liiliIliI:F у EntityPlayer)
2. В EntityPlayer.travel (IililiiilI(FFF)), наземная ветка:
   - if sneaking → f7 = getSpeed()*f6; else f7 = speedField(0.02)
   - f6 = 0.16277136F / (slipperiness^3)
3. Точка вмешательства для noslow: перехват liIIIiiilI() или компенсация
   IIIIiIliI:F после setSpeed, или isSneaking=false только для движения.

---

## 6. НОЧНАЯ СЕССИЯ 2026-09-04: ИТОГИ СКАНОВ FIELD-ФУНКЦИЙ

### 6.1 Что верифицировано / опровергнуто runtime-сканами (20 инжектов)

**Убитые слоты (вызов = тихая смерть процесса или краш JVM):**
| Слот | Байты | Что там | Симптом |
|---|---|---|---|
| 7 | 56 | GetEnv-как-JNIEnv | тихая смерть (без hs_err) |
| 50 | 400 | killer | тихая смерть |
| 55 | 440 | killer | тихая смерть |
| 69 | 552 | **DefineClass** | тихая смерть при мусорных аргументах |
| 97 | 776 | killer | тихая смерть |
| 106-114 | 848-912 | write-семейство Get/Set*Field? | VMThread краш: читает offset 0x65756c6176 ('value' — наш fid трактуется как поле) |
| 213 | 1704 | FatalError | гасит процесс |
| 229 | 1832 | killer | тихая смерть |

**«Объектные твины» (функции, игнорирующие семантику fieldID):**
- Слоты 110, 111, 115: (env, cls, name, sig) → последовательные heap-хендлы.
  Матрица «fid × reader» показала: 110 и 115 взаимно читают друг друга,
  111 возвращает константу. Это НЕ GetFieldID.
- Слот 177: NewString-двойник (каждый вызов — новая jstring).
- Слот 12 (NewGlobalRef), 26 (NewStringUTF) — ложные кандидаты из-за
  heap-handle критерия.

**ГЛАВНЫЙ ВЫВОД: GetFieldID/GetStaticFieldID/GetObjectField/GetBooleanField/
GetFloatField/SetFloatField НЕ НАЙДЕНЫ слепым сканом и, вероятно, скрыты
протектором (таблица может быть фиктивной/пересобранной в рантайме).**

**Блокировки протектора (runtime-подтверждено):**
- `GetStaticMethodID` (135) на ИГРОВЫХ классах → mid=0, pending=0 (тихо
  не работает). На JDK-классах (Thread) — работает.
- `GetMethodID` (233) на java/lang/Class ("getDeclaredField", "getName")
  → pending (NoSuchMethod). Reflection на Class заблокирован.
- `DefineClass` (69): dot-имя и slash-имя → 0 + pending; класс исключения
  прочитать нельзя (getName на Class заблокирован).
- `FromReflectedField` (226): проверить не удалось — нет способа получить
  java/lang/reflect/Field (getDeclaredField блокирован).
- `loadClass` через RML loader — РАБОТАЕТ (все игровые классы при игроке в мире).
- Виртуальные методы на игровых классах (`GetMethodID`+`Call*MethodA`) — РАБОТАЮТ.

### 6.2 Механика sneak-slowdown (реверс байткода, подтверждён)

Ключевые классы/поля (в терминах дампа):
- `rustme/lIliIiiIiI` — Minecraft main (НЕ singleton: instance живёт в
  `GameSettings.lIliiiIl:Object`, пишется в `GameSettings.IiiIiilliI()V`:
  `lIliiiIl = new lIliIiiIiI(this, ...)`.
- `rustme/iilliIliiI` — GameSettings; статический синглтон-метод `IllilIIiIl()`
  — есть в дампе, но в рантайме GetStaticMethodID его не находит (блокировка).
- `rustme/IIlllIlIiI` — World; `IlIiiiiil:Ljava/util/List` — playerEntities
  (хранит wrapper'ы `rustme/IIiIIiIIiI`).
- `rustme/IIiIIiIIiI` — EntityPlayerSP wrapper; `IliIiIiII:F` — базовая
  скорость (0.02); `lliIIiIIiI` — mod input holder.
- `rustme/liIlIliIiI` — EntityPlayer; методы скорости:
  - `lillliilII()F` — читает dataWatcher[IiIiIIliI] (текущая скорость)
  - `iiIlIiilII(F)V` — clamp → dataWatcher.set (ЗАПИСЬ СКОРОСТИ)
  - `lillIiilII()F` — базовая скорость (из атрибута)
  - `liiiliilII(F)V` — liiliIliI:F = arg (setSpeed, вызывается модом из
    liIIIiiilI с атрибутом movementSpeed)
- `rustme/IIlIIliIiI` — Entity; `lliililiI:Z` — onGround; dataWatcher-флаги
  через `IiiIillIII(I)Z` (id=3 = sprinting).
- Sneak-замедление: `wrapper.liIIIiiilI()` (onLivingUpdate) при sprinting
  добавляет `+0.3*base` к `IIIIiIliI:F` (jumpMovementFactor), а наземная
  скорость (`getSpeed()` = `liiliIliI:F`) тянется из атрибута.
  Сглаженный sneak-фактор — `IiiliIliI:F` (накопитель 0..1, `X += (f8-X)*0.3`),
  копируется в `liiiIIliI:F` и масштабирует движение.

### 6.3 Нативный noslow-путь ЗАКРЫТ (не предлагать повторно)

`jni/dll/src/noslow.cpp` удалён. Нативная компенсация (кламп liiliIliI:F из
DLL через CallVoidMethodA) и env-vtable hook на слот 195 — путь закрыт решением
юзера: «нативный бекап путь через хук делать не надо, он не работает». Единственный
рабочий путь правки игрового состояния — **Java-агент через DefineClass** (разделы 10-11).

Полезный побочный факт: env-таблица живёт в .data jvm.dll (пишемая память) —
запись envVTable[195] + restore игра переживает. Но сам подход свёрнут.

✅ Работает:
- AttachCurrentThread, FindClass@230, NewStringUTF@26, GetStringUTFChars@146,
  GetObjectClass@149, GetJavaVM@137, ExceptionOccurred@216, ExceptionClear@46,
  Push/PopLocalFrame, GetMethodID@233, CallIntMethodA@52, CallObjectMethodA@195,
  CallBooleanMethodA@200, CallStaticObjectMethodA@167 (Thread.currentThread).
- Loader-цепочка + loadClass любых игровых классов (при игроке в мире).
- Индексация env-таблицы и offsets.h — валидны для билда 26116.

ВАЖНЫЕ ГАТЧИ (дополнено ночью):
- После JNI-вызова с ошибкой — чистить pending exception (ExceptionClear@46),
  иначе ВСЕ следующие JNI-вызовы падают/ведут себя странно
- **СЛЕПЫЕ СКАНЫ env-таблицы УБИВАЮТ процесс**: killers 7, 50, 55, 69, 97,
  106-114 (write-семейство, VMThread AV на 'value'), 213, 229. Сканить можно
  только по одному слоту за инжект с логом ДО вызова.
- При инжекте в той же JVM второй раз — AttachCurrentThread виснет намертво
- Пробные вызовы под SEH: ошибки → EXCEPTION 0xc0000005, но процесс живёт
  (кроме killers выше!)
- В RustMe jar-файлы классы зашиты под MD5-хешами (11052 в minecraft.jar);
  mapping в `dump/classes/minecraft_FULL_DEOBF.mapping.csv`
- Reflection на java/lang/Class заблокирован протектором (getDeclaredField,
  getName → NoSuchMethod pending). GetStaticMethodID на игровых классах тихо
  возвращает 0. Виртуальные методы и loadClass — работают.
- Инжект из главного меню: игровые классы (EntityPlayer и др.) ещё НЕ
  загружены → loadClass даёт CNFE. В DLL добавлены retry-циклы (10s до 5 мин).

---

## 7. ИНСТРУМЕНТЫ (tools/)

- `mdump.py` — парсер минидампов (ModuleList + Memory64List), без зависимостей
- `jc.py` — helper: кэш образов jvm.dll обоих билдов в tools/cache/
- `calibrate.py`, `find_live3.py`, `find_env.py`, `match_functions.py` — поиск RVA таблиц
- `build_slot_map.py`, `finalize_map3.py`, `extend_map.py` — сборка карты слотов
- `match_functions.py` / `map_by_helper.py` — (устарели, не работают)
- `class_analyzer.py` — парсер class-файлов (fix: p+=6 не p+=8 в member header)
- `build_report.py`, `report.md`, `classes.csv` — отчёт по классам игры
- `key_classes/` — javap-дизасмы ключевых классов (EntityPlayer, ModPlayer, ...)
- `brace_check.py` — проверка баланса скобок в C++ (учитывает строки/комменты)
- `hook_targets.json`, `rank_lists.json`, `final_pairs.json`, `slot_map_*.json` — промежуточные данные
- `dump_members.py` — дамп полей/методов с access-флагами (class-файл → консоль)
- `mini_dis.py` — грубый дизасемблер метода (getfield/invoke с CP-резолвом)
- `code_bytes.py` — сырые байты Code-атрибута named-метода

---

## 8. ЧТО НАПИСАТЬ В СЛЕДУЮЩЕМ ЧАТЕ

Первое сообщение (скопируй целиком, статус на 2026-09-07):

```
Проект rustme (JNI-инструменты для Minecraft 1.12.2 защищённой сборки).
Прочитай файл C:\Users\Admin\Desktop\rustme\ZNANIA.md ПОЛНОСТЬЮ — там вся
память проекта. Ключевые разделы: 13 (MSDF-шрифты + watermark rockstar +
ArrayList vanquish + грабли стаба/шимпа), 10 (DefineClass взломан), 11 (timing:
state-поля перезаписываются — писать ВХОД, не выход), 12 (клиент/модули).

СОСТОЯНИЕ: DLL jni\build\dll\jni_rva_check.dll собрана и ждёт инжекта
(build_dll.bat одной командой; инжект 1 раз на запуск игры).
Работает: MSDF-шрифты (medium в ватермарке+ArrayList, sf_semibold запас),
Watermark rockstar (часы+капсула+градиент+пинг-бары), ArrayList vanquish
слева x=2, AutoSprint(X), FullBright(J, гамма 10 — 100 даёт синий lightmap).
Пинг: цепочка найдена в rustme-классах (player.iliiililiI() -> iiIilIliiI ->
illIillliI()), в последнем логе пинг=0 — проверить после инжекта по
"Watermark: ping=NNN ms"; если 0 — см. ZNANIA 13.2 (ловушка Tab-снапшота).

ЗАДАЧА: (поставь свою, напр. ESP — ночной план в разделе 13.7)

НОЧНАЯ ЗАДАЧА (автономный анализ, БЕЗ изменения кода):
Прогони ВСЕ 9977 классов дампа (dump/classes/minecraft/rustme/, карта имён
tools/disk_name_map.json, дизасм через javap на копии с чистым именем —
parse_cp врёт на крупных классах, см. 13.4 п.8) и классифицируй:
1) КЛАССЫ ДЛЯ ESP: позиция X/Y/Z сущностей (double-поля в IIlIIliIiI/liIlIliIiI
   — верифицируй javap'ом кто posX/posY/posZ), yaw/pitch камеры, FOV из
   iilliIliiI, EntityRenderer-аналог с матрицами проекции, bounding box.
2) Пинг: держатель ЖИВЫХ lIllilIIiI (NetworkPlayerInfo) — у нас снапшот
   iiIilIliiI даёт вечные 0 (ZnanIa 13.2).
3) HUD/рендер: их watermark, бары, иконки, шейдеры (сверь с расшифрованными
   assets/minecraft/shaders/).
4) Сеть/настройки/события/модули: полная карта по назначению.
Итог — обнови ZNANIA.md новым разделом с точными именами классов/полей/методов
(каждое верифицируй javap/дизасмом, помечай «проверено»/«гипотеза»). КОД НЕ МЕНЯТЬ.

```


## 10. СЕССИЯ 2026-09-05: DEFINECLASS ВЗЛОМАН, JAVA-АГЕНТ РАБОТАЕТ (КРИТИЧНО)

### 10.1 Итог: произвольный Java-класс инъектируется в JVM из native

**Полная рабочая цепочка (в dllmain.cpp, 269 строк, начисто):**
1. AttachCurrentThread → JNIEnv
2. Перебор Thread.getAllStackTraces() → для каждого ctxCL проба
   `loadClass("rustme.liIlIliIiI")` → берём ТОЛЬКО лоадер, который видит
   игровые классы (первый ctxCL в мире — лоадер лаунчера, он НЕ видит rustme.*!)
3. DR0-breakpoint на входе валидатора (jvm.dll RVA 0x1C2D0) + VEH →
   заглушка `mov al,1; ret` → валидатор мгновенно «успешен»
4. `DefineClass(env, NULL, игровой loader, enc-блоб, len)` → jclass
5. `GetStaticMethodID(defined, "install", "()V")` → работает на нашем классе
   (блокировка static-lookup 135 НЕ действует на классы, определённые из native!)
6. `CallStaticVoidMethodA` → агентный Java-поток жив, пишет в файл-лог

**ВАЖНО: память jvm.dll не изменяется** (чексуммить нечего).

### 10.2 Почему ночные пробы «убивали» DefineClass

- Слот 69 = настоящий jni_DefineClass (wrapper RVA 0x3F5CB0). Не вырезан.
- «Тихая смерть» ночью = мусорные аргументы (buf/len) → парсер → vm_exit.
- Валидный вызов с настоящим блобом безопасен (доказано duplicate-маркером).

### 10.3 Protected-формат классов (парсер jvm.dll, регион 0x223000-0x231600)

- Главный парсер 0x222D40; версия-декодер байт-в-байт = decryptor.py (константы
  major 40039/28200/59236 найдены в jvm.dll @0x22395E-0x2239B6).
- Поток: length → XOR-дешифровка ВСЕГО блоба KEY[i%15] (таблица KEY
  `1ec8ca80...` в .rdata @0xC5CB08) → set(buf+0x100, len-0x100) —
  **256-байтовая преамбула отбрасывается** → magic CAFEBABE @stage[256:260]
  → иначе «Corrupted classfile» (строки XOR-шифрованы, дешифратор @0x6E8680:
  seed (i+1)*0x9E3779B1 ^ 0x6B0B77C, rot13ish, XOR таблица из .rdata).
- **Валидатор заголовка 0x1C2D0** (вызов после дешифровки, .orn-обфускация):
  привязан к телу класса (hdrsteal с чужим заголовком отклоняется).
  Стандартные хеши (md5/sha/hmac/murmur) не совпали — вероятно RSA-2048
  (заголовок ровно 256 байт).
- Энкодер protected-формата: `tools/encode_protected.py` — round-trip
  100/100 на реальных классах minecraft.jar (decode→encode = identity).
  Инверсия decode_protected: encval (reversed ops), INV_TAG, INV_OPMAP,
  двойной слот long/double, NameAndType/refs (a↔b своп при записи),
  XOR body с фазой (256+i)%15, header копируется as-is (файловый вид).
- `agent_bytes_enc.h` — NoSlowAgent в protected-формате (нули в преамбуле;
  валидатор обходится DR0, поэтому подпись не нужна).

### 10.4 Что НЕ работает (проверено, не повторять)

- VirtualProtect на .text jvm.dll: все варианты → gle=87 (NtProtectVirtualMemory
  хукнут протектором). Страница: PAGE_EXECUTE_READ, MEM_PRIVATE (ручной маппинг).
- JVMTI: GetEnv(JVMTI_VERSION_1_2_1) → res=-3 (отключён в форке).
- Plain CAFEBABE класс через DefineClass → «Corrupted classfile» (парсер
  принимает только protected-формат).
- DefineClass с name != реальному имени класса → «wrong name» (передавать
  name=NULL, парсер читает из байткода).
- Class.forName("rustme.*") из агента, определённого в лоадер лаунчера →
  ClassNotFoundException (см. 10.1 п.2 — правильный лоадер обязателен).

### 10.5 Структура игры (реверс классов, верифицировано дампом)

- **GameSettings (rustme.iilliIliiI)**: static `lIiiiiIl` (Object) — не
  используется напрямую; синглтон через static метод `IllilIIiIl()`
  (getstatic lIiiiiIl + checkcast). mcInstance = единственное НЕ-static
  поле gs со значением instanceof Minecraft (`lIliiiIl`, Object, но ищем
  instanceof-поиском — 62 Object-поля).
- **Minecraft (rustme.lIliIiiIiI)**: НЕ синглтон. world = поле `IIIiiiiiI`
  (тип IIlllIlIiI). Поля thePlayer НЕТ.
- **World (rustme.IIlllIlIiI)**: playerEntities = `IlIiiiiil`
  (final List<IIiIIiIIiI> — единственный List с wrapper-элементами).
- **Локальный игрок**: элемент списка instanceof `rustme.liIililiiI`
  (EntityPlayerSP; иерархия liIililiiI → iiIililiiI → IIiIIiIIiI → liIlIliIiI).
- **movementInput**: у wrapper поле `iiliiIiII` типа `rustme.liIIIiIIiI`
  (2 float + 5 bool public — эвристика ≥2 float + ≥2 bool находит).
- **Скорость**: travel (IililiiilI) наземная ветка читает поле
  `liiliIliI:F` (геттер `iIIIIiiilI()F`); liIIIiiilI (onLivingUpdate) каждый
  тик пишет его из АТРИБУТА movementSpeed: `liiiliilII(attr.iIIiIlIIII())`.
  lillliilII/lillIiilII (dataWatcher) — НЕ то поле, что читает travel.
- **isSneaking**: `iiiilIlIII()Z` (EntityLivingBase, public).
- **Гамма**: gs.IilIllil:F (единственный non-static float в GameSettings)
  пишется, но рендер её игнорирует — **гамма в форке отключена** (слайдер
  яркости у игрока тоже не влияет на свет). FullBright-тест провален.

### 10.6 Тесты эффекта (чтобы доказать запись из Java)

- FullBright (gs.IilIllil=F=10.0): пишется (cur=10.0 в логе), экран не
  меняется — гамма мертва в форке. НЕ тест.
- TitleTest (GLFW glfwSetWindowTitle через reflection): GLFW резолвится,
  но glfwGetCurrentContext()=0 с агентного потока (контекст привязан к
  главному потоку игры). Заголовок не поменялся. Требуется HWND (WinAPI)
  — не доделано (откат).
- **Вывод: исполнение Java-кода в игре ДОКАЗАНО** (лог-файл пишется агентным
  потоком, все reflection-чтения живых объектов работают: gs, mc, world,
  player, movementInput, методы скорости). Осталось найти эффект с видимой
  отдачей и реверсить его точку записи.

### 10.7 Текущее состояние кода (структура, 2026-09-05)

- `jni/dll/src/dllmain.cpp` (~294 строки): attach → правильный лоадер →
  DR0+VEH → DefineClass всех блобов из `agent_payload.h` → install().
  Пробы/эксперименты удалены.
- `jni/agent/src/` — JAVA-АГЕНТ (источник, 5 файлов):
  - `noslow/NoSlowAgent.java` — entry (install() → поток CheatMain →
    GameContext.init() с retry 60с → модули → цикл 1мс: update+TickEvent)
  - `noslow/util/GameContext.java` — синглтон-кэш: gs (через static
    IllilIIiIl), mc (instanceof-поиск в полях gs), world, player (из
    world.playerEntities, instanceof liIililiiI), movementInput + все
    reflection-поля/методы. playerClass = liIlIliIiI (EntityPlayer base).
  - `noslow/util/Log.java` — лог в C:\Logs\noslow_agent.log
  - `noslow/event/EventBus.java` — pub/sub (TickEvent, SneakStart/StopEvent)
  - `noslow/module/AutoSprint.java` — сейчас SpeedBoost (см. раздел 11)
- `jni/backup_noslow/` — бекап старого NoSlow v16 (snapshot-компенсация).
- **Сборка ОДНОЙ командой**: `cd jni/dll && build_dll.bat` — сам вызывает
  `tools/pack_agent.py` (компиляция javac JDK8 → encode_protected →
  agent_payload.h, round-trip проверка каждого блоба) → затем cl → DLL.
- `tools/pack_agent.py` — генератор agent_payload.h (10 классов включая
  внутренние/лямбды; entry: noslow/NoSlowAgent).
- Старый `tools/NoSlowAgent.java` и `agent_bytes_enc.h` более НЕ источник —
  источник только `jni/agent/src/`.

---

## 11. СЕССИЯ 2026-09-05: TIMING-ПРОБЛЕМА И SPEEDBOOST (текущий фронт)

### 11.1 ГЛАВНЫЙ ВЫВОД СЕССИИ — почему «в логе всё пишется, в игре ничего»

Агент опрашивает/пишет state-поля с периодом 1мс, а игра — в СВОИХ тиках
(50мс). Порядок внутри тика: игра ПИШЕТ поле → потом ЧИТАЕТ. Запись агента
попадает МЕЖДУ тиками → к моменту чтения игра уже перезаписала значение.
**Поэтому НЕ РАБОТАЮТ любые записи state-полей: sprint flag (IilIiIliI:Z),
скорость liiliIliI:F, setSprint(). Логи честные — запись происходит, но
умирает до использования.**

**Что РАБОТАЕТ (доказано):** физические поля, которые игра только ЧИТАЕТ,
не перезаписывая: motionY (+JumpTest — прыжок из записи motionY=1.0),
атрибуты (setBaseValue). Полный доступ к живым объектам есть (mc/gs/world/
player/movementInput через reflection), исполнение кода доказано.

### 11.2 Ограничения/пожелания юзера (НЕ НАРУШАТЬ)

- Файлы НЕ плодить версиями (v2/v3) — менять существующие.
- Нативный бекап-путь через хук — НЕ делать (не работает).
- ZNANIA.md не обновлять без спроса (кроме явной просьбы «зафиксируй»).
- Чат-тест не нужен.

---

## 9. ИЗВЕСТНЫЕ ПРОВЕРЕННЫЕ ФАКТЫ (не перепроверять)

- Java 8 (class major 52), MC 1.12.2 + OptiFine
- -XX:+DisableAttachMechanism В КЛИЕНТЕ — но JNI AttachCurrentThread работает
  (запрещает agent-based attach, не native)
- В дампе 46 классов упоминают net/minecraft/entity/player/EntityPlayer — мод
  interacting с ванилью через эти строки (аннотации Kotlin @NotNull и т.д.)
- Мод написан на Kotlin (событийная шина, PlayerSneakEvent, settings-фреймворк)
- GetVersion не верифицирован как якорь (проба 144 → SEH, якорь неверен)
- KeyBinding класс = rustme/IilIiIiIiI (key.categories.*), GameSettings = rustme/iilliIliiI
- Astraea.dll — протектор в том же каталоге bin; Astraea.log зашифрован
- DefineClass (слот 69) РАБОТАЕТ с валидными аргументами — см. раздел 10
- Протектор НЕ блокирует native-вызовы вообще (FindClass/loadClass работают),
  НО блокирует reflection-методы java/lang/Class и GetStaticMethodID на
  игровых классах (ночная сессия 2026-09-04)
- Слоты Boolean-семьи: 200/117/57/66/52 дают корректный bool (не различимы по
  al-конверсии), 54 — мусор; в карте зафиксирован 200
- GetFieldID ≠ слоты 110/111 (ночь 2026-09-04: это объектные твины);
  прежняя запись «GetFieldID = 110 или 111» ОПРОВЕРГНУТА
- Порядок emission Call*A семьи: Bool,Byte,Char,Short,Object,Int,Long,Float,Double
  (если верна и для Set*Field, то SetFloatField ≈ слот 112, НЕ проверено)

---

## 12. СЕССИЯ 2026-09-05/06: ПОЛНАЯ СТРУКТУРА КЛИЕНТА + КАСТОМНЫЙ РЕНДЕР + MODULI (текущий фронт)

### 12.1 РЕЗЮМЕ СЕССИИ

Прорывной этап: клиент полностью пере structure-ирован (expensive-стиль), кастомный
рендер РАБОТАЕТ (шейдеры + HUD + ArrayList справа сверху), добавлены модули AutoSprint
(toggle X) и FullBright (toggle J, через гамму мода — фикстится в новом чате).

### 12.2 НОВАЯ СТРУКТУРА АГЕНТА (вместо noslow/*)

`jni/agent/src/`:
- `client/RustClient.java` — ENTRY (pack_agent DEFAULT_ENTRY = client/RustClient):
  install() → поток CheatMain → GameContext.init() (retry 60с) → модули → цикл 1мс:
  GameContext.update() + EventBus.post(TickEvent) + ensureHud() лениво.
- `events/EventBus.java` — шина: TickEvent(inWorld), SneakStart/StopEvent, Event base.
- `modules/api/Module.java` — базовый класс: name, state, toggle(), setState→onEnable/
  onDisable, onTick(TickEvent); авторегистрация Modules.register(this) в конструкторе.
- `modules/api/Modules.java` — реестр List<Module> (get/set/all).
- `modules/impl/AutoSprint.java` — настоящий спринт через клавишу (см. 12.4).
- `modules/impl/FullBright.java` — через гамму мода (см. 12.6, ФИКСИТСЯ).
- `utils/etc/GameContext.java` — весь контекст: gs (singleton IllilIIiIl), mc (instanceof
  в gs-полях), world, player, settingsObj, keyBindingsCategory, sprintKey, renderHooks,
  ensureHud() (swap GuiIngame), GL-обёртки (glBindTexture/glTexSubImage2D static),
  settingsObj (Settings instance, заполняется в resolveSprintKey).
- `utils/etc/Log.java` — лог в C:\Logs\noslow_agent.log (append).
- `utils/render/CheatHud.java` — ArrayList: скруглённый градиентный фон (шейдер),
  акцентные полоски (ванильный drawRect), текст (FontRenderer мода), анимации.
- `utils/render/RenderUtil.java` — drawRect (GL11-квады lwjglx), drawGradientRectV,
  drawRoundedRectShader (GL20), scaleStart/End, scissorStart/End, drawStringScaled.
- `utils/render/ShaderUtil.java` — компиляция GLSL-программ (GL20 native).
- `utils/render/Shaders.java` — VERT (#version 120, ftransform) + ROUND (SDF roundedBox
  + 4-цветный градиент, ВСЁ через gl_FragCoord — texcoord через lwjglx НЕ доходит!).
- `utils/render/GuiScale.java` — кэш guiScale (из res.illlIlIliI()).
- `rustme/CheatIngame.java` — ПОДМЕНА GuiIngame: extends rustme.liIIliliiI (пакет rustme
  обязателен для наследования), override iliIiiIliI(F)V (renderGameOverlay): super →
  setFontRenderer(llliiiIliI()) → new ScaledResolution((iilliIliiI)gs) → CheatHud.renderFrame.
  Инстанс подменяется в поле gs.liliiiIl через GameContext.ensureHud().

### 12.3 КЛЮЧЕВЫЕ ОТКРЫТИЯ ФОРКА (все дизасм-проверены)

1. **GameSettings = iilliIliiI — СЛИТЫЙ КЛАСС**: GameSettings + Runnable (главный цикл!)
   + IThreadListener (addScheduledTask=IllIIiiiil(Runnable), isCallingFromMinecraftThread=
   iIIIIiiiil()). Его run()V = главный цикл игры: создаёт Minecraft (IiiIiilliI()V), крутит
   IllliilliI()V = runGameLoop (fps-limit из настроек мода, NativeAPI.call). Очередь задач:
   поле ilIIllil:Object, исполняется ВНУТРИ runGameLoop.
2. **Minecraft-объект = lIliIiiIiI (УРЕЗАН, 55 методов, БЕЗ игрового цикла)**; держит gs
   в поле iIliIlilI. GameRenderer-подобный код (updateLightmap=IiIIllIiII(F) 967 байт,
   screen-рендер, шейдеры пост) — ВНУТРИ lIliIiiIiI.
3. **ScaledResolution = liIIiIliiI, карта геттеров (дизасм ctor)**: IIllIlIliI()=scaledWidth
   (fbW/guiScale), lIllIlIliI()=scaledHeight (fbH/guiScale), illlIlIliI()=guiScale. Ctor
   заполняет: IIiIIlil=fbW (ililiilliI.iIilIlIIIl), iiiIIlil=fbH (iillIlIIIl), IiiIIlil=
   guiScale (старт=1, затем getVanillaGuiScale() настройка мода + vanilla-цикл).
   ГЕТТЕРЫ НЕ СОЧЕТАЮТСЯ С ПОРЯДКОМ ПОЛЕЙ — мапить только по дизасму!
4. **GuiIngame = liIIliliiI** (super Gui=iIlililiiI): renderGameOverlay=iliIiiIliI(F)V
   (public, не final). ИНСТАНС живёт в gs.liliiiIl (Object-поле!). Подмена инстанса
   работает: создаём CheatIngame(gs), пишем в gs.liliiiIl.
5. **GuiScreen = IlIlliliiI** (abstract, super Gui), drawScreen=IiIIIlIlil(IIF)V; init
   принимает iilliIliiI; 42 экрана наследуют; открыт-ли-экран = lliIilIliI()Z.
6. **FontRenderer = lilililiiI**: drawString=lIlIIliliI(String,FFIZ)I, getStringWidth=
   IIIIIliliI(String)I, FONT_HEIGHT=9. Мод сам держит шрифт в gs Object-поле (MSDF).
7. **Клавиатура**: статик-хелпер мода rustme.lllIiilliI.iiililIIIl(I)Z = glfwGetKey(
   windowHandle(keyboard-поле), key)==key. GLFW_KEY_X=88, GLFW_KEY_J=74.
8. **GL-стек форка**: ванильный код через org.lwjglx.* (шимп LWJGL2→LWJGL3), модовые
   классы — НАТИВНЫЙ org.lwjgl.opengl (GL15/GL20/GL30 — 8 классов с шейдерами).
   СИСТЕМА КООРДИНАТ при нашем рендере (внутри renderGameOverlay): вершины сырого GL —
   SCALED (ванильная ModelView ×guiScale ЖИВА), gl_FragCoord — ФИЗИЧЕСКИЕ пиксели,
   FontRenderer.drawString — SCALED (свой масштаб внутри шрифта), ванильный Gui.drawRect
   — SCALED. Texcoord через lwjglx glTexCoord2f в шейдер НЕ ДОХОДИТ (SDF считать по
   gl_FragCoord + uniform rect).
9. **Mod event bus = lIllIilliI (статик HashMap lliiliIiiI: Class→List<Function1>)**:
   пост=ilIIIIlIIl(Object), регистрация=IIlIIIlIIl(Class,Function1); Consumer-метод
   iIlIIIlIIl — UNSUBSCRIBE (не перепутать!). RenderGameOverlay событие liIiIiiliI
   постится из liIIliliiI.iliIiiIliI (GuiIngame) @404 через iIlliiiliI.ilIillliil(event).
   МЫ НЕ ИСПОЛЬЗУЕМ шину (подмена GuiIngame надёжнее).
10. **Настройки мода**: gs.IiIIllil (Object=llIIiIiIiI holder) → .lliliiIiI (public,
    ru.rustme.settings.Settings) → getData() → MainSettingsCategory → getScreenSettings()
    → ScreenSettingsCategory.getGamma() = FloatNode (гамма!), getKeybindingSettings(),
    getScreenSettings(). GameContext.settingsObj = Settings инстанс (заполняется в
    resolveSprintKey). OptionNode: getValue→storedValue; setValue при tempState=false
    пишет storedValue+tempValue БЕЗ клампа; validateValue — только temp-ветка.

### 12.4 AutoSprint — РАБОТАЕТ (эталон модуля)

Каждый тик держит pressed=true у KeyBinding спринта (цепочка: gs.IiIIllil→holder.lliliiIiI
→Settings.getData→getKeybindingSettings→getKeySprint→KeyBindNode→getValue→KeyBinding
rustme.IiIiIiIiI, поле pressed=iilIIiIiI:Z). Игра сама включает спринт в своём тике
(liIililiiI.liIIIiiilI читает getKeySprint().isKeyDown()). pressed-поле перезаписывается
только клавиатурными событиями → нет timing-гонки. Toggle X (edge-detect через
lllIiilliI.iiililIIIl). При выключении клавиша отпускается ОДИН раз (не спамим false).
ВАЖНО: handleKey отделён от onTick — toggle работает даже когда модуль выключен.

### 12.5 КАСТОМНЫЙ РЕНДЕР — РАБОТАЕТ (эталон)

- ShaderUtil: компиляция GLSL строк (GL20 native org.lwjgl.opengl). Результат в лог:
  compile/link failed с логом компилятора при ошибке.
- SDF roundedRect: uniform rect=(x,y,w,h) в ФИЗИЧЕСКИХ пикселях + fbHeight (переворот Y:
  p.y=fbHeight-p.y) + radius (в физ.юнитах). Вершины квада — SCALED (x..x+w) — матрица
  guiScale жива и масштабирует сама. gl_FragCoord НЕ зависит от матрицы → uniforms в физике.
- FontRenderer.drawString(scaled_x) + ванильный Gui.drawRect(scaled) + наш GL-квад (scaled):
  ВСЁ СОГЛАСОВАНО, если помнишь пункт 8 (GL — scaled вершины, fragcoord — физика).
- FullBright-раскладка: ScaledResolution НОВЫЙ каждый кадр (new liIIiIliiI((iilliIliiI)gs)).
  guiScale кэшируется ОДИН раз (ctx.guiScale = res.illlIlIliI()).

### 12.6 FullBright — В ПРОЦЕССЕ ФИКСА (v44 собрана, НЕ инжекчена)

- Ванильная гамма (GameSettings.IilIllil — единственный float) МЕРТВА: читается только
  собственным геттером/сеттером (iilliIliiI.IllliilliI), рендер её не читает.
- НАСТОЯЩИЙ updateLightmap = GameRenderer lIliIiiIiI.IiIIllIiII(F)V (967 байт): читает
  гамму МОДА (getGamma().getValue() @660-669) → пересчёт RGB → запись в int[256]
  lIliIiiIiI.IIiiiiiiI (единственный int[], lightmap = int-массив!).
- Попытки: v41-42 — заливка текстуры белым GL (не нашла lightmap-текстуру: llilIiiIiI —
  это fog-класс с ДВУМЯ AbstractTex
### 12.7 ТЕХНИЧЕСКИЕ ТРЕБОВАНИЯ АГЕНТА (все причины в одной куче)

1. **НИКАКИХ лямбд/::/Proxy.newProxyInstance** — invokedynamic→LambdaMetafactory
   генерирует plain-класс мимо protected-конвейера → «Corrupted classfile» или тихая
   смерть потока. Только анонимные классы. Проверка: в .class не должно быть java/lang/invoke.
2. **java.nio.ByteBuffer** — direct для GL-вызовов.
3. **Класс в пакете rustme** — для наследования игровых классов (CheatIngame).
4. **pack_agent --cp** — game_cp.jar (9977 rustme классов чистыми именами) +
   game_stubs.jar (стабы org.lwjglx.opengl.GL11, org.lwjgl.opengl.GL20, kotlin Function1).
5. **EventBus-подписка** — анонимный Listener, try/catch внутри onEvent.
6. **Heartbeat в главном цикле** + uncaughtExceptionHandler — иначе смерть потока невидима.
7. **Module API**: конструктор super("Name") → авторегистрация; handleKey отдельно от
   onTick; onEnable/onDisable хуки; setState(true) в конструкторе для стартового ON.
8. **Заливка белым lightmap (v43) НЕ РАБОТАЕТ — мигает** (мод пересчитывает массив
   каждый тик). Правильный путь — крутить ВХОД (гамму), не выход.
9. **Инжект 1 раз на запуск игры!** Повторный — виснет на AttachCurrentThread.

### 12.8 ИНСТРУМЕНТЫ (новые за сессию)

- game_cp.jar = все rustme классы из minecraft_FULL_DEOBF.jar (уже чистые имена) —
  для компиляции агента против игровых классов. game_stubs.jar — стабы
  org.lwjglx.opengl.GL11, org.lwjgl.opengl.GL20, kotlin Function1 (compile_stubs/).
- tools/disasm_range.py <classfile> <method> [lo] [hi] — полный дизасм метода с
  диапазоном офсетов (защита от битых CP-индексов обфускатора).
- tools/disk_name_map.json — карта «файл дампа → настоящее имя класса» (this_class
  из constant pool; (N) в имени = артефакт Windows case-коллизий при распаковке).
- mini_dis.py / dump_members.py работают по stem-имени до «(».

### 12.9 ГАТЧИ СЕССИИ (новые)

1. **(N) в именах файлов дампа** — Windows case-коллизии (9625/9977 файлов), в jar
   суффиксов нет; снять (N) → 1:1 биекция с jar. Карта: tools/disk_name_map.json.
2. **class_analyzer.utf8 возвращает '' на Class-константах** — использовать свой
   utfx-резолвер (e[0] in 7,8,19,20 → рекурсия).
3. **jar-сканер**: сваливать теги 3,4,9,10,11,12,17,18 в один тип ('r') нельзя —
   проверка e[0] in (9,10,11) молча даёт 0 ссылок. Хранить tag отдельно.
4. **ScaledResolution геттеры** — НЕ по порядку полей (обфускатор перемешал):
   lIllIlIliI=HEIGHT, IIllIlIliI=WIDTH, illlIlIliI=GUI_SCALE. Мапить только по дизасму
   (какое поле читает геттер + кто пишет поле в ctor).
5. **IiilIiiIiI = GL-УТИЛ класс** (150 статик методов, обёртки над lwjglx), НЕ
   LightTexture. Настоящий updateLightmap встроен в lIliIiiIiI.IiIIllIiII(F)V.
6. **llilIiiIiI = fog-класс** (НЕ LightTexture): 2 AbstractTexture-поля (IIiIliiiI/
   IiIIliiiI), liIIliliII(F) — fog, при night vision — ранний RETURN (как vanilla NV).
7. **Инжект 12-17 классов стабилен** — DefineClass пакетом, entry client/RustClient.
8. **texId текстуры**: AbstractTexture.liIIIIIIiI.lIiiIililI()I (ленивый glGenTextures).
   LightTexture-объект НЕ AbstractTexture (super=Object) — texId брать У ЕГО ПОЛЕЙ.


---

## 13. СЕССИЯ 2026-09-06/07: MSDF-ШРИФТ, WATERMARK ROCKSTAR, ARRAYLIST VANQUISH (текущий фронт)

### 13.1 ЧТО РАБОТАЕТ

- **MSDF-шрифты**: два атласа msdf-atlas-gen (expensive), класс utils.render.MsdfFont
  (instance-based: MsdfFont.get("sf_semibold") для HUD/ArrayList, get("medium") —
  шрифт watermark rockstar из их же ассетов assets/minecraft/font/msdf/).
  Атласы читаются с ДИСКА: C:\Users\Admin\Desktop\rustme\jni\agent\fonts\msdf\.
  sf_semibold: 776x776 mtsdf range=20 size=64 yOrigin=bottom (expensive-формат).
  medium: 512x512 msdf range=12 size=32 yOrigin=TOP (rockstar) — в MsdfFont есть
  нормализация yOrigin (UV v=row/H вместо 1-top/H; planeBounds top отрицательный).
- **Watermark rockstar** (utils.render.Watermark): верх-центр y=26, часы HH:mm слева
  (iIIIIiIiI_Class267.i_method_8e352841 = SimpleDateFormat("HH:mm"), белый), остров —
  капсула h=15 r=7 фон rgba(24,21,29,0.85), градиент-слой 69% ширины #906BFF alpha
  28%->0 ГОРИзонтальный fade (порядок вершин I_method_43fbeb57: arg1->TL, arg2->BL,
  arg3->BR, arg4->TR; у нас c1=c3=accent, c2=c4=0), пинг-бары справа.
- **ArrayList vanquish** (CheatHud): отдельные плашки rgba(12,12,18,240) radius 0,
  сортировка по ширине убывание, полоска 1.5px слева градиент primary->secondary
  (#906BFF -> #5A4BFF), градиентный текст (CustomFont.drawGradientString, цвет
  каждого глифа = lerp по позиции), medium 8px, x=2 слева, y=30, БЕЗ анимаций.

### 13.2 ПИНГ (реальная цепочка, всё в rustme-классах)

ВАНИЛЬНЫХ КЛАССОВ В JAR НЕТ (net/minecraft — только 4 файла Main). Имена runtime-
классов сети — 1.8-style, найдены в CP классов мода:
- player (liIililiiI) наследует iiIililiiI (AbstractClientPlayer), у него
  iliiililiI() -> iiIilIliiI (NetworkPlayerInfo) — ПОЛЕ IliIiiil.
- iiIilIliiI.liIIillliI() -> int пинг (поле IIlllIIl).
- ЛОВУШКА: liIIillliI обновляется только при ОТКРЫТОМ Tab-листе (GUI liIlliliiI
  зовёт ilIIillliI(ping)). Вечные нули = читали это поле без Tab. Правильный
  живой геттер — illIillliI() (поле lIlllIIl, копируется из живого
  lIllilIIiI.iIliIliIlI() в ctor снапшота).
- Класс lIllilIIiI = "живой" NetworkPlayerInfo (final int lIIiliilI = latency,
  геттер iIliIliIlI()); держится мимо мода, снапшот iiIilIliiI создаётся из него.
- resolvePing в Watermark: player.iliiililiI() -> iiIilIliiI -> illIillliI() -> int
  (валидация 0..5000). Лог пинга раз в 5с: "Watermark: ping=NNN ms".

### 13.3 FULLBRIGHT

Работает: гамма мода (Settings -> getGamma -> FloatNode.setValue, tempState=false
пишет storedValue без клампа). ГАММА=100 ПЕРЕПОЛНЯЕТ lightmap -> МИР И ПЕРСОНАЖ
СИНЕЮТ. Правильное значение FULLBRIGHT_GAMMA=10.0 (полный свет, без артефактов).

### 13.4 КРИТИЧЕСКИЕ ГРАБЛИ (проверено болью)

1. **GL_BLEND в стабе был 0xBE вместо 0x0BE2** — блендинг не включался ВООБЩЕ:
   текст = сплошные квадраты, плашки непрозрачные. ВСЕ GL-константы стаба
   перепроверить при переносе (одна потерянная цифра в hex = молча ничего).
2. **glTexCoord2f через immediate mode lwjglx в GLSL НЕ ДОХОДИТ** (даже с
   glActiveTexture(GL_TEXTURE0)) — квады сэмплят одну точку -> "квадраты".
   UV только через uniform + gl_FragCoord.
3. **glGetFloat(I, FloatBuffer) в lwjglx молча не заполняет буфер** — матричная
   проекция через него невозможна. Позиции квада = scaled x guiScale (overlay
   матрица чистый масштаб, MV-дамп: [1,1,0,0,1]).
4. **GL_ALPHA_TEST режет MSDF-градиент в ступеньки** — на время текста выключать
   (restore прежнего состояния).
5. **glColor текущий после кадра** — кэш GlStateManager игры не знает про наш
   glColor: ПЕРСОНАЖ КРАСИТСЯ В ЦВЕТ ПОСЛЕДНЕЙ ПОЛОСКИ. В конце renderFrame
   всегда glColor4f(1,1,1,1); blend НЕ выключать (кэш считает включённым).
6. **SDF-плашки вплотную дают тёмные швы** (фейд 2px съедает края) — квады
   рисовать с запасом +1px со всех сторон.
7. **(N) в именах файлов**: при поиске файла по имени класса — имя диска может
   отличаться (коллизии), искать по this_class в CP.
8. **parse_cp возвращает i не равный реальному концу CP** у некоторых классов —
   поля/методы разбирать через javap: скопировать класс в
   tools/javap_tmp/rustme/Name.class -> javap -p -c -cp tmp rustme.Name
   (tmp-папка уже использовалась: tools/javap_tmp, javap_tmp2..5).
9. **NoSuchMethodException при переносе имён между классами** — логировать все
   привязки (net chain bound / discovery FAILED) и путь: пока лог молчит —
   исключение съедается и диагностика слепая.
10. **Порядок градиентных вершин rockstar** (I_method_43fbeb57): arg1->TL,
    arg2->BL, arg3->BR, arg4->TR. Перепутаешь — вертикальный фейд вместо
    горизонтального ("градиент не растухается").

### 13.5 ШЕЙДЕР MSDF (текущий, рабочий)

Фрагмент: медиана RGB - 0.5 + thickness; alpha = smoothstep(-smoothness,
smoothness, dist * pxRange); pxRange = range * (qw/(uvW*ATLAS_W)) на CPU,
кламп min 1.0. quad uniform = scaled x guiScale (без матричных проекций).
Vertex: ftransform. gl_FragCoord НЕ используется (quad-подход).
Референс-формула мода (ui_batch.fsh, расшифрован юзером в assets/):
screenPxRange = max(0.5*dot(unitRange, 1/fwidth(uv)), 1.0) — эквивалент.
MSDF_DEBUG шейдер (R=сырой сэмпл, G=alpha, B=fract(uv*8)) остался в Shaders.java —
включается CustomFont.DEBUG_SHADER=true, за один скрин локализует поломку.

### 13.6 СТРУКТУРА КЛИЕНТА (всё в agent/src)

- client/RustClient — entry, поток, модули, tick 1мс
- modules/api/{Module,Modules}, modules/impl/{AutoSprint(X), FullBright(J)}
- events/EventBus
- utils/etc/{GameContext, Log}
- utils/render/{CustomFont, MsdfFont, Watermark, CheatHud, RenderUtil,
  ShaderUtil, Shaders (VERT/ROUND/MSDF/MSDF_DEBUG), GuiScale}
- CheatIngame (пакет rustme) — подмена GuiIngame, вызывает CheatHud.renderFrame
- Watermark: верх-центр y=26, часы HH:mm белым слева, капсула r=7 rgba(24,21,29,.85),
  градиент 69% #906BFF, пинг-бары справа (4 квада 2x(3..6), пороги 450/300/150/75,
  зажжён=белый, незажжён=белый @20%)
- ArrayList: слева x=2 y=30, medium 8px, плашки (12,12,18,240), полоска 1.5px
  слева градиент vertical #906BFF->#5A4BFF, текст градиентный по ширине

### 13.7 НОЧНОЙ ПЛАН (следующий фронт)

ESP: найти в дампе поля позиции X/Y/Z сущностей (IIlIIliIiI/liIlIliIiI), yaw/pitch
камеры, FOV; математика проекции мир->экран (ванильная формула 1.12.2); боксы
нашими квадами; ESP-модуль с toggle. Не начат.

---

## 14. НОЧНАЯ СЕССИЯ 2026-09-07: АВТОНОМНЫЙ АНАЛИЗ 9977 КЛАССОВ — КАРТА ДЛЯ ESP/ПИНГА/РЕНДЕРА (проверено javap/дизасмом)

Инструменты ночи (в tools/, К ЛИСТАМ КЛАССОВ ПРИМЕНЯТЬ ЧЕРЕЗ disk_name_map.json):
- `scan_all.py` — полный CP-скан ВСЕХ 9977 классов дампа → `scan_db.json`
  (поля/методы с дескрипторами и acc-флагами, строки, class-refs). ПАРСЕР
  ЧИНЕН: NameAndType(12)/Methodref и др. = 4 байта после тега (не 2!), int/float
  = 4, InvokeDynamic/Dynamic = 4. Итог: 9977 parsed, 0 ошибок. Классификация
  бакетами → `tools/class_map_full.csv` (file, this_class, super, bucket).
- `jv.py` — запуск javap на копии под чистым именем (javap_tmp7):
  `python tools/jv.py rustme/IIlIIliIiI [-c]` (требует dot/slash полный путь; сам
  резолвит дисковый файл по карте). javap-дампы ночи: tools/javap_entity_root.txt
  (IIlIIliIiI), javap_mc.txt (lIliIiiIiI), javap_gs_c.txt (iilliIliiI),
  javap_nethandler.txt (iliilIliiI), javap_rendermanager.txt (iiiliiiIiI),
  javap_renderglobal.txt (llIlIiiIiI), javap_world.txt, javap_lliIilliiI.txt и др.
- `named_classes.txt` — 715 классов с ЧИТАЕМЫМИ именами (не обфусцированы),
  лежат ВНЕ dump/classes/minecraft/rustme: `dump/classes/minecraft/ru/rustme/*`
  (network, settings, mods/discord, util) + `ru/meproject/rustme/vanilla/*`.
  В minecraft.jar они тоже под MD5-хешами (mapping тот же csv).

### 14.1 ESP — ПОЛЯ ПОЗИЦИИ (ВСЁ ПРОВЕРЕНО javap -c, «проверено»)

Entity root `rustme/IIlIIliIiI` (94 поля). ВСЕ double-поля XOR-закодированы:
чтение/запись только через `llIiIllIII(D)D` (XOR-декод: value ^ llilIIiliI.get()
^ lilIIiliI.get(), AtomicLong-поля-ключи). НО геттеры делают decode за нас.

**Позиция (проверено дизасмом геттеров/сеттеров/move):**
| Смысловое имя | Поле | Геттер | Сеттер |
|---|---|---|---|
| posX | `IlilIiliI:D` | `IlIiillIII()D` | `lIIiIllIII(D)V` |
| posY | `lIllIiliI:D` | `liiiIllIII()D` | `illlillIII(D)V` |
| posZ | `IiiililiI:D` | `lIilillIII()D` | `lIlIlIlIII(D)V` |
| prevPosX | `IIiIliliI:D` | `IiilillIII()D` | `IlliillIII(D)V` |
| prevPosY | `iiiIIiliI:D` | `lliilIlIII()D` | `lliIillIII(D)V` |
| prevPosZ | `IIilIiliI:D` | `lilllIlIII()D` | `iIIIIIlIII(D)V` |
| lastTickPosX | `liiIliliI:D` | `IlilillIII()D` | `iIiIIllIII(D)V` |
| lastTickPosY | `ilIlIiliI:D` | `lIiiIllIII()D` | `lIlIillIII(D)V` |
| lastTickPosZ | `lIlililiI:D` | `IliIlIlIII()D` | `IiilIIlIII(D)V` |

Проверка парности: в `IllliiillI(NBT)` и `lIiiiiIlII(DDDFF)` prev/lastTick
заполняются копиями ТЕКУЩИХ координат попарно по осям (posX→prevX→lastTickX и
т.д.), а камера RenderGlobal интерполирует X через prevX-геттер `IiilillIII`,
Y — `lliilIlIII`, Z — `lilllIlIII`. НИКАКИХ «двойников координат без decode».

- Пара по оси: геттер X `IlIiillIII()D` = getfield IlilIiliI → decode. Единый
  decode-хелпер `llIiIllIII(D)D` (проверено: aload0; dload1; 2×AtomicLong.get()
  XOR; longBitsToDouble). ВСЕ координатные double-поля (pos/prev/lastTick, 9 шт)
  + motion XOR-закодированы одним и тем же хелпером — геттеры декодируют сами.
- `liiilIlIII` — НЕТ. Сеттеры всех трёх координат сразу: `lIIliliilI(DDD)V`
  (setPosition) пишет `IIiIIiliI / iIIlIiliI / IiiIIiliI` (это ТРЕХПАРАМЕТРНАЯ
  позиция БЕЗ decode — сырые поля смещения!) — не путать с per-axis сеттерами
  выше. Комплексные методы: `IIiiiIiilI(D,boolean,lllIIiliiI,lIllIilIiI)` —
  setFire-подобный; `lIiiIIiIil(DDD,FF,I,Z)` = move(x,y,z,yaw,pitch,...) → зовёт
  `llIiilIlII(DDD)` = (x += ...; фрагмент AABB) и `iiiiIllIII(FF)` = setRotation.
- `iiiiIllIII(FF)` = setRotation(yaw,pitch): оба аргумента через
  `iIliillIII(F)F` (XOR-декод float через floatToRawIntBits + 2 ключа) →
  `lIIIlIlIII(F)` пишет `IlIlIiliI:F` (yaw), `illiIIlIII(F)` пишет `IilIIiliI:F`
  (pitch). Геттеры: `IIiIillIII()F` = yaw (decode), `iilIIIlIII()F` = pitch (decode).
  ПРОЩЕ БРАТЬ именно их.
- Motion (из ZNANIA 5 подтверждено и здесь): `iIIlIiliI/IIiIIiliI/IiiIIiliI:D`
  = motion x/y/z (move-метод `lllIlllIII(DDD)` = addToXYZ пишет их; геттеры
  через тот же decode-хелпер).
- Размеры: `lilililiI:F` = width (eye-height `iliilIiilI()F` = width*0.85 —
  дизасм: getfield lilililiI; fmul 0.85f), `llIililiI:F` = height.
- onGround = `iiIIIiliI:Z` (public boolean, читается onLivingUpdate-веткой).
- prev-поля: `IIilIiliI:D` = prevX (геттер lilllIlIII), остальные prev в
  liIlIliIiI см. ниже. lastTick-пара: `lIlililiI:D` (set `IiilIIlIII(D)` через
  decode-хелпер → сеттер lIlililiI) и её чтение `IlilillIII()D`-цепочкой.
- `liiiIllIII()D` ( posY-геттер ) также юзает getDistance `lliIIIlIlI(DDD)`.

**EntityPlayer `rustme/liIlIliIiI` (157 методов, super=IIlIIliIiI):** 76 полей,
двойников координат НЕ содержит — позиция наследуется. Контейнер инвентаря и
capabilities — из ZNANIA 5. У wrapper'а `IIiIIiIIiI` (91) есть СВОИ тени
координат: `IIllliiII:D` (x), `IiiiiIiII:D` (y), `iiIiiIiII:D` (z) + prev-тени
`IiiIiIiII:D / lIllliiII:D / lIiIiIiII:D`, синхронизируются методом
`iiliIiiilI()V` (плавное приближение: если |delta|>10 → телепорт-копия, иначе
`shadow += delta*0.25`). Т.е. для ЛОКАЛЬНОГО игрока есть интерполированные
теневые координаты прямо в wrapper — для ESP других игроков ИХ НЕЛЬЗЯ брать
(тени есть только у SP-wrapper), у чужих Entity читать геттеры корня.

**Bounding box `rustme/ilIlIilIiI` (AABB, проверено конструктором (DDDDDD) 14.1-fix):**
`lIiIilIlI:D` = minX, `IiiIilIlI:D` = minY, `liiIilIlI:D` = minZ,
`iiiIilIlI:D` = maxX, `IIiIilIlI:D` = maxY, `iIiIilIlI:D` = maxZ
(конструктор: Math.min по (arg0,arg3)=X → minX=lIiIilIlI, Math.min(arg1,arg4)=Y →
minY=IiiIilIlI, Math.min(arg2,arg5)=Z → minZ=liiIilIlI; max аналогично: maxX=iiiIilIlI,
maxY=IIiIilIlI, maxZ=iIiIilIlI). ⚠ ИСПРАВЛЕНО 07.09: в первой записи ночи maxY/maxZ
стояли наоборот. Геттер AABB у Entity: `iiIlIIlIII()Lrustme/ilIlIilIiI;`
(getBoundingBox, public) и `liIIilIlII()`.

### 14.2 КАМЕРА / ПРОЕКЦИЯ (проверено дизасмом)

- **`rustme/lliIilliiI` — КАМЕРА/проекция мода (Jewel):** статик поля:
  `IlilIlIl:Lrustme/lliililIiI` = Vec3 — ТЕКУЩИЙ 3D-курсор/центр камеры;
  `IiIlIlIl`/`lIIlIlIl` (FloatBuffer 16) = MODELVIEW/PROJECTION-матрицы,
  `ilIlIlIl` (IntBuffer 4) = viewport; `iIIlIlIl` (FloatBuffer 3) = буфер
  gluUnProject-результата. Статики-флоаты, заполняемые в
  `iIiliiiiII(IIiIIiIIiI, boolean)` (вызывается из Minecraft renderHand/renderWorld
  @5335): `IIIlIlIl:F / liIlIlIl:F / IlIlIlIl:F / llilIlIl:F / iiIlIlIl:F` =
  компоненты векторов направления (yaw/pitch 0.017453292f * sin/cos * invert),
  геттеры `IIlIiiiiII()/lIlIiiiiII()/illIiiiiII()/IllIiiiiII()/lllIiiiiII()`.
  `IIiliiiiII(World, Entity, float)` = **rayTraceBlocks-обёртка** (возвращает
  `lllIIiliiI` = RayTraceResult, использует getMouseOver-ветки, в Minecraft
  зовётся на 3835/4466/6998). `IiiliiiiII(Entity, double)` = Vec3 позиции с
  интерполяцией (берет IlilillIII/IlIiillIII/liiiIllIII/lIilillIII/lIiiIllIII).
  ДЛЯ ESP: свои матрицы можно взять ДЖАВАМИ из этих статик-буферов, не трогая
  нативный glGetFloat (который в lwjglx мёртв, см. 13.4 п.3)!
- **RenderManager = `rustme/iiiliiiIiI`** (проверено): instance-поля
  `llIiIlll:D / iiliIlll:D / iIiIIlll:D` = renderPosX/Y/Z (сеттер
  `iiiilIIiII(DDD)V`, зовётся из RenderGlobal `llIlIiiIiI.IlIliIliII` @368),
  `liliIlll:F` = renderYaw (пишется в prepare-блоке с формулой yaw*90+180 для
  инвентаря-дальнобоя и (yaw-prevYaw)*partial+yaw для обычного), `IiliIlll:F` =
  renderPitch. `iiIilIIiII(Entity, DDD, FF, boolean)` = renderEntityWithPosYaw,
  `lIIilIIiII(Entity, DDD, FF)` = статический рендер. Карта рендереров:
  `ilIiIlll:Map<Class, llIIiiiIiI>` (37+ рендереров - наследники `iIlIiiiIiI`).
- **RenderGlobal = `rustme/llIlIiiIiI`**: в `IlIliIliII(Entity, llIIllliiI, float)`
  считает интерполированную камеру и кладёт в статик-поля `rustme/IlIIliiIiI`:
  `iiIliliiI:D` = camX, `lIIliliiI:D` = camY, `IlililiiI:D` = camZ (проверено
  @2340-2352: prev+ (curr-prev)*partialTick). Класс `IlIIliiIiI` — контейнер
  RenderInfo (там же `iilliliiI` = singleton). Формула камеры совпадает с
  ванильной: camX = prevPosX + (posX - prevPosX) * partialTicks.
- **FOV (проверено):** `iilliIliiI.iIiiIilliI()F` = FOV-настройка (настоящая,
  vanilla-Option FOV); внутри: gs.IIIiiiIl → (IlillilIiI)cast → `.liIIiillI:F`.
  ТРИ флоата хелдера `rustme/IlillilIiI`: `liIIiillI` = FOV-опция,
  `IiIIiillI` = гамма-мода (sync в gs.IilIllil: при loadGameOptions пишется
  IilIllil=IiIIiillI и наоборот, @739-776), `IIIIiillI` = пока не разведён
  (int `iiIIiillI` читается в runGameLoop). `IiIIiillI` передается в
  `IIiililiiI.liillIiliI(IIiIIiIIiI, F)` (fov modifier для спринта и т.п.).
- **Minecraft `lIliIiiIiI`**: `iiiIllIiII(float partialTicks, long)` — метод,
  рендерящий мир из GuiScreen-ветки: строит `lllliiiliI(screen, w, h, fov)` =
  рендер-«стекло» экрана поверх (наследники iiIiIiIiI → lilliiiliI = command-база).
  Для кастомного рендера поверх мира нам НЕ нужен экран — только шейдеры 14.4.

### 14.3 ПИНГ — ГЛАВНАЯ НАХОДКА НОЧИ (проверено дизасмом)

- **Сетевой хендлер = `rustme/iliilIliiI`** (implements illiilIIiI, 85 методов,
  64KB). Держит **`IiIllIIl: Map<UUID, iiIilIliiI>`** (public final) — ТАБЛИЦУ
  ИГРОКОВ. Сюда сыплется S34 (SPacketPlayerListItem):
  `lliIillliI(illlilIIiI packet)` → по enum `IIllilIIiI` (значения: ADD_PLAYER/
  REMOVE_PLAYER/UPDATE_LATENCY(=«UPDATE_LATENCY» строка в CP!)/UPDATE_*):
  - REMOVE → map.remove(uuid)
  - ADD → map.put(uuid, new iiIilIliiI(live))
  - UPDATE_LATENCY → `snapshot.llIIillliI(live.iIliIliIlI())` — живой пинг
    пишется в снапшот КАЖДЫЙ пакет!
- **Снапшот = `rustme/iiIilIliiI`** (это НЕ iiIilIliiI из ZNANIA 13.2... ВАЖНО:
  ZNANIA 13.2 именовала снапшот `iiIilIliiI` — верно, это и есть он; я
  изначально искал «живые lIllilIIiI», но держателем живых является ХЕНДЛЕР):
  ctor копирует из ЖИВОГО `lIllilIIiI` (NetworkPlayerInfo): profile=«...», поле
  `lIlllIIl:I` ← live.iIliIliIlI() (латентность), gametype, skin-location.
  Геттеры снапшота: `illIillliI()I` = **живой пинг** (getfield lIlllIIl —
  обновляется UPDATE_LATENCY), `liIIillliI()I` = ВТОРОЕ int-поле `IIlllIIl:I`
  (заполняется ТОЛЬКО через iilIillliI(int) из Tab-GUI — та самая ловушка
  нулей из 13.2!). ИТОГ: наш Watermark-цепочка ВЕРНА (player →
  iliiililiI() → iiIilIliiI → illIillliI()), нули были из-за чтения
  liIIillliI либо снапшота из пустого источника.
- **Живой NetworkPlayerInfo = `rustme/lIllilIIiI`** (4 поля, final): ctor
  `lIllilIIiI(illlilIIiI container, GameProfile, int latency, gametype, skin)`;
  latency = final `lIIiliilI:I`, геттер `iIliIliIlI()I`. САМ живой хранится в
  **`rustme/illlilIIiI.llIiliilI: List<lIllilIIiI>`** (пакет S34), но список
  одноразовый (живёт до обработки пакета). ПЕРМАНЕНТНО живые пинги живут в
  снапшотах карты хендлера (обновляются сетью), больше В ДАМПЕ держателей нет.
- Как добраться из агента: gs.Illlllil → (liIililiiI) → `.lIlilIIl` (Object)
  → (iliilIliiI) → `.ilIIlIlliI()Ljava/util/Collection;` (значения карты) или
  `.IIIiillliI(UUID)` / `.lIIiillliI(String name)` (перебор по имени). Снапшот
  `iiIilIliiI` → `illIillliI()I` = пинг. Путь проверен по дизасму Tab-GUI
  `liIlliliiI` (он именно так и берет коллекцию @633 и пинг @45).
- БОНУС: у хендлера есть `IiillIIl`-методы-события (IliIlIlliI и т.п. — 30+
  void(IlliilIIiI) обработчиков пакетов; по именам каналов ниже).

### 14.4 HUD/РЕНДЕР МОДА (проверено по CP-строкам, сверка с assets/shaders/)

Классы-загрузчики расшифрованных шейдеров (все уже в assets/minecraft/shaders/):
- `iIllIIiliI` — ui_batch (UI-батчер: наш MSDF-конкурент, формулы 13.5 верны)
- `liIliIiliI`/`IllliIiliI` — scene.vsh/fsh (мировой шейдер)
- `iiIlIIiliI` — terrain.vsh/fsh (ARRAY_COUNT)
- `llIIiIiliI` — armor.vsh + scene.fsh (ARMOR enum)
- `IlliIIiliI` — item_icon.vsh/fsh
- `lliIIIiliI` — sprite_renderer
- `iIIIIIiliI`/`liIIIIiliI`/`iiIIIIiliI` — blur_fullscreen/blur_down/blur_up/
  blur_composite/zone_glow (uBlur/uStrength/uSource; iiIIIIiliI ещё uMask/
  uColor/drawSilhouette = POST-процессор с масками)
- `IiiliIiIiI`/`liiliIiIiI` — общий компилятор GL-программ (shaders/program/*,
  «Couldn't compile %s program»)
- GL-обёртка мода = `IiilIiiIiI` (150+ статик-методов, lwjglx→native bridge;
  вызовы-«IiilIiiIiI.iiiI()» встречаются во всём рендере).

### 14.5 МОД-АРХИТЕКТУРА / ИЕРАРХИИ (счёт детей проверен)

- `IlilIIIliI` — база UI-widget мода: 164 наследника (deep), handleEvent/
  isEnabled, корневой listener-фреймворк.
- `iIlililiiI` — Gui (vanilla-база рисования): 99 наследника;
  `IlIlliliiI` = GuiScreen (79 наследника).
- `lIlliIliiI` — НЕ сетевой пакет! Это база «эффектов мира» (44 прямых
  наследника; поля lIlIiiIl:I + lllIiiIl:F + карты; метод
  ilIiIIiIil(FFFFFF, Entity) — спавн частиц-эффектов). Сетевые пакеты мода —
  отдельные named-классы ru/rustme/network/* (см. 14.6), обфусцированные
  тоже есть (147 классов-наследников illiilIIiI).
- `lilliiiliI` — база «конфиг/командных» узлов: 40 прямых наследников
  (+llIlliiliI = Rotation-holder мода с полями pitch/yaw/roll-флоатами —
  полезен как готовый класс «поворот на цель»).
- `IliIIiIliI` — RustMe UI base: 81 наследник (меню: donate, statistics, admin).
- `lIiIllIliI` — switch-map enum→int[] (маршрутизация каналов).
- Регистры контента: `iIliliiiII` (66 статик-полей illiIiIiII; строки
  hemp_seed/pumpkin_seed/corn_seed/potato_seed/…berries) = ПРЕДМЕТЫ;
  `IIliIiIiII` (58 статик-полей; beancan_grenade/grenade_f1/molotov_cocktail/
  wooden_spear/stone_spear/cleaver/mace/machete/long_sword/…) = ОРУЖИЕ.
  `iIlIlIiiII` — категории блоков (wood/stones/explosives/metal_ore/sulfur_ore);
  `iiIlIiIIiI` — Block-реестр (253 полей!). `iIiililliI` = ModEntityType
  (Empty/Item/Block/BowAndArrow/HandsDown).
- Топ-glue (обфусцированные, по числу ссылок на named-классы): llliilIliI
  (24: dispatcher карт/mlrs/homeinfo/donate/research/radial), liilliIliI (21:
  HUD-иконки donate/metabolism/clock), IllIlIIliI (20: клиентские хендлеры
  каналов rust:met:update, rust:player:state, rust:world:safe/raid/decay...),
  llIllIiliI (15: карта-маркеры), iiIIiiIliI (13: radial-menu), ililIliliI
  (13: донат-URL), iiiIIliliI (assign_friend), iiIlliIliI (lootbox),
  lIllIliliI (death-screen), liiiilIliI (misc-каналы: shownot/item:pickup/
  sc:entryupd/sc:timeupd/evt:khenter/evt:khquit/uphint/gntcs:upd).

### 14.6 СЕТЬ / ПАКЕТЫ / ЭКСПЛОЙТ-ПОВЕРХНОСТЬ

- **Named-классы протокола (715 файлов, список tools/named_classes.txt):**
  ru.rustme.network.{team(49),inventory(35),metabolism(24),research(20),
  craft(19),player(13),item(10),sounds(7),admin(7),wipeinfo(4)} +
  ru.meproject.rustme.vanilla.network.* (481: world/map, voicechat/udp,
  donate, damage, assignfriend, textfield, specialgameevents, genetics...).
  Формат: kotlinx.serialization (PayloadPacketData + $$serializer +
  Companion), каналы = XxxPayloadChannels.
- **VoiceChat UDP** (C2S/S2C VoicePacket(+Encrypted), Heartbeat,
  EndTransmission, AudioFrame) — потенциальный канал для exploration, но
  зашифрован (VoicePacketEncrypted).
- **Admin-каналы:** ru.rustme.network.admin.{AdminGiveItemPacketData,
  AdminSpawnVehiclePacketData, AdminPayloadChannels}; чат-команды мода
  «rust:admin:spawn», «rust:admin:give» (в классе IlIIilIliI = admin-menu UI:
  tab/filter/entry). КЛИЕНТСКИЕ пакеты админа существуют — сервер проверяет
  права, но сам факт channel-архитектуры позволяет послать что угодно —
  РИСК БАНА, не использовать.
- `liIIIlIIiI` = Rcon (vanilla-подобный серверный слушатель, клиенту бесполезен).
- `IiIIIiiliI` — Main-класс с main(String[]) (--accessToken и т.д.).
- Сетевая «шина» (не FML-каналы, своя): BinaryDataInputDecoder /
  BinaryDataOutputEncoder / EfficientBinaryFormat (named, meproject) +
  Netty (59 классов io/netty в бакете network-netty).
- Утечки приватности: `IiillllIiI`/`IiillllIiI`-группа OptiFine-cape-URLs
  (s.optifine.net), skins.minecraft.net — старые http-классы.
- `iIllIIliiI` — античит-подобный (строки MpServer/doDaylightCycle/reEntryProcessing/
  chunkCache/getChunk/Quitting — это WorldClient-строки; класс = WorldClient).

### 14.7 СТАТИСТИКА КЛАССИФИКАЦИИ (class_map_full.csv)

misc-small 6981 (мелочь/синтетика), game-core-mid 1894, game-core-heavy 763,
kotlin-lambda-ish 1733, world-related 236, mod-widget 217, enum 214,
tileentity 195, entity-related 178, block 154, network-packet 147,
entity-renderer 85 (37 прямых детей базы iIlIiiiIiI + наследники),
json-gson 85, nbt 82, math-user 76, gui-screen 74, item-related 64,
network-netty 59, auth-profile 47, lambdas 46, gui-textures 43,
mod-command-ish 42, kotlin-coroutines 36, particle 32, render-gl-wrapper 24,
render-shader-loader 14, mod-ui 12.

### 14.8 ЧТО ДАЛЬШЕ (готовые имена для ESP-модуля)

Чтение чужих игроков: world.playerEntities = `IlIiiiiil`
(подтверждено ZNANIA 5+10; перепроверено: List-поле IlIiiiiil пишется в World
ctor, читается 15 методами). Элемент списка = `IIiIIiIIiI`/`liIililiiI`.
Для каждого: поз = геттеры `IlIiillIII()/liiiIllIII()/lIilillIII()`
(XOR-самодекод), prev-тройка = `IiilillIII()/lliilIlIII()/lilllIlIII()`,
lastTick-тройка = `IlilillIII()/lIiiIllIII()/IliIlIlIII()`,
AABB = `iiIlIIlIII()`, yaw/pitch = `IIiIillIII()/iilIIIlIII()`,
eye-height = `iliilIiilI()` (width*0.85).
Камера для проекции: статик-буферы `lliIilliiI` (matrices+viewport) или
ручная формула camX/camY/camZ из `IlIIliiIiI.iiIliliiI/lIIliliiI/IlililiiI`.
Пинг: iliilIliiI → ilIIlIlliI() → Collection<iiIilIliiI> → illIillliI().
ОСТАЮТСЯ гипотезами (не проверено дизасмом): `IIIIiillI`-флоат хелдера
настроек, назначение shadow-флоатов IIiIIiIIiI (8 шт), 5 D-полей EntityPlayer
(liIliIliI/ilIliIliI/lIIiIIliI/ilIiIIliI/illiIIliI — вероятно bed/по partida).

### 14.9 ESP РЕАЛИЗОВАН (07.09, сборка 16:11, ждёт инжекта/теста)

- `jni/agent/src/modules/impl/Esp.java` — модуль «ESP», toggle G (GLFW 71,
  edge-detect как у AutoSprint). Рендер вызывается из CheatHud.renderFrame
  (главный поток, scaled-координаты) ПОСЛЕ ватермарки и ПОДО всеми HUD-плашками.
- Перебор: world.playerEntities (ctx.playersField) → снапшот массива (CME-защита)
  → скип локального игрока (instanceof liIililiiI) → для каждого IIlIIliIiI:
  поз+prev (геттеры 14.1, интерполяция partialTicks, телепорт-порог 8 блоков),
  куллинг по дистанции 128 блоков от глаз локального игрока.
- Проекция, ПУТЬ 1 (основной): статик-матрицы `lliIilliiI.IiIlIlIl` (MV=2982) /
  `.lIIlIlIl` (PROJ=2983) / `.ilIlIlIl` (viewport=2978) — public static final!
  Мод снимает их каждый кадр в world-проходе (lIliIiiIiI.llilllIiII @131, после
  setupCameraTransform iIIlllIiII(F), ДО overlay) — к нашему кадру это камера мира.
  clip = P·MV·p (column-major), NDC=clip/w, экран=viewport*(ndc+1)/2, Y-флип,
  /guiScale → scaled. Sanity-чек буферов перед использованием (mv[15]≈1 и не все
  нули — glGetFloatv шимпа мог дать пустоту; при провале ОДИН лог и откат).
- ПУТЬ 2 (fallback): ручная математика — камера из статики
  `IlIIliiIiI.iiIliliiI/lIIliliiI/IlililiiI` (RenderGlobal пишет каждый кадр),
  yaw/pitch локального игрока (IIiIillIII/iilIIIlIII), fov=gs.iIiiIilliI(),
  aspect=scaledW/scaledH, стандартный rotate yaw+180/pitch.
- Бокс: 8 углов AABB (bb.lIiIilIlI/iiiIilIlI × bb.IiiIilIlI/IIiIilIlI ×
  bb.liiIilIlI/iIiIilIlI) → проекция всех, min/max по экрану; если хоть один
  угол позади камеры (w<=0.05) — сущность пропускается (не рисуем частично).
  Линии = RenderUtil.drawRect (толщина 1.25 scaled, цвет #906BFF).
- Подпись: имя = wrapper.IlIiIiiilI() → GameProfile.getName() (проверено:
  IlIiIiiilI public в IIiIIiIIiI; Entity.getName() имеет ветки — GameProfile
  надёжнее), + дистанция в м; рисуется CustomFont (sf_semibold 7px) под боксом.
- ГАТЧ (учтён): после наших drawRect GL_BLEND выключен физически, а кэш
  GlStateManager считает включённым → в конце render() glEnable(GL_BLEND)
  (13.4.5). Список сущностей читаем ТОЛЬКО в render-потоке (главный).
- Диагностика в логе: «projection via lliIilliiI matrices» (путь 1 заработал)
  / «camera matrices stale/empty, fallback to manual math» (путь 2).
- Сборка: 28 классов в payload, java/lang/invoke отсутствует (проверено
  байтовым сканом всех .class), round-trip OK, DLL 877056 байт.

### 14.10 ESP v2 — ПЕРВЫЙ ТЕСТ ДАЛ ДИАГНОЗ, ПЕРЕПИСАНА ПРОЕКЦИЯ (07.09 16:40, ждёт теста)

Первый тест (лог юзера): toggle G работает, «projection via lliIilliiI matrices»
прошёл 1 раз, sf_semibold загрузился (лейбл рисовался!), затем «camera matrices
stale/empty», боксов НЕ ВИДНО. Диагноз: (а) старый sanity-чек смотрел только MV
и пропускал ортографическую PROJ — если в буфере на момент overlay лежит
матрица HUD (ortho: m11=0, m15=1), проекция мира через неё даёт мусор вне
экрана; (б) fallback-камера стояла в ногах (статики IlIIliiIiI = posY) и без
учёта fov-модификатора.

v2 (Esp.java переписана):
- VIEW всегда строим сами: камера = интерполированные глаза локального игрока
  (prev+pos)*pt + iliilIiilI() (getEyeHeight = height*0.85; ВАЖНО: lilililiI
  это HEIGHT, не width — 1.8*0.85≈1.53), yaw/pitch = IIiIillIII()/iilIIIlIII().
- PROJ = lliIilliiI.lIIlIlIl ТОЛЬКО при перспктивной сигнатуре: |m11|>0.5,
  |m15|<0.5, m10<0 (gluPerspective: m11=-1, m15=0; ortho HUD: m11=0, m15=1).
  Иначе — ручная перспектива из fov=gs.iIiiIilliI() и aspect=scaledW/scaledH
  (fov-модификатор спринта в fallback не учтён — боксы чуть «дышат» при спринте).
- Бокс: 8 углов AABB → min/max; любой угол за камерой (z2>=-NEAR или cw<=0) →
  скип сущности (клип не делаем).
- ДИАГНОСТИКА (первые 20с после включения G, раз в 3с): полный дамп PROJ (16
  float) ОДИН раз; diag-строка: candidates/drawn/behindSkip + камера/yaw/pitch/
  fov/projPersp; «diag last drawn: имя dist=.. box=[..]..[..] pos=(..)».
  ТРАКТОВКА: candidates=0 → рядом нет других игроков (list пуст кроме себя);
  projPersp=false → в буфере ortho/мусор (живём на ручном fov);
  drawn>0 но на экране нет боксов → сравнить box=[..] с экраном.

### 14.11 ПОПРАВКА ТЕРМИНОЛОГИИ (14.1/14.8): lilililiI = HEIGHT (не width)

`iliilIiilI()F` = getEyeHeight = height*0.85 (ванильная формула), где height =
`lilililiI:F` (не width!). В 14.1/14.8 написано «width*0.85» — имеет в виду
именно height.

### 14.12 ТЕСТ 2: ESP РАБОТАЕТ + ПРОЕКЦИЯ ПОДТВЕРЖДЕНА; v3 (17:04) — FPS И КЛИП

Тест 2 (16:50): **боксы и подписи рисуются правильно** (имена/дистанции верные).
Дамп захваченной PROJ: `[0.53 0 0 0 | 0 1.0 0 0 | 0 0 -1.0 -1.0 | 0 0 -0.1 0]`
→ perspective-путь работает; из матрицы: fovY=90° (m5=1.0 → f=1/tan(45°)=1),
**infinite-far проекция** (m10=-1.0, m14=-0.1 → near=0.05, far=∞), aspect=fbW/fbH.
**ПОПРАВКА 14.2: `iilliIliiI.iIiiIilliI()` в рантайме даёт 0.16 — это НЕ FOV**
(в диагностике видно; что за флоат liIIiillI — неизвестно). Для ручного пути
fov берём дефолт 90 (валидация 30..110 в Esp).

Проблемы теста 2 и v3:
- МАЛО FPS: `entityName` звал reflection `getMethod("getName")` на каждого
  игрока каждый кадр (~600+/сек линейных поисков — главный пожиратель) +
  4 отдельных begin/end на сущность с полным GL-состоянием. v3: методы
  getName кэшируются; ВСЕ боксы рисуются ОДНИМ glBegin/glEnd (16 вершин на
  бокс); тригонометрия view раз в кадр; строки diag только в diag-окне.
- ПРОПАДАНИЕ ПРИ ЗУМЕ: сущность скипалась если ХОТЬ ОДИН угол AABB за
  плоскостью камеры (behindSkip=1..3 в диаг). При узком fov зума краевые
  игроки постоянно «за камерой». v3: псевдо-клип — угол с cw<=0 проецируется
  с cw=W_CLAMP (0.05), уезжая далеко в верную сторону; 2D min/max накрывает
  видимую часть. Скип только если ВСЕ 8 углов за камерой (сущность позади).
- Подпись: если бокс ушёл за низ экрана (клип), лейбл лепится к нижнему краю;
  tx клампится в экран.
- Диаг-строка теперь: candidates/drawn/fullyBehind/cam/yaw/pitch/projPersp.

### 14.13 ШРИФТЫ ВШИТЫ В DLL — АВТОНОМНОСТЬ ОТ ДИСКА (07.09 17:25)

DLL самодостаточна: можно передать на другой компьютер, где нет папки rustme —
шрифты, классы агента, шейдеры едут внутри. `C:\Logs` DLL создаёт сама
(CreateDirectoryA в dllmain), логи по-прежнему пишутся туда.

- `tools/pack_agent.py`: перед компиляцией `generate_font_data()` регенерирует
  `jni/agent/src/utils/render/FontData.java` — атласы из `fonts/msdf/`
  (medium.json/png + sf_semibold.json/png, Base64-чанки по 60000 симв. —
  CP UTF8 limit 64KB, лимит метода не задет — это поля, не код). Класс едет
  в общий payload тем же protected-конвейером: 29 классов, FontData
  plain=1051314 enc=1051570, round-trip OK, java/lang/invoke нет.
- `MsdfFont.loadAtlas(fileName)`: сначала ДИСК (dev-оверрайд: положил файл в
  fonts/msdf — используется он, можно менять шрифт без пересборки), иначе
  `FontData.get(fontBase, ext)`. Источник пишется в лог ОДИН раз:
  «atlas source: disk (...)» / «atlas source: embedded payload».
- Проверено bytecode-уровнем (чистый JDK, без GL): байты FontData.get()
  байт-в-байт MATCH оригинальным файлам для всех 4 атласов.
- Размеры: DLL 877КБ → **2.1МБ** (payload.h 6.2МБ, ~1МБ кодирование + 1МБ
  Base64-у пух в строках .class). В память грузится лениво, как раньше.
- sf_medium.* в payload НЕ зашиты (никто не запрашивает; если понадобится —
  добавить в FONTS_TO_EMBED в pack_agent.py).
- При обновлении шрифтов: положил файлы в fonts/msdf → build_dll.bat — всё
  переехало в DLL.
