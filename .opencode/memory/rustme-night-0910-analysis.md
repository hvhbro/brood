---
name: rustme-night-0910-analysis
description: Ночь 09-09/10 — S2C каталог 92 канала, dmg-каналы без цифр урона,
  depth-пайплайн мода, таймер, SilentAim/AccountManager цепочки
metadata:
  node_type: memory
  type: project
  originSessionId: sess_51789f74-aa32-46f5-a8b5-bc4cf0da3232
---

Ночная сессия 2026-09-09/10 (автономный анализ, код не менялся). Артефакты:
`tools/s2c_full_catalog.json` (канал→класс→поля), `tools/s2c_channels_map.txt`,
`tools/channels_all.txt`, `tools/full_src_index.json` (индекс full_src по Renamed-from),
`tools/decomp/pc_out/` (декомпилированные PayloadChannels). GАТЧ fill-скриптов:
CFR пишет выход под classpath-корень (для named-классов это TMP/ru/...), а не TMP/<pkg>.

**DMG (главный вопрос) — ОТВЕТ: цифр урона в сети НЕТ.**
- rust:dmg:death → OpenDeathScreenPacketData {timeAlive:long, killedBy:DamageSource, withA:DamageSource, attackDistance:float}; DamageSource = sealed {Player(GameProfile)/Item(ItemStack)/Simple(name,SimpleDamageSourceType 21 значений: Bear/Wolf/Fell/Drown/Radiation/Fire/Hunger/Thirst/…)}.
- rust:dmg:close → пустой маркер; rust:dmg:respawn → RespawnPacketData{respawnPointId:Integer?} (C2S-выбор точки).
- DamageMap (liiilillii_6 = lIIIlilliI) — клиентский справочник множителей Map<броня, Map<оружие, Double>> + карта по строениям/технике (IliillIliI = wipe-info урон) — НЕ сетевой.
- Fortnite Damage = только ЛОКАЛЬНЫЙ расчёт: пересечение трассера (серверная сущность, видна клиенту) с AABB чужого + урон ⌊|motion|×NBT damage⌋ (см. [[rustme-decompile-full]]).

**S2C каталог**: 92 канала, карта в json; 14 — подтверждённо пустые маркеры (CloseDeathScreen/EnableHud/CloseXxx/launch-пустоты). Ключевые: rust:player:state→PlayerStatePacketData, rust:met:update→UpdateMetabolismPacketData{metabolism:MetabolismStorage}, rust:item:pickup→ItemPickupNotificationPacketData (лут-история), rust:map:markers→UpdateMapMarkersPacketData, rust:sc:entryupd→ScoreboardUpdateEntriesPacketData, rust:vc:conninfo→VoiceChatConnectionInfoPacketData.

**Depth/WetWorld (разбор их пост-эффектов)**: ru.rustme.rendering.postfx (RenderTarget liiiiiilii_30 = ТОЛЬКО color RGBA8 текстура+FBO, БЕЗ depth вообще; BackgroundBlur liiiiiilii_29 + BlurTarget; FullscreenShader; MsaaFramebuffer). Главный FBO игры iIlIiIiIiI (iiliiiiiii_21): color = ТЕКСТУРА (iIlIIiIiI — её сэмплирует их blur как источник!), depth = renderbuffer (illIIiIiI). Готовый референс пайплайна: flush батчера → bind target → viewport → bind source tex → GL11.glDrawArrays(TRIANGLES,0,3) → композит в игру. Их шейдеры (assets/minecraft/shaders) depth НИГДЕ не сэмплируют. Вероятные причины провала WetWorld: (а) feedback loop — сэмплировали текстуру, аттачнутую к текущему FBO (0x8B8D), (б) swap слетал при пересоздании FBO (методы iIiliIiIII(II)/iiiliIiIII — вызывать swap заново после resize/пересоздания). Чистый путь: glBlitFramebuffer depth → СВОЯ текстура → сэмпл в отдельном проходе.

**Таймер**: главный цикл = gs.IllliilliI()V: очередь задач (ilIIllil Queue, poll → liIllilIiI.IIIiliIllI) → iiIiiilliI()V (тик: HUD liIIliliiI.IlliiiIliI, mc.llIIllIiII(F)) → Math.min → IIliiilliI()V (render: NativeAPI.protectionTick → astraea-payload с status/stage/error! отсылается через mod-NHPC каждый кадр) → IIiiIIiliI.IlllliIIil(F,J) синглтон (mc.IiIlllIiII(F)/IIiIllIiII(FJ)/iiiIllIiII(F)/lIIlllIiII()V). iiiIllIiII(F) = рендер-проход (постит iiiiiIiliI(partialTicks) в шину мода). Аналог Timer-объекта: лестница liliIiiliI → iIliIiiliI → IIiIIiiliI → lilliiiliI (полеIllIilil:Z, IIlliiiliI = enum 3-х режимов tick), создаётся НОВЫЙ на каждый тик — классический timerSpeed-patch ПОЛУ в форке не виден (fps-limit из настроек мода + 173.0 clamp в liilllIiII). Swing-гейт: 6 тиков (iIiillIII()F + Math.floorMod, getSinglePlayerAmortization).

**SilentAim цепочки**: рейкаст = lliIilliiI.IIiliiiiII(World, Entity, F) (уже известен), вызовы @3835/4466 в Minecraft. Полная атака = wrapper.IIiIIiIIiI.iiIiiIiilI(Entity) (lllIIiIiI-метод: логгер-глухой if lliilIiilI/Z, дистанция от IIIIiIliI-атрибута, криты 0.5/0.2/0.8, knockback 0.9, sendPacket через ВАНИЛЬНЫЙ netHandler lliIlIIIiI.llIlIllilI, attack-пакет llililIIiI = 4 int: entityId varint + 3 short). Silent-повороты: у C03/C06 (iliiilIIiI база) ПОЛЯ yaw/pitch public — правка перед отправкой без фасада.

**AccountManager**: профиль = gs.lllIllil:Object → iIiIlilIiI (UserProfile: 3 String поля iIiillIlI/IIiillIlI/lIiillIlI + GameProfile getter IiiiiiIllI() лениво fillProfileProperties; геттер строк IlllllillI/llllllillI/iiiiiiIllI/liiiiiIllI). gs.IllIiilliI() → возвращает его. CLI-ключи парсит ru.meproject.Main (main→llllIlIIII-статик holder: liliIIlill=--accessToken и т.д., передача в net.minecraft.client.main.Main → Session(username,uuid,token) ctor (String,String,String)). Смена аккаунта в игре = пересоздание UserProfile в gs.lllIllil + правка полей профиля игрока; НО protectionTick/attestation (lilIIiiliI, античит-сборка iIiIllIIiI/iIIIiIiIiI) может завязаться на исходный UUID — риск флага, тестировать только с тестового аккаунта.

**Водяной знак-сервер**: цепочка gs.IiIiIilliI() → lIIlIIliiI(ServerData) из iiIIllil:Object; заполняется iiIlIIliiI (GuiConnecting) из статик IliliiliII.liIiIiliII/lliiIiliII; писатель статик — вне дампа (клей лаунчера из CLI --server) → вероятно там адрес, не «Песочница»; проверка 1 инжектом (лог геттеров lIiilIIl/liiilIIl).

**Декомпиляция**: полный прогон 11052 классов уже есть (full_src 9977 + named_src 974, файл-индекс в file_class_index.json). driver v2 (decompile_rest.py) дописывает до 100% named-сурсов — но kolлизии NTFS внутри групп требуют per-class каталогов (задел в скрипте).
