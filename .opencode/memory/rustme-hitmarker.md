---
name: rustme-hitmarker
description: Hitmarker (✕ на месте попадания, порт gamesense-референса) — 2 слоя
  детекта: (1) ЛКМ-edge + ray-AABB предикшн, (2) сервер-подтверждение через
  hurt/hit-звуки SoundEsp; форма 1:1 референсу (статичные ±3..±6, lineWidth=guiScale,
  moved в координаты НЕ подставлять); мировая привязка через Esp.projectToScreen
metadata:
  node_type: memory
  type: project
  originSessionId: sess_108ab9bb-329b-4492-a6b9-07aa20973a59
---

Hitmarker (09-11, DLL 14:00→14:32, 103 кл, ждёт теста). Модуль «Hitmarker» (Visuals), рендер из CheatHud.renderFrame после ItemEsp. Настройка: «Длительность» 1..10с (дефолт 5 — держится полной альфой), фейд фиксированный 1с (alpha 255/сек, как step=255/1.0*frametime в референсе).

**ДЕТЕКТ — 2 СЛОЯ (09-11 14:32):**
- **Слой 1 — предикшн (мгновенный):** ЛКМ edge (ctx.isMouseButtonDown(0), в открытом меню — скип) → луч из глаз (Esp.camX/Y/Z — обязательно вызвать Esp.updateCamera(ctx, partialTicks) самому) по взгляду (forward = (-sin(yaw)cos(p), -sin(p), cos(yaw)cos(p))) → сляб-тест ray-AABB по чужим (AABB = iiIlIIlIII(), поля 14.1), ≤150м. Точка = ray∩AABB.
- **Слой 2 — сервер-подтверждение (точный):** hurt/hit-звуки через SoundEsp. SoundEsp теперь ПОЛЛИТ ВСЕГДА (при выключенном модуле — троттлинг 50мс; classify только при isState), новые API: addHitSoundListener(HitSoundListener)/isHitSound(name: contains hurt|hit|flesh|damage)/notifyHitListeners; diag-лог «hit-sound: …» 1/сек — по нему тюнить паттерны. Hitmarker слушает: маркер ТОЛЬКО если наш клик был ≤1.2с назад (окно отсеивает чужие перестрелки), жертва = ближайший чужой игрок ≤3м от точки звука (AABB-центр), найден → маркер в её центр + НЕДАВНИЙ (≤500мс) предикшн-маркер заменяется точным (без дублей). hits синхронизирован (listener из агентного потока, рендер из GL-потока — снапшот draw).
- **AutoShoot СОВМЕСТИМ из коробки:** AutoShoot эмулирует ОС-клик через java.awt.Robot (ClickWorker) — поле KeyBinding огонь НЕ триггерит (лог 09-11), а Robot = настоящий клик ОС → glfwGetMouseButton его видит → LMB edge срабатывает. Доказано SHOT-логами AimBot (тот же edge) при автострельбе.

**НЕ НАШЛОСЬ (анализ 09-11):** hurt_time из property-системы (enum IliiIllIiI: SCALE/HEALTH/HURT_TIME/PARTIAL_TICKS...) — это АНИМАЦИОННЫЕ свойства моделей (BipedPose), не сетевой стейт; чистого synced hurt-поля у игроков не найдено (DataWatcher-поля на Entity root нет, dmg-каналы без цифр). Путь через hurt-звуки — единственный серверный фидбэк.

**Механика маркера:** Hit {x,y,z мировые, bornMs, moved=8, alpha=255, confirmed}; каждый кадр Esp.projectToScreen (skip lastW≤0.05); moved 8→0 (20/сек), hold (слайдер) → фейд 1с; линии ✕: (±(3+moved))→(±(6+moved)) SE/NW/NE/SW, GL_LINES, белый с alpha.

**Отвергнутые пути:** lllIIiliiI = IBlockState-интерфейс, НЕ RayTraceResult (ZNANIA 14.2 мимо); gs.iIiiIilliI() = getPartialTicks.

Связано: [[rustme-esp]] (камера/проекция/AABB), [[rustme-soundesp]] (поллинг playingSounds), [[rustme-aimbot-predict]] (AutoShoot/ClickWorker).

**ФОРМА V2 (09-11 15:26/17:01, 106 кл) — жалоба юзера «перекрестие не похоже на референс»:**
1. moved-анимация УБРАНА из координат: в референсе moved вычисляется, но в DrawLine-вызовы НЕ подставляется — линии статичные ±3..±6. Не подставлять moved повторно.
2. **glLineWidth = GuiScale.get(ctx)** — раньше при guiScale 2 линии рисовались 1 физический пиксель = вдвое тоньше референсных (главная причина «непохожести»).
3. Сегменты строго: SE (x+3,y+3)→(x+6,y+6); NW (x−3,y−3)→(x−6,y−6); NE (x+3,y−3)→(x+6,y−6); SW (x−3,y+3)→(x−6,y+6). НЕ транспонировать (±3,∓6)↔(±6,∓3) — даёт крест вместо ✕-диагоналей.
