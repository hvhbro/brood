---
name: rustme-menu-elements
description: Меню: типизированные элементы настроек РЕАЛИЗОВАНЫ И ПОДТВЕРЖДЕНЫ ЛОГОМ ЮЗЕРА (09-10, DLL 22:23 74 кл) — чекбоксы/чипы-режимы/мультибокс/свотчи/секции rock 1:1 (тогглы булов/чипов, бинды из меню работают), плавный скролл через lIIillIlil+IiiliilliI.lillilIIIl (getEventDWheel); JumpCircle/AimBot/Strafe конвертированы
metadata:
  type: project
---

**РЕАЛИЗОВАНО 09-10 (DLL 22:17, 74 класса, java/lang/invoke=0). ЛОГ ЮЗЕРА 22:09 ПОДТВЕРЖДАЕТ ЖИВУЮ РАБОТУ:** тогглы kimiko-настроек JumpCircle из меню (`Турбулентность -> ON`, `Свечение -> ON`, `Режим цвета -> Свой`, `Подкрашивать цветом -> ON` — visibleWhen-цепочки и чипы-режимы живые), захват бинда (`bind AimBot -> R`, затем `-> MOUSE4`). Сборка 21:15 с этими элементами была сломана define-порядком (см. [[rustme-jni-dll]]), 21:49+ — рабочие.

**Module.java — Setting-иерархия:** `Setting(name)` база с условиями видимости `visibleWhen(BoolSetting,boolean)` / `visibleWhen(ModeSetting,int)` (generic `<T extends Setting> T visibleWhen(...)` возвращает T → чейнинг присваивания в поле исходного типа). Наследники: `BoolSetting extends FloatSetting` (value 0/1, get()/set()), `ModeSetting extends FloatSetting` (String[] modes, index()/get()/is()), `MultiSetting extends Setting` (options+selected[], toggle(i), count()), `SectionSetting`, `ColorSetting` (int argb + Module.COLOR_SWATCHES 12 цветов). Фабрики Module: addSetting/addBool/addMode/addMulti/addColor/addSection.

**CheatMenuScreen — элементы (rock createComponent 1:1):** ряд-чекбокс h=18 текст 8px alpha 0.75+0.25×hover, pill 13×8 r3.5 (ON=ACCENT, OFF=BG@a102, knob 5×5 r2 inset 1.5, анимация 300мс линейный шаг); слайдер h=32 label 8px+значение 7px справа, track h3 r1.5, knob r3 = кольцо ACCENT 1.5px + тёмный центр, value-анимация 300мс; чипы Mode/Multi: title 8px + wrap gap 2, chip r2.5 pad 3 текст 7px medium (bg dark@a102→accent 0.2×hover; выбран=accent→dark; текст white alpha 0.75+0.25×sel, анимация 220мс), у Multi счётчик "N of M"; Color — свотчи 11×11 r3, выбранный с кольцом ACCENT; Section — 9px sf_semibold a230 pad(10,5). Клик-хит-тест по кэшу раскладки последнего кадра (SRow), только видимые строки и только внутри клипа.

**СКРОЛЛ (плавный):** override `lIIillIlil()` (handleMouseInput форка, зовётся на КАЖДОЕ событие while(Mouse.next()) и колесо ИГНОРИРУЕТ) → после super читать `rustme.IiiliilliI.lillilIIIl()` = **getEventDWheel** (массив IiiiIIiiiI event-очереди iiiliilliI; запись верифицирована дизасмом IlllilIIIl(int,bool,int): [X][Y][WHEEL][button][state]). super бросает IOException → try/catch. wheel×26 к scrollTarget, кламп [0, contentH−viewH], scrollCur += (target−cur)×(1−exp(−dt/90)); контент под scissor (свой scissorOn с GuiScale, RenderUtil.scissorStart считает scale грубо 240). Смена выбранного модуля сбрасывает скролл.

**Конверсия модулей:** JumpCircle = kimiko 1:1 (сепараторы Основное/Искажение/Свечение/Подкраска/Цвет; булы Турбулентность/Свечение/Подкрашивать цветом/Второй цвет; режимы Анимация [Обычная,Эластичная,Назад] и Режим цвета [Радуга,Клиент,Свой]; visibleWhen-цепочки; «Клиент»=фейд #906BFF↔#5A4BFF, «Свой»=2 ColorSetting-свотча); AimBot Draw FOV/Predict Y → addBool; Strafe Auto Jump → addBool.

**ФИКС 09-11 (юзер):** `layoutSettings` пропускает скрытые настройки (visibleWhen=false) — раньше они занимали место в списке не отрисовываясь; теперь список схлопывается/расхлопывается динамически (раскладка пересчитывается каждый кадр).

**CONFIG-вкладка (09-11):** нижний чип-«шестерёнка» рельсы (setChipY) открывает конфиг-панель вместо home (повторный клик — назад; выходят также клик по категории/поиску/RMB-модулю); панель: CONFIG-заголовок + статус-строка, поле имени (ASCII ≤24), кнопки Save/Load/Delete и Reload/Open dir, список конфигов с выделением; скролл списка переиспользует scrollTarget/scrollCur (свободны при selected==null), wheel-условие расширено на configMode. ГАТЧИ: enterConfigMode обнуляет selectedAtLayout (иначе конфиг-скролл запишется в scrollStore модуля при возврате); клик-блок обрабатывает только панель ниже topH (сохранён драг окна за верхнюю полосу). Формат файлов/шифрование — [[rustme-config-system]].

Связано: [[rustme-jumpcircle-input]], [[rustme-aimbot-predict]], [[rustme-phase-gating]], [[rustme-menu-tracers-merge]].
