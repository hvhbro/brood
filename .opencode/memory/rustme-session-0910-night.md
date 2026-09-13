---
name: rustme-session-0910-night
description: Сводка ночи 09-10 — Timer удалён, Strafe 1:1 по найденному
  runtime-коду, SoundEsp (varint-парсер), JumpCircle near-clip, KeybindsWidget,
  блюр-меню фикс (WINDOW uv по экрану); InstantUse контракт снят, ждёт «делай»;
  DLL 02:33 65 классов
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e956625e-4cc0-4829-b444-3cbb0dcab14f
---

СЕССИЯ 09-10 (ночь) — ИТОГИ. Финальная DLL 02:33, 65 классов, invoke чист.

**1. Timer УДАЛЁН полностью** (файл + регистрация) по решению юзера: их Timer-runtime в VMP (регион 0x27xxx мутирован, hlt-трапы, 0x27830 = GL-резолвер-приманка). Слоты сняты (timerSlider=5.0 @0x32cf08, smartTimerDuration=3.0 @0x2fded0), код недостижим. Таймер форка: rustme.IlillilIiI (декомпил ililliliii_8), объект в mc.IIIiiiIl, acc IiIIiillI:F + partial liIIiillI:F + int iiIIiillI (стирается liiiliIllI каждый кадр), timerSpeed-поля НЕТ.

**2. Strafe.java переписан 1:1** по НАЙДЕННОМУ runtime-коду (0x5c3xx-0x5c6xx). Прошлая flyingSpeed-версия удалена как неих. Алгоритм: AirOnly&&onGround→exit; strafe/forward инпут; mag=sqrt(s²+f²)<0.04→exit; на земле speed=min(0.2806,mag) (ваниль), в воздухе speed=mag×((boost−0.1)×0.015+1) (boost 0.77→×1.010, 100→×2.485); угол=yaw ±90°(strafe-знак) +180°(forward<0), rad=×0.017453292519943295; motionX=−sin(rad)·speed, motionZ=cos(rad)·speed; AutoJump: onGround+collidedHorizontally→motionY=0.42. Все константы из дампа (0.04/0.2806/0.1/0.015/1.0/0.42). Настройки: Boost 0.1-100 (def 0.77), Air Only, Auto Jump.

**3. SoundEsp** (modules/impl/SoundEsp.java): payload-шина rust:sound:play (событие rustme.IlilliiliI — КЕЙС-ловушка!), подписка illIIIlIIl(Class,Consumer) — ИНСТАНС-метод синглтона lIllIilliI.iiIiliIiiI (единств. static-поле типа шины, ищется динамически). Словарь конкурента 1:1 (~60 правил категоризатора + weapon-map 15 пар ak47→AK47…, 'step'→скрыть), формат «%s x%d (Nm)», цвета: бой оранж/техника циан/лут жёлтый. Серверные entryId (CP-скан jar): rustme.block.*.open/close, rustme.entity.*.open/close и т.д. **ГЛАВНЫЙ БАГ найден и исправлен**: EfficientBinaryFormat = VARINT для int и длин строк (BinaryDataInputDecoder→readVarInt: 7 бит/байт), не int32 — парсер молча разваливался на первом поле. Переписан readVarInt/readVarString + диаг: «payload channel: X» (раз в 2с) и «sound: id=… pos=…» (1с). Поля PlaySoundPacketData: entryId=varString, instanceId=varint, volume=float4, position=notNullByte+3×float4, category=varint.

**4. JumpCircle — серия фиксов, финал:** (а) glVertex без glBegin/glEnd — вершины целиком игнорируются (первый корень); (б) screenSpaceStart/End добавлен и потом УБРАН — в overlay-фазе матрицы уже экранные с guiScale ×2 (Esp/Tracers рисуют без хука), glOrtho при пустом стеке рискует портить PROJ навсегда; (в) NEAR_W=0.2 near-clip: вершины с cw≤0.2 срезают сегмент — кламп W_CLAMP растягивает их в бесконечность (диаг юзера: w=0.12 → sx=5985 sy=8321 при экране 960×509 — вырожденные квады-мусор); (г) счётчики drawn/skipped (лог раз в 300мс при живом круге) — следующий диагностический шаг; (д) дефолты Размер 4.5 / Эластичность 1. Геометрия: кольцо под собственной камерой — из 1-го лица видно только при взгляде вниз (kimiko та же). Esp.lastW() добавлен (RAW cw последней проекции).

**5. KeybindsWidget.java** (utils/render/) — порт sweetie KeybindsWidget, БЕЗ иконки (юзер просил). Плашка «KeyBinds» (sf_semibold 10.5 белый) + строки bind(слева x+2)…имя(справа right-aligned), fontSize 10, rowHeight=cellHeight+2, обрезка имён maxWidth-25+«...», offsetY=y+21, listY=offsetY+3. Анимации 1:1: alpha 0.12 / height 0.15 / per-module map 0.12, slide=6×(1−alpha); у оригинала map течёт — чистим при size>binds+16. Позиция (3,120), драг hudDrag=3 (hit-тест после Watermark и ArrayList), keyName-маппинг скопирован из CheatMenuScreen. Хук: CheatHud.renderFrame после Watermark. Список = Modules.all() с isState && bindKey>0.

**6. InstantUse** — полный lguse-контракт снят (детали в [[rustme-instantuse]]): uphint → корутина → delay(duration) → lguse; шuse клиентом не шлётся; РАБОЧИЙ план = lguse сразу при uphint с longUse!=null. КОД НЕ ПРАВИЛСЯ — ждёт «делай» ([[rustme-phase-gating]]).

**ГАТЧИ СЕССИИ (новые):**
- Сканер rip-relative инструкций: off-by-one у cmp/mov byte[rip+d32] (7 байт: opcode+modrm+d32+imm8, RIP-base=off+7) — «пустые» результаты скана были ЛОЖНЫМИ; мульти-паттерн (48 8D/80 3D/0F B6/C6 05/8A/F6 05) с верными длинами нашёл всю Strafe-функцию.
- Кейс-ловушки имён: rustme.IlilliiliI vs ililliiliiI — существование класса проверять zip-namelist'ом game_cp.jar ДО сборки (третий случай после IiIIiillI/iiIIiillI).
- Подписка на модовую шину = инстанс-метод (illIIIlIIl) на синглтоне; invoke(null) на инстанс-методе падает всегда. GameContext.hudSubscribe резолвит iIlIIIlIIl = ОТПИСКУ, не подписку.
- Юзер тестировал СТАРУЮ DLL (лог 01:15 < build 01:22) — отпечаток сборки сверять по mtime + «define done: N/N».
- Сообщение об ошибке CNFE в старом логе ≠ баг в текущем коде — сначала сверить mtime DLL против времени лога.

Связано: [[rustme-chtdump-competitor]], [[rustme-next-features]], [[rustme-instantuse]], [[rustme-session-0909-evening]].

**KeybindsWidget фикс наложения текстов (09-10 22:23):** ряд = бинд слева (x+2) + имя справа; резерв под бинд был ФИКСИРОВАННЫЙ maxWidth-25px (из sweetie, под короткие "R"/"F") — бинды вида MOUSE4/BACKSPACE при шрифте 10px шире 25px → правое имя наезжало на бинд. Фикс: maxNameW = maxWidth − 2 − width(bind) − 6 (реальная ширина бинда + зазор 6), обрезка с "..." с учётом ширины суффикса. Вертикаль (rowHeight=cellHeight+2) была ок. 