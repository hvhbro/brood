---
name: rustme-motion-field-map
description: КАРТА MOTION-ПОЛЕЙ Entity root — ZNANIA 5/14.1
  («iIIlIiliI/IIiIIiliI/IiiIIiliI = x/y/z») ОШИБОЧНА; истина iIIlIiliI=motionY,
  IIiIIiliI=motionX, IiiIIiliI=motionZ (доказано jump 0.42 + спринт-бустом);
  из-за этого Strafe «взлетал» — писал в motionY
metadata:
  node_type: memory
  type: project
  originSessionId: sess_233cc9ce-5694-4faa-a88b-3387560bfb88
---

**ЛОГИЧЕСКАЯ ОШИБКА В ZNANIA 5/14.1 (не переопровергать без «делай»): порядок motion-полей в списке был НЕ x/y/z.**

**ИСТИННАЯ КАРТА (проверено дизасмом двух независимых ванильных мест, liIlIliIiI = EntityPlayer):**
- `iIIlIiliI:D` = **motionY** — метод прыжка `ililIiiilI()`: `ldc 0.42f; f2d; putfield iIIlIiliI` (вертикаль!)
- `IIiIIiliI:D` = **motionX** — там же спринт-буст прыжка: `IIiIIiliI −= sin(yaw_rad)·0.2` (ваниль motionX −= sin·0.2)
- `IiiIIiliI:D` = **motionZ** — там же: `IiiIIiliI += cos(yaw_rad)·0.2` (ваниль motionZ += cos·0.2)
- Кросс-проверка: `lllIlllIII(DDD)` = addVelocity(x,y,z): param1→IIiIIiliI, param2→iIIlIiliI, param3→IiiIIiliI — согласуется (X,Y,Z).
- Motion-поля пишутся/читаются RAW (getfield/putfield в addToXYZ, БЕЗ XOR-хелпера) — XOR-кодированы только pos/prev/lastTick. Reflection setDouble на motion валиден.

**ПОСЛЕДСТВИЯ (баг юзера 09-10 «Strafe взлетает»):** jni/agent/src/modules/impl/Strafe.java и utils/etc/GameContext.java (motionXField/motionYField/motionZField, resolve @303-305) держали карту x=iIIlIiliI/z=IIiIIiliI/y=IiiIIiliI → Strafe каждый 1мс-тик писал `−sin(rad)·speed` в motionY (гравитация перезаписывалась → полёт), «Z» попадал в X. JumpCircle.java:92 читал ctx.motionYField как вертикальную скорость — фактически читал motionZ (маскировалось onGround-чеком).

**ФИКС (когда будет «делай»):** поменять местами: motionX=IIiIIiliI, motionY=iIIlIiliI, motionZ=IiiIIiliI в GameContext и в Strafe (walkUp-имена) — JumpCircle починится сам через ctx. Формула направления Strafe (motionX=−sin(rad)·speed, motionZ=cos(rad)·speed) верна для ванильной конвенции при strafe==0/forward>0.

Связано: [[rustme-session-0910-night]], [[rustme-night-map]], [[rustme-phase-gating]].
