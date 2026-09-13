---
name: rustme-render3d
description: Ревизия 3D-рендера форка (09-10, всё дизасм-верифицировано) —
  FBO-хелдер iIlIiIiIiI (поля/методы/аттачменты), GL-константы liIlIiiIiI из
  clinit (36160/36161/36064/36096), главный цикл facade (runGameLoop→renderFrame
  порядок проходов), камера-статики lliIilliiI.iIiliiiiII
  (2982/2983/2978+gluUnProject), пост-группа IllIiIiIiI/lIlIiIiIiI (FXAA/пассы),
  рецепты 3D-фич
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e23a6951-aa81-4a1d-9a32-5c8aee908dc9
---

**Ревизия 09-10 (юзер: «глубоко проанализируй рендер в 3д — depth, буферы, шейдеры, пост-процессинг — перепроверяй каждый раз»). Всё снято javap/CFR, каждый вывод перепроверен. Дизасм-дампы: tools/decomp/r1 (FBO-хелдер), r2/r3 (facade), r4 (камера), r5/r6 (пост-группа/пасс), r7/r8 (маски), r16 (SoundManager), jt40 (GL-константы); скрипт fbo_constants.py.**

**1. FBO-хелдер `iIlIiIiIiI` (ctor w, h, useDepth):**
- Поля: `lllIIiIiI`=framebuffer id, `iIlIIiIiI`=COLOR texture id (RGBA8, internal 32856), `illIIiIiI`=DEPTH RENDERBUFFER id (**33190 = DEPTH_COMPONENT24** — depth НЕ текстура, root-cause WetWorld подтверждён числами), `IIlIIiIiI`=useDepth, `lIlIIiIiI/iiilIiIiI`=физ. W/H, `IllIIiIiI/lilIIiIiI`=лог. W/H, `IiilIiIiI`=clearColor[4] (1,1,1,0).
- Методы: `iIiliIiIII(w,h)`=create (tex+RB+attach: COLOR_ATTACHMENT0=текстура, DEPTH_ATTACHMENT=renderbuffer); `IllIiIiIII()`=clear (mask 16384 | 0x100 при depth); `lllIiIiIII(true)`=bind(+viewport); `IIiliIiIII()`=unbind→0; `iiiliIiIII()`=unbind texture; `iiIliIiIII(w,h,bl)`=**framebufferViewOrtho** (ortho 0..w/h, near 1000 far 3000, translate 0,0,−2000, fullscreen-квад — выкладка результата на экран); `lliliIiIII(filter)`=TEX filter+clamp; `ililiIiIII()`=delete all.
- GL-константы моста `liIlIiiIiI` (GlStateManager) сняты из clinit (обе ветки ARB/EXT): `IIllliiiI`=36160 GL_FRAMEBUFFER, `IliiiIiiI`=36161 GL_RENDERBUFFER, `lIllliiiI`=36064 GL_COLOR_ATTACHMENT0, `lliiiIiiI`=36096 GL_DEPTH_ATTACHMENT, `lllIliiiI`=36053 DEPTH_COMPONENT, `IIIlliiiI`=36054 D16, `iIllliiiI`=36055 D24.

**2. Главный цикл facade `lIliIiiIiI`:** runGameLoop = `IllillIiII(f, l)` → `IiIIllIiII(f)` (тик) → `llIIllIiII(f)` → glDepthFunc(516)+clearDepth(0.1) → **renderFrame `llilllIiII(kind=2)`**. Порядок renderFrame: setupCamera `iIIlllIiII(f)` → fog → sky `IiilllIiII(-1, f)` + `llIlIiiIiI.IilIIIliII(f,n)` → terrain `iIilllIiII` (если игрок < 128+renderDist×128) → entities `IIilllIiII` + `iiiiIIliII` (RenderGlobal) → block outlines → particles → погодные маски (см. ниже) → clouds → rain → HUD → при флаге `Illillll` финальный пост `IlIlllIiII(f, n)`.
- **Камера-статики**: `lliIilliiI.iIiliiiiII(entity, bl)` — glGetFloatv 2982 (MODELVIEW→`IiIlIlIl`), 2983 (PROJ→`lIIlIlIl`), glGetInteger 2978 (viewport→`ilIlIlIl`), gluUnProject центра → `IlilIlIl` (Vec3). Вызывается из facade — к моменту нашего overlay актуальны (ESP-путь подтверждён).
- **FXAA/пост**: `liiIllIiII(n)` грузит `shaders/post/fxaa_of_2x/4x.json` через пост-группу, кэш `lllIllll[n]`.

