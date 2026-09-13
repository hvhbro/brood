---
name: rustme-nametags-icons
description: NameTags-иконки хотбара (анализ 09-07, ждёт «делай» фазы 1) —
  RenderItem мода rustme/liiiliiIiI доступен из CheatIngame как поле lllIliil
  (iIIilIliII(stack,x,y)); хотбар форка = 6 слотов main-списка; у ЧУЖИХ игроков
  инвентарь НЕ синхронизируется сервером (пустой) — иконки только локального
  хотбара или «лут-история» чужих по entityId из дроп-пакета
metadata:
  node_type: memory
  type: project
  originSessionId: sess_77a97d83-960d-48a2-80ca-1280c4d65177
---

Анализ 2026-09-07 (чат «иконки предметов в NameTags»). Юзер просил ТОЛЬКО анализ — код не менять, ждёт явного «делай» (фаза-гейтинг [[rustme-phase-gating]]).

**РЕНДЕР ИКОНКИ — готовое API мода, всё проверено javap:**
- RenderItem = `rustme/liiiliiIiI` (Kotlin). Экземпляр = **public final поле `lllIliil` в GuiIngame `liIIliliiI`** → наш CheatIngame его наследует, доступно как `this.lllIliil` БЕЗ reflection (инициализируется из `gs.iIiIiilliI()`).
- Нарисовать предмет: `iIIilIliII(Lrustme/liIIIIIIiI;II)V` (stack, x, y). Внутри: isEmpty-чек → выбор 3D-модели `IilllIiliI.IlllIIlIil` (кэш Map<ключ,модель>) → бинд атласа предметов → depth 0.1 → GL-обёртка IiilIiiIiI. Тот же слой, что рисует HUD мода → совместим с нашим overlay-контекстом в CheatIngame (renderFrame). Контекст рендера внутри жёстко = ЛОКАЛЬНЫЙ игрок из gs — не мешает, модель выбирается по самому стеку.
- Готовый «слот хотбара» (translate + иконка + счётчик): `liIIliliiI.lIiiiiIliI(IIF,IIiIIiIIiI,ItemStack)V`.

**ИНВЕНТАРЬ ФОРКА (1.8-стиль, Rust-тематика):**
- InventoryPlayer = `rustme/iiIIIiIIiI`: main = `lllliIiII` (30 слотов), броня-список = `IliiIIiII` (7), offhand-список = `iiiiIIiII` (0); хотбар = main-слоты 0..5, размер = статик `IiilIIiilI()I` (=6), валидатор `lIlIIIiilI(I)Z`; текущий слот `iliiIIiII:I`; getCurrentItem = `lIiIIIiilI()`.
- `iIliiIiII:Lrustme/iiIIIiIIiI` (public final InventoryPlayer) есть у КАЖДОГО игрока — создаётся в ctor wrapper'а IIiIIiIIiI. Remote-игрок = `rustme/IiIililiiI` extends iiIililiiI (создаётся в net handler из GameProfile таб-снапшота IIIiillliI(UUID)).

**СТОПОР: у ЧУЖИХ игроков инвентарь ПУСТОЙ — сервер НЕ синхронизирует хотбар/руку чужих.** Перебор пакетных хендлеров iliilIliiI (главный) + lliIlIIIiI (клиентский мир-хендлер):
- Пакет `(entityId:short, ItemStack)` = `iIlllIIIiI` — это ДРОПНУТЫЕ предметы (спавн/удаление item-энтити через world IIIiliIIiI.lIiIlliilI(id,stack) → entity.IiiiiIIilI(stack)).
- Слотовый пакет (-1/-2) `iiIiIlIIiI` пишет ТОЛЬКО локальному игроку (в хендлере жёстко checkcast liIililiiI из gs).
- Named-канал ru.rustme.network.player.PlayerStatePacketData (rust:player:state) = researched/craft/skins/teaBoosts/entityId/handsDown — инвентаря нет. Equipment-пакета в форке НЕТ (предмет в руке чужого тоже не синхронится; SwingPacketData = только arc).
- Итог (09-07, касалось только хотбара): у чужих все 6 хотбарных слотов = пустой стек-синглтон liIIIIIIiI.IiiIlIlII (isEmpty=true).

