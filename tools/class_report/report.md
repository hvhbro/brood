# RustMe class map (dump/minecraft/rustme, 9977 классов)

Версия игры: **Minecraft 1.12.2 + OptiFine**, Java 8 (class major 52). 
Все ванильные классы переименованы протектором в `rustme/IIII...`; 
мод-классы `ru/rustme/*` и `ru/meproject/rustme/*` сохранили читаемые имена.

Полный построчный справочник: `classes.csv` (file, class, super, ifaces, methods, fields, tags, anchor, note).
Иерархия: `hubs.txt`.

## Ключевые классы (якоря)

| Runtime class | Файл | Назначение |
|---|---|---|
| `rustme/IIiIIiIIiI` | `IIiIIiIIiI(91).class` | **EntityPlayerSP wrapper** — base player wrapper (Inventory поле). |
| `rustme/IIlIIliIiI` | `IIlIIliIiI(23).class` | **Entity (root)** — Корень всех сущностей: 229 методов, поле lliililiI:Z = isSneaking, iIIlIiliI/IIiIIiliI/IiiIIiliI = motion D x/y/z. |
| `rustme/IIlllIlIiI` | `IIlllIlIiI(15).class` | **World (vanilla)** — 229 методов; IillillllI(DDDDDD[I) = rayTraceBlocks; IIIiiiiiil. |
| `rustme/IilIiIiIiI` | `IilIiIiIiI(21).class` | **KeyBinding category enum** — key.categories.*. |
| `rustme/IillilIiiI` | `IillilIiiI(1).class` | **SoundHandler-ish** — 127 методов. |
| `rustme/IilllIiIiI` | `IilllIiIiI(5).class` | **Static helpers (mod)** — iIillIIIII(player)=max eff. level (depth strider?), lIiIlIIIII(player)Z. |
| `rustme/IliIIiIliI` | `IliIIiIliI(45).class` | **RustMe UI base (mod)** — AnimatableColor/DrawStyle строки. |
| `rustme/IlilIiIIiI` | `IlilIiIIiI(21).class` | **SharedMonsterAttributes** — поле generic.movementSpeed; getAttributeByName. |
| `rustme/iIIiiIiIiI` | `iIIiiIiIiI(38).class` | **??? (со Minecraft ctor)** — Второй аргумент ctor Minecraft. |
| `rustme/iIIlliiliI` | `iIIlliiliI(6).class` | **PlayerSprintEvent (mod)** — extends IIiIIiIIiI; Z-флаг. |
| `rustme/iIiililliI` | `iIiililliI(2).class` | **ModEntityType (mod)** — enum-подобный: типы сущностей. |
| `rustme/iIlIiiIIiI` | `iIlIiiIIiI(38).class` | **Enum/particle?** — статик lilIiiiII. |
| `rustme/iiIIIiIIiI` | `iiIIIiIIiI(92).class` | **InventoryPlayer** — slots; getCurrentItem; методы (I).ItemStack. |
| `rustme/iiIililiiI` | `iiIililiiI(4).class` | **AbstractClientPlayer** — abstract extends SP-wrapper base. |
| `rustme/iilliIliiI` | `iilliIliiI(8).class` | **GameSettings** — 91 поле (keybinds/options), 120 методов. |
| `rustme/lIiililliI` | `lIiililliI(1).class` | **ModPlayer facade** — sneaking/elytraFlying обёртки. |
| `rustme/lIliIiiIiI` | `lIliIiiIiI(5).class` | **Minecraft (main class)** — ctor(GameSettings, ...), 55 методов: runTick/renderWorld. |
| `rustme/lIlliIliiI` | `lIlliIliiI(5).class` | **RustMe packet base** — FFFLIIlIIliIiI;String и т.д. |
| `rustme/liIililiiI` | `liIililiiI(2).class` | **EntityPlayerSP (mod-patched)** — travel override; onLivingUpdatezone. |
| `rustme/liIlIliIiI` | `liIlIliIiI(10).class` | **EntityPlayer (vanilla)** — Базовый класс игрока: 157 методов, конструктор (World, GameProfile), fields speed. |
| `rustme/liIlliiliI` | `liIlliiliI(2).class` | **PlayerSneakEvent (mod)** — player + sneaking Z. |
| `rustme/liiililIiI` | `liiililIiI(16).class` | **MathHelper** — cos/sin/sqrt/clamp обёртки. |
| `rustme/lillilIIiI` | `lillilIIiI(8).class` | **PlayerCapabilities** — walkSpeed iiiiliilI:F, flySpeed iIiiliilI:F, isFlying llllIiilI:Z. |
| `rustme/llIlliiliI` | `llIlliiliI(1).class` | **Rotation holder** — pitch/yaw/roll. |
| `rustme/lliililIiI` | `lliililIiI(8).class` | **Vec3 (vanilla)** — поля liiIIlIlI:D, IIiIIlIlI:D (+third) — вектор. |
| `rustme/llliiIIIiI` | `llliiIIIiI(12).class` | **TileEntity (55 подклассов)** — доступ (I)TE, multimap-чанки. |
| `rustme/lllliIiliI` | `lllliIiliI(2).class` | **ModelPlayer (render)** — transformIfSneaking. |
| `rustme/lllliIliiI` | `lllliIliiI(2).class` | **Модель игрока (render)** — render(F,V,...) код 833. |

## Sneak / движение (для no slow sneak)

- `rustme/lillilIIiI` — PlayerCapabilities: walkSpeed `iiiiliilI:F`, flySpeed `iIiiliilI:F`, isFlying `llllIiilI:Z`.
- `rustme/liIlIliIiI` (EntityPlayer) `IililiiilI(FFF)` = **travel**; наземная ветка:
  - `lliililiI:Z` (isSneaking, поле Entity) проверяется в @682: 
    `if (sneaking) f7 = getSpeed() * f6; else f7 = speedField(0.02)` где `f6 = 0.16277136F/(slipperiness^3)`.
  - `iIIIIiiilI()F` геттер возвращает `liiliIliI:F` — «speed»-поле игрока.
  - Установщик `liiiliilII(F)` вызывается из `rustme/IIiIIiIIiI.liIIIiiilI()` (клиентский onLivingUpdate):
    @168-194: `if (iiiilIlIII() /*isSneaking*/) IIIIiIliI = IIIIiIliI + 0.3 * IliIiIiII`.
- `rustme/liIililiiI` (mod SP) переопределяет travel (`iIilliiilI`) и onLivingUpdate.
- `rustme/liIlliiliI` PlayerSneakEvent кидается из `rustme/liIililiiI`/`liIliilliI` (Kotlin bus).

**Вывод для no slow sneak**: клиент считает скорость в `liIIIiiilI()` 
(EntityPlayerSP.onLivingUpdate-подобный метод в `rustme/IIiIIiIIiI`): при isSneaking
добавляется sneak-поправка `+0.3 * IliIiIiII` к `IIIIiIliI:F` (friction speed) и
затем `liiiliilII(F)` (setSpeed) это фиксирует. Точка вмешательства: перехват/
компенсация в `liIIIiiilI()` или возврат из `iiiilIlIII()` (isSneaking)=false
только для расчёта скорости.

## Методика поиска нужных классов в будущем

1. `tools/class_report/classes.csv` — фильтр по `tags`/`anchor`/`super`.
2. Строки-маркеры: текстуры (`textures/entity/...`), регистры (`minecraft:`), 
   чат-ключи, `key.categories.*`, звуки, `NBTTagCompound`, пакеты `rustme:...`.
3. Иерархия: `hubs.txt` (Block=79 детей, Inventory и т.д.).
4. javap-дизасм: скопировать класс в `tools/class_report/key_classes/rustme/`
   и выполнить `javap -p -c -cp . rustme.<Name>`.
