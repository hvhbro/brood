# Архитектура проекта + как работает загрузка своих классов в JVM

Этот документ отвечает на два вопроса:
1. **Как устроен проект** — где что лежит.
2. **Как свои классы попадают в чужую защищённую JVM** — вся цепочка от инжекта DLL до `install()`.

---

## 1. Общая идея одной картинкой

```
 Игра (rustme.exe + jvm.dll + протектор Astraea)
   └─ запущена, JVM жива, классы игры в protected-формате
        ▲
        │ 1. Inject DLL (один раз на запуск!)
        │
 jni/build/dll/jni_rva_check.dll
   ├─ agent_thread (jni/dll/src/dllmain.cpp)
   │    ├─ ① Attach к JVM через таблицу JavaVM из jvm.dll (RVA)
   │    ├─ ② Поиск правильного ClassLoader через перебор потоков
   │    ├─ ③ DR0 + VEH bypass валидатора protected-классов (jvm.dll+0x1C2D0)
   │    ├─ ④ DefineClass ВСЕХ классов агента из памяти DLL (agent_payload.h)
   │    └─ ⑤ GetStaticMethodID(install) + CallStaticVoidMethodA
   │
   └─ Java-агент уже ВНУТРИ игры (JDK 8):
        Brood.install() → поток CheatMain → GameContext → модули → HUD
```

Ключевой трюк: **память `jvm.dll` не патчится**. Валидатор обманывается hardware-брейкпоинтом на потоке + VEH, а классы агента подаются в том же `protected`-формате, который понимает парсер протектора.

---

## 2. Структура репозитория

```
rustme/
├── README.md                  — короткий обзор
├── ZNANIA.md                  — полная память проекта (источник правды, 14 разделов)
├── ARCHITECTURE.md            — этот файл
├── decryptor.py               — decode_protected(): protected-формат → plain .class
├── dump/                      — распакованные классы игры (9977 шт, для анализа)
│   └── minecraft/rustme/*.class
├── assets/                    — расшифрованные шейдеры/шрифты игры
└── jni/
    ├── dll/                   — НАТИВНАЯ ЧАСТЬ (C++)
    │   ├── src/dllmain.cpp    — вся loader-цепочка (294 строки)
    │   ├── src/offsets.h      — 4 RVA в jvm.dll
    │   ├── src/jvm/jvm_offsets.h — карта слотов JNIEnv (109 функций)
    │   ├── src/jni_types.h    — типы FindClassFn и др.
    │   ├── src/logger.cpp/.h  — лог C:\Logs\jni_rva_check.log
    │   ├── src/memory_utils.cpp/.h
    │   ├── src/agent_payload.h — ГЕНЕРИРУЕТСЯ (все классы агента в protected-формате)
    │   └── build_dll.bat      — сборка одной командой
    ├── agent/src/             — JAVA-АГЕНТ (JDK 8, исходники)
    │   ├── client/Brood.java             — entry: install() → поток CheatMain, цикл 1 мс
    │   ├── events/EventBus.java        — шина TickEvent / SneakStart/Stop
    │   ├── modules/api/Module.java + Modules.java — база модулей + реестр
    │   ├── modules/impl/               — Esp(G), AutoSprint(X), FullBright(J),
    │   │                                 NoSlow, Tracers, AimBot, Svo, … (~25 шт)
    │   ├── utils/etc/GameContext.java  — reflection-доступ к mc/gs/world/player
    │   ├── utils/etc/Log.java          — лог C:\Logs\noslow_agent.log
    │   ├── utils/render/               — CheatHud, Watermark, MsdfFont, CustomFont,
    │   │                                 RenderUtil, ShaderUtil, Shaders, GuiScale…
    │   └── rustme/Hud.java             — наследник игрового GuiIngame (подмена инстанса)
    └── build/dll/jni_rva_check.dll — собранная DLL (регенерируется)
```

Сборка и инжект:

```bat
cd jni\dll
build_dll.bat
:: на выходе jni\build\dll\jni_rva_check.dll (классы+шрифты уже внутри)

:: инжект — ОДИН раз на один запуск игры, иначе виснет на AttachCurrentThread:
jni\build\injector\inject.exe --once --no-pause "C:\...\jni_rva_check.dll" "c:\...\rustme.exe"

:: логи:
type C:\Logs\jni_rva_check.log     :: натив
type C:\Logs\noslow_agent.log      :: java-агент
```

---

## 3. Нативная часть (`jni/dll/`)

### 3.1 `src/dllmain.cpp` — сердце загрузки

Файл ~294 строки, функция `agent_thread()` делает 4 шага (см. раздел 4).
`DllMain` на `DLL_PROCESS_ATTACH` только создаёт поток `agent_thread` и сразу возвращается.

Важные детали реализации:

- `CreateDirectoryA("C:\\Logs")` — DLL сама создаёт папку логов.
- Все JNI-вызовы идут **не через `jni.h`**, а через сырые указатели из env-таблицы:
  `envVTable[JNI_SLOT(Name)]`, где `JNI_SLOT = byte_offset / 8` (`dllmain.cpp:75`).
- После каждого JNI-вызова с возможной ошибкой — `ExceptionClear`, иначе все следующие вызовы «падают».
- Опасные вызовы обёрнуты в `__try/__except (SEH)`: ошибки дают `0xc0000005`, но процесс живёт.

---

## 4. Загрузка своих классов в JVM — пошагово

Это главный раздел. Код — `jni/dll/src/dllmain.cpp:39-283`.

### Шаг 1. Attach к JVM (`dllmain.cpp:53-73`)

