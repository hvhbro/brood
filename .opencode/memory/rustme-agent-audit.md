---
name: rustme-agent-audit
description: Полный аудит agent-проекта 09-09/10 (31 java-файл, 6667 строк, юзер «проверь весь код») — критические баги:
  AutoSprint залипающий спринт при выключении (onDisable пуст + onTick гейт isState), NoSlow молчаливый
  ранний return при частичном резолве, Esp камера prev-геттеры vs боксы lastTick (рассинхрон) + 30-строчный
  дубль updateCamera, GuiScale кэш навсегда, FullBright static gammaDefault протухает, мёртвые поля;
  NoSlow спам-логи удалены (DLL 21:08, 54 класса)
metadata:
  node_type: memory
  type: project
---

Аудит 09-09/10 (юзер: «проверь все классы всю цепочку»; 31 java-файл / 6667 строк прочитано; код по итогам НЕ менялся — отчёт в чат; позже частично перекрыт откатом/перереализацией [[rustme-night-s2c-catalog]]).

**КРИТИЧЕСКИЕ:**
1. **AutoSprint залипающий спринт**: onDisable() ПУСТ (комментарий «onTick отпустит при след. проходе»), но подписка = `if (isState()) onTick(event)` → после выключения onTick не зовётся, lastWritten=true остаётся, pressed=true держится навсегда (до перезахода в мир). ФИКС при возврате: forceSprintKey(false) в onDisable.
2. **NoSlow молчаливый ранний return**: resolveExtra() имеет свой try/catch; при падении modGetUID==null → тик молча выходит КАЖДЫЙ раз (`if (modGetUID == null) return`) — модуль мёртв без диагностики (speedAttr не сбрасывается, resolveExtra не ретраится до смены игрока). ФИКС: ретраить resolveExtra с логом.
3. **Esp камера рассинхрон + дубль**: чужие боксы интерполируются по lastTick (IlilillIII/lIiiIllIII/IliIlIlIII, фикс плавности), но камера локального игрока в ОБОИХ путях (render @146-148 и updateCamera @491-493) — по prev (IiilillIII/lliilIlIII/lilllIlIII) → микро-сдвиг боксов относительно камеры. updateCamera = 30-строчный ДУБЛЬ камеры из render(), уже разошлись (prev vs lastTick). ФИКС: камеру на lastTick + один общий метод.
4. **GuiScale кэш навсегда**: set() кэширует, get() игнорирует ctx.guiScale при cached>0 → смена разрешения/guiScale оставляет шрифты/иконки/блюр на старом масштабе (GlassGrab/IconRender/CustomFont юзают). CheatIngame обновляет ctx.guiScale каждый кадр впустую.
5. **FullBright static gammaDefault**: onDisable восстанавливает в СТАРЫЙ нод — после смены мира settingsObj пересоздаётся, новая гамма остаётся 10.0 навсегда. Резолвить по текущему ctx.settingsObj.

**СРЕДНИЕ/МЕЛКИЕ:**
- GameContext.resolveMovementAttributes() + modApplyMethod/modRemoveMethod/modClass — мёртвые после удаления Strafe (NoSlow resolveExtra резолвит сам; stale-комментарий в NoSlow про resolveMovementAttributes оставался до чистки логов).
- Мёртвые поля: Esp DIAG_WINDOW_MS/DIAG_PERIOD_MS/lastDiag/projDumped/enabledAt/stat*Prev (присваиваются, не читаются)/netFailedLogged; Watermark CLOCK_GRAY/lastPingLog; RustClient unused imports Method/Modifier + поле autoSprint.
- ItemIcons uvCache по Item — скин-варианты (NBT) делят UV (ок для базовой иконки; риск, если иконка скин-зависимая).
- CheatHud сортировка ArrayList: getWidth в компараторе O(n²)/кадр; RustClient Modules.all().toArray() каждые 1мс — GC-мусор.
- GL_CLAMP (0x2900) в SvoTexture/GlassGrab на LWJGL3-стеке: CLAMP_TO_EDGE (0x812F) надёжнее для NPOT — блюр-граб может артефактить на некратных 2 разрешениях.
- NoRecoil javadoc дважды «Клавиша N (GLFW 78)» — handleKey удалён, toggle из меню (stale docs).
- Svo applyAll: 2 новых IdentityHashMap/тик на главном потоке (GC-мусор).

**NoSlow СПАМ-ЛОГИ УДАЛЕНЫ (09-09/10, DLL 21:08, 54 класса):** убраны diag-блок раз в 1с (dumpMods всего списка модификаторов — главный спам), «slowness effect removed» (писался каждый тик при активном эффекте), «compensating» = one-shot за период компенсации (compLogged, сброс при снятии), «compensation removed» убран; мёртвые lastDiag/DIAG_PERIOD_MS/dumpMods/`removed=null no-op` вычищены. Остались: registered, первый compensating за период, extra resolved, ошибки. ГАТЧ: регэксп-правка съела закрывающую скобку else-ветки → «catch without try» — после регэксп-правок проверять парность скобок и компилировать.

**СТАТУС ПОСЛЕ АУДИТА (09-09/10):** из критических НИ ОДИН не чинен — юзер вместо фиксов попросил удалить неработавшие новые модули (Timer/Strafe/BlockOverlay/ItemEsp/BulletTracers удалены, DLL 51 кл 16:15) и убрать спам-логи NoSlow (DLL 21:08, см. выше). Остались незакрытыми: AutoSprint onDisable, Esp камера prev-vs-lastTick (+дубль updateCamera), GuiScale кэш, FullBright stale gammaDefault, NoSlow silent-return при упавшем resolveExtra. Мёртвый resolveMovementAttributes()/modApplyMethod в GameContext оставлен (NoSlow.resolveExtra свой резолв подстраховывает через ctx.modClass). Дальше: AimBot получил баллистику 1:1 из дампа конкурента (см. [[rustme-chtdump-competitor]], DLL 63 кл 01:09 10.09) — дефолты PingComp 0.5 / K_MAX 1.5 / elapsedCap 0.5с из .data-дампа.

Связано: [[rustme-night-s2c-catalog]], [[rustme-ads-slowdown]], [[rustme-esp]], [[rustme-norecoil]], [[rustme-msdf-font]].
