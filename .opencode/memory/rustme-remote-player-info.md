---
name: rustme-remote-player-info
description: Полная карта «что клиент знает о ЧУЖИХ игроках» (анализ 09-07,
  исправлено 09-08) — личность (GameProfile/UUID/пинг/gamemode/скин),
  геометрия+sneak/sprint-флаги, player-specific dataWatcher (absorption/int/NBT),
  мод-каналы о чужих (тиммейт позиции+Online/Offline/Dead/Wounded, скорборд,
  assignfriend, звуки, voicechat UUID); 09-08: рука+броня чужих ЕСТЬ (S04
  IiililIIiI); НЕ приходит: хотбар, HP чужого
metadata:
  node_type: memory
  type: project
  originSessionId: sess_77a97d83-960d-48a2-80ca-1280c4d65177
---

Анализ 2026-09-07 (второй вопрос чата: «что вообще можем узнать о чужих игроках»). Только анализ, кода нет ([[rustme-phase-gating]]).

**А. ТВЁРДЫЕ ДАННЫЕ (синхронятся всегда):**
- Имя+UUID: `GameProfile` = public final поле `iIIiiIiII` у wrapper IIiIIiIIiI, геттер `IlIiIiiilI()` (лучший источник имени, уже в ESP).
- Пинг: снапшот-карта сетевого хендлера `iliilIliiI.IiIllIIl: Map<UUID, iiIilIliiI>`, живой геттер `illIillliI()`.
- Gamemode: в том же снапшоте (`ilIIlilIiI`). Скин: `NetworkPlayerInfo` держит локацию скина (поле `iIiiIIlIiI`).
- Геометрия: pos/prev/lastTick-геттеры (см. [[rustme-night-map]]), вектор скорости из производных, AABB, yaw/pitch (куда смотрит), onGround, riding = Entity-поле `IiIililiI` у root. У wrapper интерполированные тени координат (`IIllliiII/IiiiiIiII/iiIiiIiII` + prev, обновление `iiliIiiilI()` = shadow += delta*0.25).
- Ванильный dataWatcher-флаги через `IiiIillIII(I)Z` на Entity root: sneak (также `lliililiI:Z`), sprint (id=3), горит/невидим/ест/летит. Таймеры hurt/death — примитивные поля root.
- Дистанция — считаем сами.

**Б. PLAYER-SPECIFIC dataWatcher у wrapper (статик-параметры, семантика int/byte не вскрыта):**
- absorption: float параметр `llIiiIiII`, геттер `lIIiIiiilI()` / сеттер `liIiIiiilI(F)`.
- int параметр `liIiiIiII` (геттер `lIilIiiilI()`), NBT `IIiiiIiII`, байты `iIllliiII`/`llllliiII` (флаги видимости частей скина).

**В. МОД-КАНАЛЫ О ЧУЖИХ (named-классы ru/rustme/network, ru/meproject):**
- ТИММЕЙТЫ — жирно: `TeamInfo` = List<TeammateInfo> (GameProfile, joinTime, `TeammateLocation` = entityId + fallback Vec2d — СЕРВЕР САМ ШЛЁТ ПОЗИЦИИ, leader, `TeammateState` = Online/Offline/Dead/Wounded). Клиентские держатели: `llIllIiliI` (map-маркеры, Caffeine-кэш + StateFlow) и `IiilIlIliI`. Только для своих тиммейтов.
- Скорборд: `ScoreboardEntry(playerName, score)` — имена+счёт всех.
- AssignFriend: `OpenAssignFriendPacketsData.playerProfiles: List<GameProfile>` — полный список профилей.
- KingHill-ивент — только агрегаты (playersInArea, kills), не по-игроково.
- Звуки мода: `PlaySoundPacketData` (entryId, позиция, volume, category) — позиционные, без привязки к игроку.
- VoiceChat: `VoiceChatPlayerInfoPacketData` (playerUuid, velocity host/port) — кто на связи; сами голосовые пакеты зашифрованы (VoicePacketEncrypted).
- Лут-история: дроп-пакет `iIlllIIIiI` (entityId, ItemStack) — копить по entityId (детали в [[rustme-nametags-icons]]).
- Смерти: `OpenDeathScreenPacketData` (timeAlive, killedBy GameProfile, attackDistance) — только про СВОЮ смерть.

**Г. ЧЕГО О ЧУЖИХ НЕТ (проверено, не искать):**
- Инвентарь/хотбар (пустой), предмет в руке (equipment-пакета в форке нет), HP чужого (UpdateMetabolismPacketData и rust:player:state — личные каналы «для себя», хендлер IllIlIIliI кладёт в свой UI-стейт), голосовые данные.

**Вывод для NameTags:** доступный набор = имя, UUID, пинг, gamemode, дистанция, yaw/pitch, sneak/sprint, скорость, absorption, тим-статус (тиммейты), лут-история. Ждёт «делай» — фаза 1 (иконки локального хотбара + плашка из А+В).

**РЕАЛИЗАЦИЯ ПИНГА ЧУЖИХ (08.09, v4 ESP) — ПОДТВЕРЖДЕНО РАНТАЙМ-ТЕСТОМ юзера:** в его логе `diag last: Disonysus ping=108`, `Elkingoaaa ping=113` — цепочка реально даёт живой пинг чужих игроков. Юзер попросил пинг в NameTags — путь вшит в Esp.java: `iliiililiI()` лениво резолвит по UUID через net handler локального игрока (`gs.iIIiiilliI()` → `IIIiillliI(UUID)` = Map.get), значит работает на ЛЮБОГО игрока в мире; геттер живой `illIillliI()`. Reflection-кэш + валидация 0..5000, -1 = без пинга. Детали и раскладка плашки — в [[rustme-esp]].

**GearESP РЕАЛИЗОВАН (08.09 вечер, «делай» юзера):** иконки руки + брони (MainHand + Armor1..7 через `IiliIiiilI`) рисуются над боксами ESP с динамическим размером 6..14 scaled px от высоты бокса; подложка — только ванильный Gui.drawRect (наш lwjglx-вариант глушит blend и ломает RenderItem, гатчи в [[rustme-esp]]). DLL собрана 08.09 22:39, живой тест за юзером.

Связано: [[rustme-nametags-icons]], [[rustme-esp]], [[rustme-night-map]], [[rustme-next-features]].
