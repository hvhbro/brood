---
name: rustme-network-c2s
description: Карта C2S-пакетов rustme (анализ 09-08) — полный конвейер отправки
  payload из агента (liliiilliI → EfficientBinaryFormat → IIlllIIIiI →
  netHandler.llIlIllilI), 47 каналов (tools/c2s_map.json), клиент payload НЕ
  валидирует; НЕ ТРОГАТЬ astraea-античит (NativeAPI.handlePacket — класса нет в
  дампе) и rust:admin:*; кандидаты AutoRespawn/AutoEat ждут «делай»
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fc8109d3-5706-4108-8643-181e2c269238
---

Анализ 2026-09-08 (Kotlin-часть + все пакеты клиент→сервер + эксплойт-поверхности). Только анализ, кода нет ([[rustme-phase-gating]]), ZNANIA не обновлял ([[rustme-znania-policy]]). Артефакты: `tools/analyze_c2s.py`, `tools/c2s_map.json`, `tools/channel_holders.json`.

**КОНВЕЙЕР ОТПРАВКИ C2S (всё дизасм-проверено, классы public, зовётся джавами без лямбд; Netty в classpath игры):**
`rustme.liliiilliI.IlIIIIIIIl(player /*liIililiiI*/, channel /*"rust:..."*/, data /*PayloadPacketData*/)` → `iiIllIIIiI(Unpooled.buffer())` → `kotlinx.serialization.serializer(data.getClass())` → `EfficientBinaryFormat.INSTANCE.encodeToByteBuf(buf, data, ser)` → `new rustme.IIlllIIIiI(channel, buf)` (C2S CustomPayload, payload ≤ 32767Б иначе IllegalArgumentException) → `player.lIlilIIl` (netHandler `iliilIliiI`) `.llIlIllilI(packet)` → NetworkManager. Приём S2C: `IlIIilIIiI` (канал ≤20 симв., payload ≤1МБ) → `iliilIliiI.IlliillliI` → switch по каналу (vanilla MC|*, gun:recoil...) → default → пост `IlilliiliI(channel, buf)` в шину; подписчики декодируют тем же форматом. Wire-формат EfficientBinaryFormat: varint-инт, UTF-8 строки, enum-индексы, null-марки поверх Netty ByteBuf.

**КАРТА C2S: 47 каналов / 55 call-sites.** Каналы почти всегда в статик-полях holder-классов (getstatic) — резолвить парами ldc→putstatic в clinit (ldc напрямую почти нет). Группы: inv (action/toggle — 9 сайтов/itmact/split/fluid/fluiddrink/vendbuy), craft (request/cancel/sync/tofront/favorite/skin), team (create = ПУСТОЙ пакет/invac/invrej/kick/promote/quit/openmenu), dmg:respawn, misc:shuse/lguse, map:crtuser/deluser, build:subcode/codecld, donate:buy/oloot/lootf/unpref, player:skin, radial:click/close, resch:research/closed, textfld:apply/cls, vc:talkcng, mlrs:select/launch/closed, admin:give/spawn; assignfriend = `rust:assign:open/close/submit` (named AssignFriendPayloadChannels). Остальные ~45 rust:* строк — S2C-only.
- InventoryAction enum (rust:inv:action = windowId+slot+selectedSlot+action): Throw/RightMouseClick/DragRelease/Pour/EquipArmor.
- ItemActionsKeys (rust:inv:itmact = windowId+slot+строка-действие): **Eat, Drink, DrinkWaterJug, Research, RefillTank, RefillJackhammer, WeaponAmmoEject, WeaponAmmoChange, ChainsawFuelEject, LargeMedkitUse, Drop**.
- CraftRequestPacketData(entryId, count), CraftQueueActionPacketData(queueItemId).