**ОБНОВЛЕНО 09-08 (вечерняя сессия — S04 НАЙДЕН, «рука чужого не синхронится» было ошибкой):** equipment-пакет `IiililIIiI` (entityId varint + slot enum `iilliilliI` + ItemStack) — хендлер `iliilIliiI.IllIlIlliI` пишет `entity.iIiIliiilI(slot, stack)` в ЛЮБУЮ сущность. Синхронятся MainHand + Armor1..Armor7 (OffHand-геттер всегда EMPTY). Чтение чужого: `wrapper.IiliIiiilI(slot)` (MainHand → `inv.lIiIIIiilI()`, Armor → `inv.IliiIIiII.get(index)`), javap-проверено. НЕ синхронится полный хотбар (main 30). **GearESP РЕАЛИЗОВАН 08.09 (юзер дал «делай»)**: Esp.java v5 — иконки руки+брони над боксами через GameContext.resolveGear (RenderItem = gs.iIiIiilliI(), НЕ через наследуемое поле CheatIngame) + масштаб 6..14px от высоты бокса; гатчи подложки/glColor — в [[rustme-esp]]. **ТЕСТ ПРОВАЛЕН (иконки пустые — их RenderItem из overlay не рисует: RenderItemController-батчер + негативный кэш моделей)** → путь переписан на ItemIcons: свой шейдер uniform-UV сэмплирует ИХ блок-атлас, UV из ItemModelMesher→IBakedModel→Sprite; детали и вся цепочка в [[rustme-esp]] v5.1. DLL 23:32 ждёт теста.

**ВЕРДИКТ (выдан юзеру):**
- Фаза 1 (гарантированно работает): иконки ЛОКАЛЬНОГО хотбара в HUD / плашке своего ESP-бокса — `this.lllIliil.iIIilIliII(stack, x, y)` в renderFrame.
- Чужим доступна только «лут-история»: копить по entityId предметы из дроп-пакета iIlllIIIiI и показывать в плашке NameTag. Полный хотбар чужих = перехват/подделка каналов = РИСК БАНА (не предлагать).

**БОНУС-КАРТА КЛАССОВ (проверено дизасмом):**
- `rustme/lllIliIIiI` = EntityLivingBase-подобный (ОТДЕЛЬНАЯ ветка от Entity root IIlIIliIiI): поле инвентаря-интерфейса `IIiiiIlII:lIIIliIIiI`, held-slot `liiiiIlII:I`, getHeldItem `liiiiIIilI()`, setHeldItem `IiiiiIIilI(ItemStack)V`; 17 детей (мобы).
- Enum слотов экипировки = `iilliilliI` (MAINHAND = liilIliiiI). Wrapper `IIiIIiIIiI.IiliIiiilI(slot)`: MAINHAND → inv.getCurrentItem, вторая константа → EMPTY, остальные → inv.IliiIIiII.get(index).
- Мод-холлер настроек `llIIiIiIiI` поле `IliIIiIiI:I` = режим хотбара (0 = ванильный стиль).
- scan_db.json НЕ содержит named-классы ru/ (0 энтрей) — для них tools/dump_members.py напрямую по файлу класса.
- Дизасм-дампы сессии: tools/javap_tmp7/{renderitem,itemlayer,invplayer,otherplayer,guingame,nethandler,worldhandler,worldclient,livingbase,entityplayer,wrapper2,entityroot,holdpacket}.txt

Связано: [[rustme-esp]] (куда встраивать плашку), [[rustme-night-map]] (поля ESP/пинг), [[rustme-jni-defineclass-bypass]] (правила агента: без лямбд/Proxy), [[rustme-next-features]].

