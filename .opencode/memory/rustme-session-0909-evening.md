---
name: rustme-session-0909-evening
description: Сводка вечера 09-09 — AimBot/JumpCircle/InstantUse/Dormant реализованы, менюбMerge друга, PacketFly отложен
metadata:
  type: project
---

СЕССИЯ 09-09 (вечер/ночь) — ИТОГИ:

**Реализованные модули (все в jni/agent/src/modules/impl/):**
- **AimBot.java** — полный порт конкурента: ammo-классификатор по displayName оружия (см. [[rustme-chtdump-competitor]] таблицу), ring-buffer 8 снимков, predict k = flightTimeCoeff(dist,ammo) × elapsedSec(tracking) × pingComp, к л ампы [0..6]/[0..2]/lead5бл. Y-predict исправлен: aim.y = target.y + boneOffset БЕЗ ddy-экстраполяции (ddy содержит гравитацию/прыжки → aim улетал вверх). Поворот через Entity.iiiiIllIII(FF)V = setRotation-метод (НЕ field-write — тот обновляет prev+curr синхронно). Toggle R (menu bind). Настройки: FOV/PingComp/Prediction/DrawFOV.
- **JumpCircle.java** — расширяющийся круг при прыжке (порт kimiko). Детект: onGround→air + motionY>0.02. Рендер: 3D-кольцо (48 сегментов, двусторонний quad) через projectToScreen. Цвет: радуга HSV или градиент cyan/pink. Настройки: Время/Размер/Эластичность/Цвет. Рендер в CheatHud ПЕРЕД Esp.render (чистая камера).
- **InstantUse.java** — lguse на usingItem true→false при dur≥800мс (usingStart гейт). shuse УДАЛЁН (сервер открывает UI interactable — спальник/сундук). Helper: liliiilliI.IlIIIIIIIl(player=SP, channel, payload).
- **Dormant ESP** (встроен в Esp.java): dormPos HashMap<entityId,{x,y,z,time}>, призрак рисуется <4.48с после ухода из playerEntities, жёлтый бокс + "DORMANT X.Xs".

**PacketFly отложен** (см. [[rustme-packetfly-analysis]]): юзера флагал сервер, код в backup_packetfly/ не компилится, чистая DLL без него.

**Меню-merge друга (a7ec5a57):** agent-часть влита (см. [[rustme-menu-tracers-merge]]): Tracers/MenuModule RSHIFT/CheatMenuScreen Class215-реплика/GlassGrab blur/IconRender+IconData. Наша локальная работа сохранена.

**Ключевые гатчи сессии:**
- wrapDeg возвращает double → явный (float) cast обязателен
- projectToScreen возвращает double → НЕ float w = double (lossy)
- IiiIIiliI/iiIIIiliI — ПОЛЯ (double/boolean), не методы — через reflection walk-up
- Поле iIliiIiII (InventoryPlayer) объявлено в WRAPPER IIiIIiIIiI не в SP — иерархический подъём
- Суффикс _N в декомпиле (iiliililii_4) = CFR-артефакт дублей; настоящее имя в Kotlin metadata d2=
- shuse = interactable-interact (сервер открывает UI!), НЕ использовать для форс-использования
- JumpCircle в srcs.txt нужен: find jni/agent/src -name "*.java" > tools/srcs.txt после добавления новых файлов

**Состояние:** DLL 58 классов (23:23), invoke чист, все модули зарегистрированы в RustClient. JumpCircle/AimBot/InstantUse/Dormant ждут теста юзера.
