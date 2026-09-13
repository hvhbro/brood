---
name: rustme-custom-fov
description: Custom Fov (0..200) — настоящий FOV мода = FloatNode
  Settings.getData().getGeneralSettings().getFov() (градусы, дефолт 70),
  читается getFOVModifier (liliiiiiii_4.lilIllIiII) из gluPerspective; setValue
  без клампа; ZNANIA 14.2/14.12 ошибочны — iIiiIilliI() это getPartialTicks, НЕ
  FOV
metadata:
  node_type: memory
  type: project
  originSessionId: sess_108ab9bb-329b-4492-a6b9-07aa20973a59
---

Custom Fov (09-11, DLL 13:46, 96 кл, ждёт теста). Модуль «Custom Fov» (Visuals): слайдер FOV 0..200 (step 1, дефолт 70).

**НАСТОЯЩАЯ ЦЕПОЧКА FOV (декомпиляция tools/decomp/full_src, 09-11):**
- `liliiiiiii_4.lilIllIiII(F,Z)` = getFOVModifier (EntityRenderer-аналог) — зовётся из `Project.gluPerspective(this.lilIllIiII(f, true), fbW/fbH, 0.05, far)` при построении проекции (3 call-site: мир/hand/облака).
- Внутри: `Settings.getData().getGeneralSettings().getFov().getValue()` — **FloatNode в ГРАДУСАХ, дефолт 70** (при useFov=false — 90 для служебных проходов). Поверх: зум-ключ (keyZoom /4), лук-замах (/(1−500/(t+500))×2+1), спринт (*60/70 в конкретных видах).
- **ГАТЧ ZNANIA: gs.iIiiIilliI() = getPartialTicks (0..1), НЕ FOV** — 14.2 «FOV-опция liIIiillI» и 14.12 «даёт 0.16» разрешены: liIIiillI в холдере IlillilIiI — поле таймера partialTicks. FOV — только узел настроек.
- GraphicSettingsCategory узла FOV НЕ содержит (только renderDistance/af/aa/mipmap/качества).

**РЕАЛИЗАЦИЯ (как FullBright-гамма):** resolve через GameContext.settingsObj → getData() → getGeneralSettings() → getFov(); запись `OptionNode.setValue(Float, tempState=false)` — storedValue БЕЗ клампа валидатора. Применять при изменении слайдера (lastApplied-кэш) + страховочный реплай раз в 1с; onEnable запоминает значение узла (fovDefault), onDisable возвращает. Кламп применяемого ≥1.0: при FOV=0 проекция вырождается (1/tan(0)=inf → кадр ломается), 1° = максимальный зум без поломки. Тихий ретрай resolve, пока settingsObj не готов. Тик — EventBus.subscribe(TickEvent, аноним) ([[rustme-custom-time]]: Module.onTick мёртв).

Связано: [[rustme-next-features]], [[rustme-custom-sky]] (fovY-юниформ неба читается из PROJ-буфера и теперь будет следовать нашему FOV), [[rustme-esp]] (PROJ-сигнатура/fov fallback).
