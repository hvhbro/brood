---
name: rustme-antioverlay
description: AntiOverlay (маски heavy helmet/diving mask) — реестр спеков: хаб
  liiIlilliI.iiliIIIiiI → overlay-реестр lIlIillIIl() (lIiIlilliI<iiIIlilliI>) →
  Map lIilIIIiiI ВНУТРИ lIiIlilliI (не у хаба!); ключи icon_<файл-без-пути> —
  NoSuchField у хаба = мод молча мёртв
metadata:
  node_type: memory
  type: project
---

AntiOverlay — удаление fullscreen-масок (тяжёлый шлем/водолазная маска) из рендера форка. Порт chtdump-концепции конкурента (v4): удаляем спеки из икон-реестра по ключу → рендерер спрашивает реестр → null → (Kotlin null-safe) оверлей не рисуется. Тыква (vanilla pumpkinblur) — отдельно, перезапись текстуры в TextureManager.

**ПОЧЕМУ МОД БЫЛ МЁРТВ (лог 09-11: `[AntiOverlay][ERROR] resolve failed NoSuchFieldException: lIilIIIiiI`):** resolve брал поле lIilIIIiiI у ХАБА — а оно у класса-реестра. resolve падал на первом тике, triedResolve=true → навсегда, мод молча ничего не делал (анти-паттерн: «resolve failed» один раз и тишина — см. [[rustme-esp]] гатч 9).

**ПРАВИЛЬНАЯ ЦЕПОЧКА (дизасм liiIlilliI / lIiIlilliI / IIIliilliI, 09-11):**
- Хаб = `rustme.liiIlilliI`, синглтон static `iiliIIIiiI`. Держит ~17 типизированных реестров `rustme.lIiIlilliI<T>` (static final поля: iIIiIIIiiI/IIIiIIIiiI/…/liiIIIIiiI/…).
- **Map<String,T> lIilIIIiiI — public final поле ВНУТРИ `rustme.lIiIlilliI<T>`** (реестр: liIlillIIl(String)=get, iIIlillIIl(T)=register, IIIlillIIl()=collection).
- Оверлей-спеки = `rustme.iiIIlilliI` (extends IillIiIiII: String id + iconPath); их реестр — **одно поле хаба `liiIIIIiiI`** (геттер `lIlIillIIl()` → lIiIlilliI<iiIIlilliI>).
- Resolve: `regC.getField("iiliIIIiiI").get(null)` → `regC.getMethod("lIlIillIIl").invoke(hub)` → `overlayRegistry.getClass().getField("lIilIIIiiI").get(registry)` → Map. Убранное храним в HashMap, onDisable возвращает map.put.

**КЛЮЧИ (дизасм IIIliilliI.IlIlIilIIl(path)):** `key = "icon_" + path.substringAfterLast('/').substringBeforeLast('.')` → из textures/gui/overlay-heavy-helmet.png → **"icon_overlay-heavy-helmet"**; маска = **"icon_overlay-diving-mask"** (текстуры в static-константах IiiIliIlII: llliilIliiI/iliiilIliiI). chtdump-ключи верны.

**Почему remove работает:** static-поля IiiIliIlII (llliiliiiI = спек heavy и т.п.) больше НИКТО не читает (scan_db: класс IiiIliIlII упоминают только IIIliilliI и он сам) — рендерер получает спеки через реестр по ключу, Map.remove(key) даёт null → не рисуется. Не забыть: геттеры спеков через static-поля — НЕ путь отключения.

**Почему remove не сработал (лог 09-11: specs removed=2, оверлеи живы):** маски рисуют ВИДЖЕТЫ из OverlayListener, которые КЭШИРУЮТ спеки в полях при конструировании (старая декомпиляция iiiliillii_6: `ilIlIIiiiI = iilIiliiiI.IlIlIilIIl(...)` — спек в статик-поле). Реестр спеков остаётся только для lookup по ключу, рендер идёт по кэшу.

**СТАТУС: v5 ПОДТВЕРЖДЁН РАБОЧИМ юзером (09-11 вечер: «Отлично AntiOverlay теперь работает!»).** Hotbar-модуль использует тот же Overlays.hide для HotbarOverlay — тоже работает.

**ГАТЧ СПАМА:** Overlays.resolve не ставил resolved=true в success-ветке → 858 строк «resolved (21 widgets)» в логе за сессию. Флаг ставить ДО return true (исправлено 09-11).

**СТАТУС: v5 ПОДТВЕРЖДЁН РАБОЧИМ юзером (09-11 вечер: «Отлично AntiOverlay теперь работает!»).** Hotbar-модуль использует тот же Overlays.hide для HotbarOverlay — тоже работает.

**ГАТЧ СПАМА:** Overlays.resolve не ставил resolved=true в success-ветке → 858 строк «resolved (21 widgets)» в логе за сессию. Флаг ставить ДО return true (исправлено 09-11).

**V5 (09-11 15:26/17:01, 106 кл) — удалять сами ВИДЖЕТЫ из OverlayListener.overlays:**
- **OverlayListener = rustme.liiiilIliI** (Kotlin object): синглтон static `iiIIIilll`; список виджетов — static ArrayList (поле-тип List, distinguishable от фабрик-List = Arrays$ArrayList); 22 фабрики KFunction в clinit, порядок = старой декомпиляции.
- Текущие классы виджетов: **HotbarOverlay = rustme.lilIililiI** (фабрика #6), **HeavyHelmetOverlay = rustme.liIiililiI** (#2), **DivingMaskOverlay = rustme.lliiililiI** (#3). Маски = WornItemEffectOverlay (ext `liliililiI`): `getIntensity()` = isWorn()→1/0, isWorn сравнивает экипировку игрока с полем item (RustEntry).
- utils/etc/Overlays.java: hide(ctx, widgetClass)/restore — удаление инстансов из списка. **Мутации ТОЛЬКО через ctx.runOnMainThread** (рендер-поток итерирует список → CME). Определение списка: static List-поле OverlayListener со значением instanceof java.util.ArrayList (фабрики = Arrays$ArrayList).
- Kotlin-метаданные у текущего билда ЗАЧИЩЕНЫ (grep DivingMaskOverlay по дампу = пусто) — имена только через фабрики из clinit OverlayListener.

Связано: [[rustme-nametags-icons]] (Hotbar v6 — тот же Overlays.hide), [[rustme-chtdump-competitor]], [[rustme-esp]].
