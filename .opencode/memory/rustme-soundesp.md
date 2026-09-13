---
name: rustme-soundesp
description: SoundEsp v2.3 (09-10, DLL 17:59, ждёт теста) — звуки НЕ через payload
  (rust:sound:play не ходит, diag доказал); поллинг SoundManager.playingSounds с
  диффом по ключу; цепочка
  gs.iiliIilliI()→SoundEngine.iIliiiil→SoundManager.IiliIliIl→wrapper.IliiIliIl→ISound;
  три тест-фикса: опечатка имени (IIlIiliiI≠IIIlIiliiI), геттеры по РАНТАЙМ-классу
  инстанса, ResourceLocation = lllililIiI (path-геттер lIilllillI, НЕ wrapper
  llIilIiliI.getName!)
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e23a6951-aa81-4a1d-9a32-5c8aee908dc9
---

**СТАТУС: v2.3, DLL 17:59 (09-10) — ждёт теста юзером. Дневник: (1) 15:06 — `sound chain resolved OK`, но каждый тик `poll failed: IllegalArgumentException: object is not an instance of declaring class`; (2) 15:20 — фикс рантайм-класса (ниже), снова то же; (3) 17:59 — фикс ResourceLocation (ниже).**

**ROOT-CAUSE v1 (payload-версия была мертва по определению):** diag полдня показывал приходящие каналы (rust:world:safe, rust:player:swing, rust:misc:uphint, rust:world:upkeep, rust:dmg:close) — подписка шины работала, но `rust:sound:play` НЕ ПРИХОДИТ НИ РАЗУ: сервер не рассылает звуки мира пейлоадами. Звуки — ванильный путь S29 → клиентский SoundManager.

**Цепочка (декомп SoundManager `IililIiliI` = ru.rustme.sounds.SoundManager (Kotlin) + ISound `IIIlIiliiI`, всё javap-верифицировано):**
- `gs.iiliIilliI()` → SoundEngine `rustme.IIiililiiI` (строка "Sound engine started"; сам SoundHandler `liiililiiI` в полях НЕ хранится нигде — осиротелен, путь только через геттер gs)
- SoundEngine поле `iIliiiil` → SoundManager `rustme.IililIiliI`
- SoundManager поле `IiliIliIl` (LinkedHashMap<String, SoundInstance>) = playingSounds
- SoundInstance (wrapper `rustme.llIilIiliI`, поля IliiIliIl=ISound, name=String-ключ мапы) поле `IliiIliIl` → ISound `rustme.IIlIiliiI`
- ISound геттеры: `llIilIiliI()` → **ResourceLocation = `rustme.lllililIiI`** (⚠ НЕ `llIilIiliI` — это wrapper!); путь звука = **`lIilllillI()`** (поле `iIIlIlIlI`); domain = `ililllillI()`; `toString()` = domain+":"+path. **X=`lIIilIiliI()F`, Y=`lIlilIiliI()F`, Z=`iililIiliI()F`**; volume=`illilIiliI()`, pitch=`lililIiliI()` (вычисляемые — умножают на данные Sound-объекта)

**Маппинг X/Y/Z не угадан, а снят:** positioned-ctor impl `lIllIiliiI(…, F volume, F pitch, …, F x, F y, F z)` — fload 8→putfield `iIlllllI`, fload 9→`IilllllI`, fload 10→`IIlllllI`; геттеры читают ровно эти поля. `lilllllI`=volume, `lllllllI`=pitch. ⚠ Дамп-файл `lIllIiliiI(9).class` содержал ЧУЖОЙ класс (`lIlliilIiI`) — реальный файл искать перебором по this_class (NTFS case-коллизии).

