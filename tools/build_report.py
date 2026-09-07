#!/usr/bin/env python3
"""Build the full class report: report.md (human) + classes.csv (machine).
Classifies all 9977 obfuscated classes by hierarchy, strings, sizes, known
anchors; flags important classes for future JNI work."""
import json
import os
import re
from collections import defaultdict

OUT = r'C:\Users\Admin\Desktop\rustme\tools\class_report'
corpus = json.load(open(os.path.join(OUT, 'corpus.json'), encoding='utf-8'))

# --- known anchors (established during this analysis) ---
ANCHORS = {
 'rustme/liIlIliIiI': ('EntityPlayer (vanilla)', 'Базовый класс игрока: 157 методов, конструктор (World, GameProfile), fields speed.'),
 'rustme/IIlIIliIiI': ('Entity (root)', 'Корень всех сущностей: 229 методов, поле lliililiI:Z = isSneaking, iIIlIiliI/IIiIIiliI/IiiIIiliI = motion D x/y/z.'),
 'rustme/IIiIIiIIiI': ('EntityPlayerSP wrapper (mod)', 'Клиентский игрок мода: extends EntityPlayer, implements IllIlilliI. Inventory=iIliiIiII. Методы-обёртки над ванилью; travel вызывает liilIIlIII (moveRelative).'),
 'rustme/liIililiiI': ('EntityPlayerSP (mod-patched)', 'Патченный клиентский игрок: extends iiIililiiI (AbstractClientPlayer). travel override iIilliiilI, onLivingUpdate-подобные liIIIiiilI.'),
 'rustme/iiIililiiI': ('AbstractClientPlayer (vanilla, abstract)', 'abstract extends IIiIIiIIiI; поля lliIiiil/iiIIiiil float.'),
 'rustme/liIlliiliI': ('PlayerSneakEvent (mod)', 'Событие sneak: поле lIliIIlil=IIiIIiIIiI (player), iIiiIIlil:Z (sneaking). getSneaking/setSneaking.'),
 'rustme/iIIlliiliI': ('PlayerSprintEvent (mod)', 'Событие спринта, аналогично.'),
 'rustme/lIiililliI': ('ModPlayer facade (mod)', 'Kotlin-обёртка игрока: getSneaking/setSneaking/elytraFlying; поля F/D/Z.'),
 'rustme/llIlliiliI': ('Rotation container', 'liiIlIiIil(F)/IiIIlIiIil(F)/iIiIlIiIil(F) — setPitch/Yaw/Roll.'),
 'rustme/lillilIIiI': ('PlayerCapabilities', 'isFlying Z, walkSpeed F (iiiiliilI), flySpeed F (iIiiliilI); get/set: IIiiIliIlI()/iIiiIliIlI(), IliiIliIlI()/lIllIiIIlI.'),
 'rustme/llliiIIIiI': ('WorldServer?', '64 метода; проверить.'),
 'rustme/IIlllIlIiI': ('World (vanilla)', '229 методов; IillillllI(DDDDDD[I) = rayTraceBlocks; IIIiiiiiil.'),
 'rustme/lliililIiI': ('Vec3 (vanilla)', 'поля liiIIlIlI:D, IIiIIlIlI:D (+third) — вектор.'),
 'rustme/iiIIIiIIiI': ('InventoryPlayer (vanilla)', 'slot-методы: IIIiIIiilI(I), liliIIiilI(I...), getCurrentItem.'),
 'rustme/IilIiIiIiI': ('KeyBinding category enum', 'key.categories.*.'),
 'rustme/llIiIiIiI': ('RustMe client settings (mod)', 'Много Z-полей — флаги настроек чит-меню?'),
 'rustme/IilllIiIiI': ('Static helpers (mod)', 'iIillIIIII(player)=max eff. level (depth strider?), lIiIlIIIII(player)Z.'),
 'rustme/liiililIiI': ('MathHelper', 'IiiIIlillI(F)F=cos, liliIlillI(F)F=sin, lIlIIlillI(F)F=sqrt, iIiIIlillI(D)F, iiIIIlillI(F)F=clamp.'),
 'rustme/lllliIiliI': ('ModelPlayer/render hook', 'transformIfSneaking строка.'),
 'rustme/iilliIliiI': ('GameSettings', '91 поле, 120 методов, ссылки на KeyBinding.'),
 'rustme/lIliIiiIiI': ('Minecraft (main class)', 'ctor(GameSettings, ...), 55 методов: runTick/renderWorld.'),
 'rustme/iilliIliiI': ('GameSettings', '91 поле (keybinds/options), 120 методов.'),
 'rustme/lillilIIiI': ('PlayerCapabilities', 'walkSpeed iiiiliilI:F, flySpeed iIiiliilI:F, isFlying llllIiilI:Z.'),
 'rustme/llIiIiIiI': ('RustMe client settings (mod)', 'Много Z-флагов настроек (чит-меню?).'),
 'rustme/iiIIIiIIiI': ('InventoryPlayer', 'slots; getCurrentItem; методы (I).ItemStack.'),
 'rustme/lIliIiiIiI-нет': ('', ''),
 'rustme/IillilIiiI': ('Block registry/base (79 подклассов)', 'Ванильный Block: methods getLocalizedName, collision.'),
 'rustme/llliiIIIiI': ('TileEntity (55 подклассов)', 'доступ (I)TE, multimap-чанки.'),
 'rustme/IliIIiIliI': ('RustMe UI base (mod)', 'AnimatableColor/DrawStyle строки.'),
 'rustme/lIlliIliiI': ('RustMe packet base', 'FFFLIIlIIliIiI;String и т.д.'),
 'rustme/liiililIiI': ('MathHelper', 'cos/sin/sqrt/clamp обёртки.'),
 'rustme/lllliIiliI': ('ModelPlayer (render)', 'transformIfSneaking.'),
 'rustme/iIiililliI': ('ModEntityType (mod)', 'enum-подобный: типы сущностей.'),
 'rustme/llIlliiliI': ('Rotation holder', 'pitch/yaw/roll.'),
 'rustme/IlilIiIIiI': ('SharedMonsterAttributes', 'поле generic.movementSpeed; getAttributeByName.'),
 'rustme/iIIlliiliI': ('PlayerSprintEvent (mod)', 'extends IIiIIiIIiI; Z-флаг.'),
 'rustme/liIlliiliI': ('PlayerSneakEvent (mod)', 'player + sneaking Z.'),
 'rustme/lIiililliI': ('ModPlayer facade', 'sneaking/elytraFlying обёртки.'),
 'rustme/lllliIliiI': ('Модель игрока (render)', 'render(F,V,...) код 833.'),
 'rustme/iiIililiiI': ('AbstractClientPlayer', 'abstract extends SP-wrapper base.'),
 'rustme/liIililiiI': ('EntityPlayerSP (mod-patched)', 'travel override; onLivingUpdatezone.'),
 'rustme/IIiIIiIIiI': ('EntityPlayerSP wrapper', 'base player wrapper (Inventory поле).'),

 'rustme/iIIiiIiIiI': ('??? (со Minecraft ctor)', 'Второй аргумент ctor Minecraft.'),
 'rustme/iIlIiiIIiI': ('Enum/particle?', 'статик lilIiiiII.'),
 'rustme/IillilIiiI': ('SoundHandler-ish', '127 методов.'),
}

