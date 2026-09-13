---
name: rustme-viewmodel
description: View model (позиция/поворот/масштаб предмета в руке) — подмена
  ItemRenderer: Minecraft.llIlllll (public, rustme.llilIiiIiI) →
  rustme.ViewModelRenderer, оверрайд llIIliliII обёрнут в translate/rotate/scale;
  паттерн CheatIngame
metadata:
  node_type: memory
  type: project
---

View model (09-11, DLL 14:12, 101 кл, ждёт теста). Модуль «View model» (Visuals): Позиция X/Y (±1, step 0.01), Позиция Z (±1), Поворот X/Y (±90), Поворот Z/roll (±180), Масштаб (0.1..2) — офсеты поверх штатного трансформа руки (±0.56/-0.52/-0.72 у форка).

**ЦЕПОЧКА (верифицировано 09-11):** ItemRenderer = **rustme.llilIiiIiI** (public, НЕ final; конструктор принимает **GameSettings** — в форке gs заменяет mc; поле gs внутри = lIIIliiiI:public final iilliIliiI). Minecraft (lIliIiiIiI) держит его в поле **llIlllll** (public, единственное поле этого типа). Рендер руки = **llIIliliII(player iiIililiiI, pitch F, swing F, hand llIIIilIiI, swingProgress F, stack liIIIIIIiI, equipProgress F)** — внутри pushMatrix + translate(±0.56, -0.52+swing*-0.6, -0.72) (старая декомпиляция lliliiiiii_1.java:179; старые имена НЕ совпадают с текущими — full_src это СТАРЫЙ билд, классы переименованы CFR по case-коллизиям, сопоставлять по константам/структуре).

**МЕХАНИКА (паттерн CheatIngame):** rustme.ViewModelRenderer extends llilIiiIiI — оверрайд llIIliliII: если активен → GL11 pushMatrix → glTranslatef(posX/Y/Z) → glRotatef(Z roll, Y yaw, X pitch) → glScalef → super → popMatrix; иначе прямая делегация. Подмена ленивая из TickEvent (агентный поток, запись ссылки — как ensureHud): найти поле llIlllll по иерархии mc.getClass(), сохранить оригинал, записать `new ViewModelRenderer((iilliIliiI) ctx.gs)`; onDisable возвращает оригинал + transformActive=false. Статики трансформа volatile, обновляются из настроек каждый тик. Статики в ViewModel (modules.impl), читаются рендерером из пакета rustme — класс рендерера обязан лежать в rustme-пакете (параметры оверрайда могут быть package-private).

**ГАТЧИ:** рендер вызывается и для ПУСТОЙ руки (stack empty) — трансформ применялся и к кулаку (ок для viewmodel); наш transform снаружи super'овского pushMatrix → не конфликтует с их трансформами; GL-матричные операции НЕ кэшируются GlStateManager (кэш у цвета/освещения) — glTranslatef напрямую безопасен. game_stubs GL11: glPushMatrix/glPopMatrix/glTranslatef/glRotatef/glScalef присутствовали изначально.

Связано: [[rustme-esp]] (Entity геттеры), [[rustme-custom-fov]] (сосед Visuals), [[rustme-jni-defineclass-bypass]] (правила агента).