**ЭКСПЛОЙТ-ПОВЕРХНОСТИ:**
- Клиент payload НЕ валидирует вообще (голые Kotlin data-классы, единственная проверка — 32767Б) — из агента «послать что угодно» = одна строка.
- Кандидаты-модули (легально): AutoRespawn (rust:dmg:respawn/RespawnPacketData), AutoEat/Drink/Refill (itmact), VendingBuy-макро. NoSlow-инвентаря гипотеза СНЯТА юзером (09-08: «NoSlow — это прицельное замедление оружия, не инвентарь» → [[rustme-ads-slowdown]]).
- ПРЕДЛОЖЕНО юзеру (ждёт «делай»): AutoRespawn → AutoEat → NoSlow-инвентарь (AutoRespawn — дешёвый тест: докажет, принимает ли сервер payload мимо UI-цепочки).
- НЕ ТРОГАТЬ: `rust:admin:*` (сервер проверяет права = лог/бан), спам map/mlrs/donate (тайминги видны серверу), **`astraea`/`astraea-init`** — АНТИЧИТ: сервер шлёт челлендж → в `iliilIliiI.IlliillliI(IlIIilIIiI)` читается int len + body (DataInputStream) → `ru.rustme.NativeAPI.handlePacket(steamid, bytes)` → non-null ответ уходит обратно в канал `astraea`. Класса NativeAPI В ДАМПЕ НЕТ (инъектится форкнутой JVM; `NativeAPI.call(String, Object[])` зовут GameSettings/main-loop и netHandler) — реверс из jar невозможен, вмешательство = детект. Остальные rust:-каналы через NativeAPI НЕ идут (прямая netty-пересылка, протектор не инспектирует).
- VoiceChat UDP: ключ AES/GCM живёт в `lIiIiilliI.IIllliiiiI` (SecretKey); расшифровка чужого голоса = MITM — запрещено.
- Крипта: `iIiIiilliI` = AES/GCM/NoPadding (voicechat enc/dec, SecureRandom IV); `iliIIiiliI` = AES/ECB/PKCS5Padding + строка 'rustme-magic' + MD5 (обёртка InputStream, 8 юзеров — ресурсы/паки); `IiiliilIiI` = RSA + AES/CFB8/NoPadding + SHA-1 (ванильно-подобная авторизационная).
- Валидация ДВИЖЕНИЙ сервера НАЙДЕНА в клиентском jar (integrated-server классы; NetHandler `lliIlIIIiI`, пороги → [[rustme-movement-exploits]]); валидация payload-каналов (rust:*) по-прежнему неизвестна → «сервер примет X» = гипотеза, живой тест с тестового аккаунта.

Kotlin-слой: Settings = JSON (kotlinx.serialization, camelToSnakeCase, бэкап битого файла, миграции); OptionNode tempState-ветка подтверждена байткодом (= ZNANIA 12.10, не дублировать). 1733 kotlin-lambda-ish + 36 корутин В payload игры есть, но правило «никаких лямбд в агенте» остаётся. `ru.rustme.util.RustTrustStore` = пиннинг сертификатов лаунчер-API; `ru.rustme.mods.discord` = DiscordRPC.

Связано: [[rustme-next-features]], [[rustme-night-map]], [[rustme-phase-gating]].

**Дополнение 09-08 вечер — карта S2C-каналов:** полный список ~86 rust:* каналов с держателями собран (rust:map/craft/inv/mate/dmg/evt/gntcs/sc/misc/world/mlrs/resch/radial/textfld/donate/build/player/sound/team/admin/vc/wipe — по 1-3 holder-класса каждый). Ключевое для сервер-инфо: `rust:misc:itmap` → InitMapPacketData (mapId=технический wipe-id rm+YYMM+вариант, НЕ имя сервера — живой обход 24 серверов это доказал, детали [[rustme-watermark-servername]]); `rust:wipe:state` → WipeStatePacketData (только wipeDate+classic); имя сервера приходит ТОЛЬКО таб-заголовком (S47-аналог rustme/iIllilIIiI, не rust:*-канал). PacketFly/PacketHook (netty-хук utils/net/PacketHook, бэкап jni/agent/backup_packetfly) сознательно исключены юзером из сборки 09-08 вечером («вручную списки делаем») — не баг, не восстанавливать без спроса.

**chtdump КОНФИГ-БЛОЛ (.rdata 0x10200-0x11a00) — рантайм-дамп их config.ini (09-11, полный разбор и значения в [[rustme-chtdump-competitor]]).** **AntiOverlay** (Enabled=1 HeavyMask=1 DivingMask=1 Pumpkin=0) = прятать оверлеи масок форка; текстуры в rustme.IIIliilliI(7): overlay-bleeding/freezing/heavy-helmet/diving-mask.png + pumpkinblur в liIIliliiI(6). **NoSlowJagger** (noSlowDjager*): RemoveSlowness=1 WalkSpeed=0.15 Speed=0.2 MaxHunger=1 AlwaysSprint=0 OnlyJugg=0 MotionBoost=0. ПОРТОВАНЫ (09-11, DLL 86 кл — актуальная механика и фиксы в [[rustme-esp]]): AntiOverlay v3 — маски идут через ИКОН-систему (икон-ключи icon_*, реестр liiIlilliI.iiliIIIiiI), подмена через TextureManager НЕ работает → пробуются ОБА домена (minecraft+rustme) в публичной карте lIillIiiI: tex==null → регистрация TransparentTexture, tex загружен → ПЕРЕЗАПИСЬ СОДЕРЖИМОГО texId (bind+glTexImage2D 1×1 alpha0); diag `domain:path -> texId overwritten` / `not in TextureManager (icons?)` — если второе, texId доставать через ItemIcons-цепочку их икон-реестра. NoSlowJagger: Slowness-снятие (potion-инфра через ctx.resolveNoSlow()!), caps walkSpeed 0.15 (ctx.moveStrafeField), атрибут movementSpeed ADD (UUID 1a2b3c4d...), foodStats.foodLevel=20 (wrapper.iliIiIiII → IIlIIilIiI.iIlllIIlI), AlwaysSprint, OnlyJugg (jug в руке). ГАТЧ: ctx.modClass может быть null пока NoSlow/Strafe выключены — резолвить rustme.iIliiliIiI в самом модуле. 