# --- string-signature classification ---
SIGNATURES = [
  ('Network/packet', ['writeVarInt', 'readVarInt']),
  ('GUI/Screen', ['gui', 'button', 'drawScreen']),
  ('Container/Slot', ['putStack', 'getStack']),
  ('Item/ItemStack', ['getUnlocalizedName', 'itemStack']),
  ('Block', ['material', 'blockIcon']),
  ('Rendering/shader', ['glShader', 'GL20', 'ARB']),
  ('Model rendering', ['setRotationAngle', 'cubeList']),
  ('Texture', ['textures/']),
  ('NBT', ['writeTag', 'NBTTagCompound']),
  ('Kotlin internals', ['kotlin/']),
  ('Sounds', ['sounds/', 'SoundEvent']),
  ('Particles', ['particle']),
  ('World gen', ['ChunkProvider', 'Biome']),
  ('Networking client', ['ClientHandler', 'NetworkManager']),
  ('Recipes', ['recipe']),
  ('Achievements', ['achievement']),
  ('Scoreboard', ['score']),
  ('Boss', ['bossBar']),
  ('Chat', ['IChatComponent', 'addChatMessage']),
]

def classify(info):
    text = ' '.join(info['strings'])[:4000]
    tags = []
    for label, keys in SIGNATURES:
        if any(k in text for k in keys):
            tags.append(label)
    return tags

rows = []
hub_children = defaultdict(list)
for f, info in corpus.items():
    t = info['this']
    hub_children[info['super']].append(t)
    rows.append({
        'file': f,
        'class': t,
        'super': info['super'],
        'ifaces': ';'.join(info['ifaces']),
        'n_methods': len(info['methods']),
        'n_fields': len(info['fields']),
        'size': info['size'],
        'tags': ';'.join(classify(info)),
        'anchor': ANCHORS.get(t, ('', ''))[0],
        'note': ANCHORS.get(t, ('', ''))[1],
    })

# write CSV
import csv
with open(os.path.join(OUT, 'classes.csv'), 'w', newline='', encoding='utf-8') as fp:
    w = csv.DictWriter(fp, fieldnames=list(rows[0].keys()))
    w.writeheader()
    w.writerows(rows)

# top hubs
top = sorted(hub_children.items(), key=lambda kv: -len(kv[1]))[:25]
with open(os.path.join(OUT, 'hubs.txt'), 'w', encoding='utf-8') as fp:
    for sup, kids in top:
        fp.write(f'{sup}: {len(kids)} subclasses\n')

print(f'CSV written: {len(rows)} classes')
print('top hubs:')
for sup, kids in top[:12]:
    print(f'  {sup}: {len(kids)}')