**Поллинг:** каждый 1мс-тик дифф ключей мапы (HashSet seenKeys, лимит 4096 со сбросом); новый ключ = новый звук → classify (словарь конкурента 1:1: WEAPON_MAP 15 пар ak47→AK47… + CATEGORY_CHAIN ~60 групп: step→скрыть, timed_explosive→C4, airdrop/heli/bradley→циан, landmine/turret→жёлтый, выстрелы→оранж) → метка x/y/z. CME-защита: `entrySet().toArray()` с глушением (звуковой поток мутирует мапу). Entity-звуки считают позицию от entity каждый тик через интерфейсные геттеры — метка едет за целью автоматически.

**❗ГАТЧ регистрозависимых имён: obfuscated имена для loadClass КОПИРОВАТЬ из scan_db программно, не перепечатывать.** Опечатка `rustme.IIlIiliiI` (две заглавные I) вместо `rustme.IIIlIiliiI` (три) = ClassNotFoundException в каждом ретрае (лог спамился ~1/с).

**ТЕСТ 15:06 (после фикс-а имени): `sound chain resolved OK`, но каждый тик `poll failed: IllegalArgumentException: object is not an instance of declaring class`** — объект из `SoundInstance.IliiIliIl` НЕ реализует интерфейс, с которого резолвились геттеры.
**ФИКС 15:20 — резолв геттеров ПО РАНТАЙМ-КЛАССУ объекта**: `methodsFor(isound.getClass())` — getMethod по фактическому классу, кэш `sndMethodCache` по имени класса; не-звуковые классы кэшируются негативно (Method[0]) с одноразовым логом `non-sound entry class: <имя>`. Паттерн: **если reflection-target может быть разных классов — резолвить Method по рантайм-классу инстанса, не по предполагаемому интерфейсу**.

**ТЕСТ 15:20 → всё ещё то же исключение. ФИКС 17:59 — третий слой той же ловушки:** `resNameGetter` резолвился от **wrapper-класса `llIilIiliI`** (его `getName()` = UUID-ключ мапы!), а вызывался на `lllililIiI` (ResourceLocation), который возвращает ISound-геттер `llIilIiliI()`. Фикс: `resLocC = loadClass("rustme.lllililIiI"); resNameGetter = resLocC.getMethod("lIilllillI")` — path-геттер (дизасм: читает поле `iIIlIlIlI`; `ililllillI()` = domain; `toString()` = domain+":"+path). Урок: **в obf-семействе классов цепочка «поле → тип → метод» должна верифицироваться на КАЖДОМ шаге по scan_db/javap — один неверный символ в имени класса = IllegalArgumentException/ClassNotFoundException**.

**Классификация/рендер:** цвета COL_COMBAT=0xFFFF8A3C / COL_VEHICLE=0xFF54E0FF / COL_LOOT=0xFFFFE14D; слияние близких меток одного типа (stMerge, позиция подтягивается к новому звуку); рендер Esp.projectToScreen + плашка `%s x%d (%.0fm)` + точка, fade последние 0.7с. Настройки: Дистанция 20-200 (100), Время метки 1-10с (3), Слияние 1-10 (3).

**Тест-критерии:** `[SoundEsp] sound chain resolved` → `[SoundEsp] sound: <путь> @(x,y,z)` на шагах/выстрелах. Если классификатор режет нужное — имена из diag подскажут, что дополнить в словарь (имена конкурента могли отличаться от путей этой сборки).

**ФИКС 09-11 (юзер: «не показывает звуки ak47 и шагов»):** классификатор резал нужное в двух местах: (1) `{"step",""}` = намеренный SKIP шагов → стало `{"step.","Footsteps"}, {"footstep","Footsteps"}`; (2) звуки форка пишутся с ПОДЧЁРКИВАНИЕМ (`gun.assault_rifle.shoot`) и не матчили ключи с точкой (`assault.rifle`) — `classify()` теперь нормализует `_`→`.`, добавлены dotted-варианты недостающих ключей (supply.drop/flame.turret/cargo.ship/scrap.transport). WEAPON_MAP ak47 заработал после нормализации.

Связано: [[rustme-chtdump-competitor]], [[rustme-esp]], [[rustme-network-c2s]].