**Hotbar HUD РЕАЛИЗОВАН (09-10 23:52, DLL 82 кл) → v2 (09-11 10:02, DLL 86 кл, ждёт теста):** кастомный хотбар rockstar (IiIiIiIIi_Class170) адаптирован под Rust-форк: 6 слотов (не 9!), панель непрозрачная низ экрана. Цепочка (javap): player.iIliiIiII → InventoryPlayer (iiIIIiIIiI): main-список = lllliIiII (NonNullList extends AbstractList → List.get), selected = iIiiIIiII:I; stack damage = iliIIililI(); **maxDamage = Item (IillilIiiI).liillIII публичное поле** (пишется билдером liIiIiiIiI(F) с ldc 15.0f — Rust-прочность; IiillIII = maxStackSize, установлен через lliiIiiIiI(int)). Метрики rockstar 1:1: слоты шаг 21.5, иконка 16×16, lift = 0.25*selAnim+0.1*nearAnim (300мс), выбранный −12px вверх. Иконки — ItemIcons (родные texId+uvRect). НЕ перенесено из rockstar: XP-уровень, сердечки/еда/воздух (status bars), off-hand, кулдаун-затемнение предметов. **v2 фиксы (жалобы юзера): панель 186×(H-25..H+1)** — накрывает ваниль 182×22 (старая 141 торчала по бокам, ваниль видна); номера 8px и счётчик 7px ВНУТРИ слота (slotX+15-width, счётчик с тенью 0x66000000) — раньше налезали друг на друга; **auto-pick списка хотбара**: если main (lllliIiII) первые слоты пусты → пробуем iiiiIIiII (второй NonNullList); diag раз в 5с `pick=main|alt sel= counts=[...]`. **v3 ФИНАЛ (09-11 10:47, DLL 86 кл, ждёт теста) — НБ выше РАЗРЕШЁН дизасмом:** ВЫБРАННЫЙ СЛОТ = **iliiIIiII:I** (getCurrentItem `lIiIIIiilI()` читает iliiIIiII как индекс в main); **iIiiIIiII = МУСОР** (в логе sel=306/594 — из-за этого не было подсветки/подъёма выбранного). **Пустой стек = count 32767 (0x7FFF)** — непустые 1..N; раньше `count>0` пропускал пустые → «99+» вместо номеров 1-6 на пустых слотах. Геометрия выровнена по ВАНИЛИ (слоты x=W/2-91+i*20+1, y=H-22+3; панель 188×27 от x=W/2-94, y=H-25) — фиксирует «камень во втором слоте» (слоты теперь под игровыми позициями) и накрывает ваниль. diag `pick=/counts=[...]` остался (по нему проверять, доходят ли непустые стеки до иконок — жалоба «взял предметы, не отображаются»). Размеры списков из ctor подтверждены: main=withSize(30), IliiIIiII=withSize(7) (броня/экип), iiiiIIiII=withSize(без явной константы).

**v4 (09-11 11:25, DLL 88 кл, ждёт теста):** жалобы юзера: лишнее место после 6-го слота (панель 188 = запас под ваниль) и ваниль не скрыта. Решение: **хотбар форка глушится override'ом liIIliliiI.iillIIIliI(res) в CheatIngame** (public; читает кэш предмета в руке ililliil + счётчик апдейта llIlliil = рендер хотбара; при Hotbar.isActive() → return, иначе super) — добавлен static Hotbar.isActive(); панель КОМПАКТНАЯ 141 (6×21.5+12, slotStart=6) — ванильный 182-запас больше не нужен. Осторожно: если iillIIIliI рисует и другие HUD-элементы форка (не только хотбар) — при включённом Hotbar они пропадут вместе с ним; проверять по тесту.

**v6 (09-11 17:01, DLL 106 кл) — v5-свап был НЕ тот путь, НАСТОЯЩИЙ рисовальщик = виджет HotbarOverlay:** юзер подтвердил «HotBar теперь работает». Хотбар форка рисует UI-виджет **HotbarOverlay (rustme.lilIililiI)** из **OverlayListener.overlays** (static ArrayList на rustme.liiiilIliI, синглтон iiIIIilll) — тот же список, что и маски (см. [[rustme-antioverlay]]). Подавление: **utils/etc/Overlays.hide(ctx, lilIililiI)** — удаление инстансов из списка через ctx.runOnMainThread (рендер-поток итерирует список → CME без него), restore при выключении; идемпотентно (count==0 → skip). iillliil-свап (v5) оставлен как страховка. УТИЛИТА Overlays.java — общий механизм подавления ЛЮБЫХ HUD-оверлеев форка по классу виджета.
- **ГАТЧ СПАМА:** Overlays.resolve не ставил resolved=true в success-ветке → «resolved (21 widgets)» 858 раз в логе. Флаг ставить ДО return true.
- **Патроны в обойме (НЕ РАБОТАЕТ, ждёт diag):** ammoOf — stack.llIiIililI()→tag → IIlliIlilI("rustme_subdata")→sub → gun = getString("ammo_type") non-empty → count = getInteger("ammo") / getString("ammo") fallback; рисуется 7px в правом нижнем углу слота (красный при 0). Юзер: не отображается → добавлена diag `ammo diag: type=... int=... str=...` раз в 5с — следующий лог покажет реальный ключ/тип NBT.