**3. Пост-группа `IllIiIiIiI`** (vanilla PostEffectGroup-аналог, json): targets → `new iIlIiIiIiI(w, h, true)` в map `lillIiIiI` по имени; passes = `ilIlIiIiI:List<lIlIiIiIiI>`, каждый пасс: input FBO → shader (liiliIiIiI) → output FBO; uniforms ProjMatrix/InSize/OutSize/Time/ScreenSize + aux. `IIiiIIiIII(w,h)` = resize всех + process всех пассов.

**4. Погодные маски (silhouette-система, путь для 3D-эффектов):** контроллер `lilllIliiI` (поле программа `IlliIlIl:lIliliiIiI`, очереди `IIiIIlIl[4][2]` — тип × до/после мира); renderFrame зовёт `liillllliI` (до мира) и `iIillllliI` (после) — рисуют очереди с switch lightmap on/off; маска `lililIliiI` рисует billboard-квад от позиции.

**5. SoundManager-цепочка (для SoundEsp, верифицировано):** `gs.iiliIilliI()` → SoundEngine `IIiililiiI` → поле `iIliiiil` → SoundManager `IililIiliI` → поле `IiliIliIl` (LinkedHashMap playingSounds) → wrapper `llIilIiliI.IliiIliIl` → ISound `IIIlIiliiI`: `llIilIiliI()`=ResourceLocation→getName(), X=`lIIilIiliI()`, Y=`lIlilIiliI()`, Z=`iililIiliI()` (маппинг X/Y/Z по ctor: fload 8/9/10 → putfield iIlllllI/IilllllI/IIlllllI). ГАТЧ: SoundHandler `liiililiiI` в полях нигде не хранится (осиротелен, доступ только через gs-геттер). Детали в [[rustme-soundesp]].

**6. Рецепты 3D-фич:**
- 3D-оверлей поверх мира — из overlay-фазы (CheatIngame) через статики `lliIilliiI` (как ESP/Tracers); depth-test корректен через их renderbuffer.
- Depth-текстуры НЕТ (renderbuffer) — путь к сцене-депту: **ТОЛЬКО read-only копия** (глубина 09-10: подмена аттачментов в оверлее провалена — binding в оверлее = FBO id 1, у него НЕТ depth-rb (rb=0, аттачмент-запросы NONE для всех id 1..12); подмена на нём = порча рендера (худ исчез + картинка зависла). Рабочий паттерн JumpDistort: probe FBO id 1..12 read-only (COMPLETE + color TEXTURE + depth RENDERBUFFER + валидация неоднородности depth readback'ом), затем glCopyTexSubImage2D глубины в свою текстуру каждый кадр; probe 09-10 кандидатов не нашёл → окклюзия off (graceful). [[rustme-depth-swap]] [[rustme-jumpcircle-input]].
- Полноэкранный пост — сэмплинг КОПИИ сцены (glCopyTexSubImage2D из текущего таргета; color-текстура `iIlIIiIiI` в оверлее пуста/чужая); fullscreen-проход рисовать в ТЕКУЩИЙ таргет (не FBO 0 — оверлей в своём FBO, binding=1); скопированная текстура НИ К ЧЕМУ не аттачится (feedback loop = зависшая картинка); silhouette-маски — очередь до/после мира как их погода.
- Per-corner радиусы (Vector4f их BLUR_RECT) наш SDF-шейдер не поддерживает — единый радиус.
- lwjglx GL11 shim ИМЕЕТ glGetTexImage(IIIIILjava/nio/FloatBuffer;)V, glCopyTexSubImage2D, glGetTexLevelParameter(IntBuffer) (javap дампа) — стаб GL11 дополнен этими методами + GL_ONE=1.

Связано: [[rustme-wet-world-shader]], [[rustme-depth-swap]], [[rustme-soundesp]], [[rustme-night-0910-analysis]].