```cpp
jvm_base = GetModuleHandleA("jvm.dll");
jvm      = jvm_base + kJavaVmRva;          // main_vm
vtable   = *jvm;                           // JavaVM vtable
attach   = vtable[4];                      // AttachCurrentThread
res = vtable[6](jvm, &envPtr, 0x00010006); // GetEnv(JNI 1.6)
if (res == -2 /*Detached*/) res = attach(jvm, &envPtr, nullptr);
envVTable = *envPtr;                       // живая JNIEnv-таблица
```

Флаг `-XX:+DisableAttachMechanism` в командной строке игры запрещает agent-attach, но **native `AttachCurrentThread` работает**.

### Шаг 2. Поиск правильного ClassLoader (`dllmain.cpp:89-159`)

Проблема: `FindClass` из native видит только системные классы. Игровые (`rustme.*`) видит только **RML-загрузчик игры**.

Решение — loader-цепочка:

```
FindClass("java/lang/Thread")                                        // слот 230
→ GetStaticMethodID(Thread, "getAllStackTraces", "()Ljava/util/Map;") // слот 135
→ CallStaticObjectMethodA → Map<Thread, StackTrace[]>                 // слот 167
→ Map.keySet() → Set.iterator()                                       // слоты 233+195
→ для каждого Thread:
→   getContextClassLoader()                                           // слот 233+195
→   ПРОБА: loadClass("rustme.liIlIliIiI")                             // слот 233+195
→   берём ТОЛЬКО лоадер, у которого проба != null
```

Нюансы (все выстраданы):

- Имя для `loadClass` — **только в точках**: `"rustme.liIlIliIiI"`, НЕ `"rustme/liIlIliIiI"` (RML считает `getHashedName = MD5(dot-имени)`; slash даёт `CNFE`).
- Первый `ctxCL` в мире — лоадер лаунчера (`ru.meproject.Main`), он `rustme.*` НЕ видит. Поэтому перебор, а не первый попавшийся.
- В главном меню игровые классы ещё не загружены → `CNFE` — это нормально (в агенте есть retry).
- Проверочная проба — `rustme.liIlIliIiI` (EntityPlayer), всегда грузится когда игрок в мире. Запасной якорь — `ru.rustme.settings.Settings` (загружен всегда, даже в меню).

### Шаг 3. DR0 + VEH bypass валидатора (`dllmain.cpp:161-198`)

Цель: валидатор protected-классов по адресу `jvm.dll + 0x1C2D0` должен вернуть «успех» для наших классов с нулевой подписью.

Почему именно так:

- Патчить память `jvm.dll` нельзя (`VirtualProtect → gle=87`, да и чексуммы).
- Поэтому — **hardware breakpoint на потоке**: `DR0 = validator`, `DR7 |= 1` (local enable, exec). Ставится только на нашем потоке через `GetThreadContext/SetThreadContext`.
- `AddVectoredExceptionHandler(1, drVeh)` ловит `EXCEPTION_SINGLE_STEP (0x80000004)`.
- Хендлер (`dllmain.cpp:29-37`): если `RIP == validator` → `RIP = stub`, где stub = `mov al,1; ret` (`B0 01 C3`) в `VirtualAlloc(PAGE_EXECUTE_READWRITE)`-памяти.
- Итог: вход в валидатор мгновенно превращается в `return true`. Память `jvm.dll` не изменена.

После пакета `DefineClass` — `g_drActive=false`, хендлер снят, `DR0/DR7` очищены.

### Шаг 4. DefineClass всех классов агента (`dllmain.cpp:200-247`)

```cpp
defineClass = envVTable[DefineClass]; // слот 69
for (blob : g_agentClasses) {         // agent_payload.h, уже в topo-порядке
  __try {
    cls = defineClass(env, nullptr, classLoader, blob.data, blob.size);
  } __except (EXCEPTION_EXECUTE_HANDLER) { continue; }
  ExceptionClear(env);
  if (cls) log("[+] define[name] -> jclass");
  if (name == g_agentEntryName) defined = cls; // entry = client/Brood
}
```

Критичные детали:

- **Блобы — в protected-формате**. Валидный вызов с настоящим блобом безопасен; «тихая смерть» старых проб была от мусорных `buf/len` (парсер → `vm_exit`), а не от самого слота 69.
- **`name = nullptr`**: имя берётся из байткода. Не-null с несовпадающим именем → `wrong name`.
- **`classLoader` = найденный на шаге 2** (иначе `ClassNotFoundException` на `rustme.*` внутри агента).
- **Порядок важен**: `DefineClass` резолвит заголовок eagerly — родитель должен быть определён раньше потомка, иначе `NoClassDefFoundError` и `define → 0`.
- DR0 armed **один раз на весь пакет**.
- `ExceptionClear` после каждого define — иначе pending-исключение травит следующие вызовы.

### Шаг 5. Запуск агента — `install()` (`dllmain.cpp:255-278`)

```cpp
midInstall = GetStaticMethodID(defined, "install", "()V"); // слот 135
CallStaticVoidMethodA(defined, midInstall);                // слот CallStaticVoidMethodA
```

Почему это работает, хотя static-lookup на игровых классах заблокирован: **блокировка не действует на классы, определённые из native через наш DefineClass**. `GetStaticMethodID(install)` на своём классе возвращает валидный `mid`.

`install()` (`jni/agent/src/client/Brood.java:37-53`):

```java
Thread t = new Thread(new Brood(), "CheatMain");
t.setUncaughtExceptionHandler(...); // иначе смерть потока невидима
t.setDaemon(true);
t.start();
```

Дальше агент живёт сам: `run()` → `sleep 2с` → `GameContext.init()` с retry 60с → создание модулей → `ensureHud()` → бесконечный цикл `1мс: update() + EventBus.post(TickEvent) + heartbeat`.
