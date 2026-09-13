---
name: rustme-night-map
description: Ночная карта 9977 классов дампа — ESP-поля, камера, пинг, FOV,
  шейдеры, сеть (все проверено javap/дизасмом 09-07)
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d7a7ffa0-7b84-4d30-b7d0-5ba21843fb76
---

Ночная сессия 2026-09-07: полный анализ дампа (scan_all.py → scan_db.json 9977/9977, class_map_full.csv, named_classes.txt=715 читаемых ru/* классов). Детали в ZNANIA.md раздел 14.

Ключевое (дизасм-проверено):
- Entity root IIlIIliIiI: pos X/Y/Z = IlilIiliI/lIllIiliI/IiiililiI:D (геттеры IlIiillIII()/liiiIllIII()/lIilillIII()); prev = IIiIliliI/iiiIIiliI/IIilIiliI (геттеры IiilillIII()/lliilIlIII()/lilllIlIII()); lastTick = liiIliliI/ilIlIiliI/lIlililiI (IlilillIII()/lIiiIllIII()/IliIlIlIII()); yaw=IlIlIiliI:F (IIiIillIII()), pitch=IilIIiliI:F (iilIIIlIII()), onGround=iiIIIiliI:Z, eye-height=iliilIiilI()=width*0.85, width=lilililiI:F. ВСЕ координатные double/float XOR-закодированы (хелпер llIiIllIII(D)D с 2×AtomicLong-ключами), геттеры декодируют сами.
- AABB=ilIlIilIiI: lIiIilIlI=minX, iiiIilIlI=maxX, IiiIilIlI=minY, liiIilIlI=maxY, iIiIilIlI=minZ, IIiIilIlI=maxZ; getBoundingBox=iiIlIIlIII().
- Камера/проекция: lliIilliiI (статик FloatBuffer матриц modelview/projection + viewport + gluUnProject; поля-вектора направления IIIlIlIl/liIlIlIl/IlIlIlIl/llilIlIl/iiIlIlIl). RenderManager=iiiliiiIiI (renderPos llIiIlll/iiliIlll/iIiIIlll, renderYaw=liliIlll). CamPos-статики=IlIIliiIiI.iiIliliiI/lIIliliiI/IlililiiI (пишутся в RenderGlobal llIlIiiIiI.IlIliIliII).
- Пинг РАЗГАДКА: снет-хендлер iliilIliiI (gs.Illlllil→liIililiiI.lIlilIIl→cast) держит Map<UUID,iiIilIliiI> IiIllIIl; S34-хендлер lliIillliI(illlilIIiI) на UPDATE_LATENCY пишет live.iIliIliIlI() в снапшот.llIIillliI(int); живой пинг = снапшот.illIillliI() (поле lIlllIIl). liIIillliI()=другое поле IIlllIIl (Tab-only, ловушка 13.2). Коллекция = ilIIlIlliI(), поиск = IIIiillliI(UUID)/lIIiillliI(name).
- FOV: ЗАХВАЧЕННАЯ PROJ мода (lliIilliiI.lIIlIlIl) показала fovY=90° + infinite-far (near=0.05, far=∞) — рабочее значение. ⚠ gs.iIiiIilliI() в рантайме даёт 0.16 — это НЕ fov (ночная гипотеза опровергнута тестом, ZNANIA 14.2→14.12 исправлена); holder IlillilIiI.liIIiillI не разведён, гамма-мода = holder.IiIIiillI (sync в gs.IilIllil).
- lIlliIliiI — НЕ сетевой пакет (база эффектов/частиц, 44 наследника). Регистры: предметы=iIliliiiII (66), оружие=IIliIiIiII (58), блоки=iiIlIiIIiI (253 полей).
- Мод-иерархии: widget-база IlilIIIliI (164 наследника), Gui iIlililiiI (99), GuiScreen IlIlliliiI (79), RustMe UI IliIIiIliI (81).
- Шейдеры: загрузчики найдены (iIllIIiliI=ui_batch, iiIlIIiliI=terrain, llIIiIiliI=armor, liIIIIiliI/iiIIIIiliI=blur/zone_glow post, IllliIiliI/IlliIIiliI=scene/item_icon, lliIIIiliI=sprite_renderer; компилятор IiiliIiIiI/liiliIiIiI). GL-обёртка мода=IiilIiiIiI.
- Сеть: named-классы ru/rustme/network/* + ru.meproject.rustme.vanilla.network.* (kotlinx.serialization, каналы XxxPayloadChannels), voicechat UDP зашифрован; admin-каналы (rust:admin:spawn/give) — риск бана.

**Why:** эти имена — рабочие якоря для ESP-модуля и дальнейшего реверса; потеря = повторный многочасовой анализ.

**How to apply:** при кодинге ESP/пинга брать имена отсюда или из ZNANIA 14, помечать новое как «проверено» только после javap. Связано: [[rustme-jni-offset-recovery]], [[rustme-msdf-font]], [[rustme-phase-gating]].
