---
name: rustme-custom-time
description: Custom Time (клиентское время суток) — World.illlillllI() →
  WorldInfo.liIllIlIiI → setWorldTime iIlilIlllI(J)V (пара к liillIlllI()J с
  поля iIiIllllI:J); apply каждый TickEvent (сервер перетирает TimeUpdate);
  пресеты пишут слайдер Time 0..24000
metadata:
  node_type: memory
  type: project
  originSessionId: sess_108ab9bb-329b-4492-a6b9-07aa20973a59
---

Custom Time (09-11, DLL 13:04, 93 кл, ждёт теста). Модуль «Custom Time» (Visuals): пресеты-чипы Day/Sunset/Night/Midnight/Sunrise/Custom + слайдер Time 0..24000 (step 50); клик по чипу пишет слайдер (syncPreset по смене индекса, Custom не трогает), модуль применяет слайдер каждый тик.

**ЦЕПОЧКА (javap-верифицировано 09-11):** `rustme.IIlllIlIiI` (World) → `illlillllI()Lrustme/liIllIlIiI;` (getWorldInfo; в World поле `illlllllI` — единственное этого типа) → **setWorldTime = `iIlilIlllI(J)V`** класса WorldInfo `rustme/liIllIlIiI`. Пара проверена дизасмом: геттер `liillIlllI()J` читает то же поле `iIiIllllI:J`, и именно его зовут `World.iIiilllllI(F)` = getCelestialAngle (солнце/луна) и `IIilIllllI()` = calculateSkylightSubtracted (небесный свет) → время сразу управляет небом и освещением. WorldInfo-класс найден сигнатурным сканом: ≥3×(J)V + ≥3×()J методы (set/get WorldTime/TotalTime/RainTime/ThunderTime...).

**ГАТЧИ:** TickEvent летит ~1мс (не 20 тиков) — применяем каждый тик без троттлинга (серверский TimeUpdate перетирает поле; 2 reflection-вызова/мс — копейки). worldInfo инстанс берём ЗАНОВО каждый тик (мир меняется при респавне); при world==null сбрасываем resolvedOk (новый classloader). resolve без sticky-флага: тихий ретрай, пока мир не загрузится. Module.onTick НИКТО не вызывает (мёртвый хук в базе!) — тики только через EventBus.subscribe(TickEvent, анонимный Listener) как AntiOverlay/MenuModule.

Связано: [[rustme-next-features]] (статус фич), [[rustme-ambience]] (Visuals-сосед).
