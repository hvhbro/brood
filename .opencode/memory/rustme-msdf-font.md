---
name: rustme-msdf-font
description: rustme форк — MSDF-шрифты (MsdfFont, два экземпляра), критические
  грабли стаба/шимпа, watermark и ArrayList vanquish
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f991460d-4c78-4e62-9fec-7aebee4c90d8
---

rustme JNI-проект: HUD-шрифты и overlay переведены на MSDF (эта сессия — ZNANIA.md раздел 13, там полные детали). Ключевое:

- **MsdfFont instance-based**: `MsdfFont.get("sf_semibold")` (ArrayList/HUD) и `get("medium")` (ватермарка, атлас из ассетов rockstar). Атласы с диска `jni\agent\fonts\msdf\`, формат msdf-atlas-gen, парсер встроен. medium имеет `yOrigin=top` — нормализация в MsdfFont (UV v=row/H, planeBounds top отрицательный).
- **Дисковые зависимости агента (инвентаризация 09-07):** с диска ЧИТАЮТСЯ только 2 атласа MSDF — medium.json/png (~260КБ, при первом кадре HUD) и sf_semibold.json/png (~527КБ, при первом ArrayList/ESP) из `jni\agent\fonts\msdf\` (Files.readAllBytes → GL-текстуры, в памяти только метрики+texId). `sf_medium.json/png` (~524КБ) лежат там же, но НИКОГДА не грузятся (никто не звал get("sf_medium") — можно удалить). ПИШЕТСЯ на диск: только логи `C:\Logs\noslow_agent.log` (агент) и `C:\Logs\jni_rva_check.log` (DLL). Всё остальное зашито: 28 классов агента — в agent_payload.h (protected-формат, DefineClass из памяти), шейдеры — строковые константы Shaders.java (GL20 из памяти). Опция: атласы можно запечь byte[]-константами в payload (как классы) для полной отвязки от диска; сейчас осознанно с диска — шрифты правятся без пересборки DLL.
- **Пинг**: player (liIililiiI → iiIililiiI=AbstractClientPlayer).`iliiililiI()` → iiIilIliiI, `liIIillliI()` = пинг. ЛОВУШКА: это снапшот-поле, обновляется только открытым Tab-листом; живое значение — `illIillliI()`.resolvePing в Watermark.
- **Критические грабли** (все проверены болью, детали в ZNANIA 13.4):
  - GL_BLEND в стабе был 0xBE вместо 0x0BE2 — блендинг молча не работал;
  - glTexCoord2f (immediate lwjglx) НЕ доходит до GLSL — UV только uniform+gl_FragCoord;
  - glGetFloat(I,FloatBuffer) молча не заполняет буфер;
  - GL_ALPHA_TEST режет MSDF-градиент — выключать на время текста;
  - glColor после кадра сбрасывать в белый (иначе персонаж красится цветом полосок), blend не выключать (кэш GlStateManager);
  - GL_CURRENT_PROGRAM (0x8B8D) тоже кэшируется игрой: привязку шейдерной программы восстанавливать БЕЗУСЛОВНО после каждого нашего шейдерного вызова (и в 0 тоже); условный restore (if prev!=0) оставлял наш MSDF-шейдер висеть в fixed-pipeline кадре → лобби (меню с иконками) рендерилось сквозь нашу маску-квада → ФИОЛЕТОВЫЙ ЭКРАН (фикс 09-08: CustomFont.drawString/drawGradientString + RenderUtil.drawRoundedRectShader, prevProgram читается до sh.start(); ПОДТВЕРЖДЕНО юзером — «пофиксилось, нету фиолетового экрана»);
  - НЕ делать физический glEnable(GL_DEPTH_TEST) в HUD/overlay-фазе (09-08, меню друга): кэш GlStateManager считает depth выключенным и свои disable скипает → весь нарисованный после пассов HUD проваливается depth-тестом против глубины мира в FBO и ИСЧЕЗАЕТ целиком; после grab-подобных пассов depth оставлять выключенным до конца фазы;
  - lwjglx-шимп = ТОНКАЯ ПРОКСИ в нативный LWJGL3 (реальные исходники: dump/classes/minecraft/org/lwjglx/opengl/ — полный набор GL11/13/20/30, javap показывает прямой форвард каждого метода); no-op-подозрения сняты, НО fixed-pipeline текстурный квад (glBegin+glTexCoord2f) в контексте форка НЕ РЕНДЕРИТСЯ (иконки друга были невидимы) — текстуры рисовать только GL20-шейдером с UV из gl_FragCoord (схема Shaders.ICON: quad+uvRect uniform'ы в физ.пикселях, вершины SCALED);
  - копия экрана в текстуру: glCopyTexSubImage2D (шимп проксирует) из READ-фреймбуфера; текстура в ПОЛНЫЙ размер экрана + blur-проходы в half-res FBO (даунскейл сам собой); GL30-функции FBO через reflection (org.lwjgl.opengl.GL30 нет в компиляционном cp); детали фикса иконок/блюра — [[rustme-menu-tracers-merge]];
  - SDF-плашки рисовать с запасом +1px (иначе тёмные швы между ними);
  - порядок градиентных вершин rockstar: arg1→TL, arg2→BL, arg3→BR, arg4→TR.
- **FullBright**: гамма 100 переполняла lightmap (синий мир/персонаж) → FULLBRIGHT_GAMMA=10.
- **Watermark** rockstar-стиль: y=26 верх-центр, капсула r=7 rgba(24,21,29,.85), градиент #906BFF 69% ширины fade вправо, часы HH:mm белым слева, пинг-бары справа (пороги 450/300/150/75).
- **ArrayList vanquish**: слева x=2, отдельные плашки rgba(12,12,18,240) с запасом +1px (иначе швы), medium 8px, полоска 1.5px, градиентный текст primary #906BFF → secondary #5A4BFF.

Связано: [[rustme-phase-gating]], [[rustme-jni-offset-recovery]]
