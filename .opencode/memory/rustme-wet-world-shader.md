---
name: rustme-wet-world-shader
description: WetWorld/SkyNebula УДАЛЕНЫ 09-08; ROOT-CAUSE найден 09-09 ночным
  дизасмом — depth-текстуры в пайплайне нет (renderbuffer/MSAA-blit color-only),
  3D-эффекты рисовать silhouette-масками; рецепт и гатчи внутри
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e956625e-4cc0-4829-b444-3cbb0dcab14f
---

**СТАТУС: ФИЧА УДАЛЕНА (09-08, решение юзера «удали обе функции и утилиты»).** WetWorld/SkyNebula/PostRig/DepthSwap и шейдеры WET/WET_DEBUG/SKY_NE/SKY_DEBUG/DEPTH2COLOR/BLIT убраны из jni/agent/src; регистрации в RustClient и вызовы в CheatHud убраны; ShaderUtil.uniformMat4 удалён. DLL 29 классов. Наработанные стабы в tools/compile_stubs + game_stubs.jar (GL30, нативный GL13, расширенный lwjglx GL11) ОСТАВЛЕНЫ — безвредны и пригодятся.

**Что было доказано за 5 итераций (ценно для повторной попытки):**
1. Depth-подмена РАБОТАЕТ (см. [[rustme-depth-swap]]): у мирового FBO iIlIiIiIiI (gs.IlliIilliI()) depth=RENDERBUFFER → своя текстура GL_DEPTH_COMPONENT24 через glFramebufferTexture2D; FBO COMPLETE, мир пишет depth в неё, игра цела.
2. Полный пайплайн copyScene(glCopyTexSubImage2D из read-буфера) → шейдер в свой postFbo → blend/opaque блит РАБОТАЕТ визуально (затемнение кадра = шейдер исполнялся, копия живая: scene copy mean 34-127 по времени суток).
3. ЧТО НЕ ЗАРАБОТАЛО: depth-СЭМПЛИРОВАНИЕ в шейдере (гипотеза — lwjglx glActiveTexture no-op для юнита≠0; MSDF жив на юните 0 по умолчанию). Camera-relative ViewProj (у rockstar ВЕСЬ шейдер в камера-относительных координатах: world = CamPos + view!) был исправлен, но финальный тест не проводился — юзер решил удалить. Диагностическая сборка с WET_DEBUG (R=depth G=mask B=disc) и SKY_DEBUG (R=depth G=skyA B=dir.y) + UNIT TEST юнитов была готова, НИКОГДА не тестировалась. **09-08 поздно: гипотеза no-op ОПРОВЕРГНУТА** — дизасм реального шимпа (dump/classes/minecraft/org/lwjglx/opengl/, полный набор GL11/13/20/30) показал ТОНКУЮ ПРОКСИ в нативный org.lwjgl.* (каждый метод — прямой форвард). Вероятная реальная причина падения depth-сэмпла — пустая текстура на юните (сравни баг GlassGrab друга: copy-проход без копии экрана); рабочий путь — glCopyTexSubImage2D, детали [[rustme-menu-tracers-merge]].
4. GL-константы аудит пройден: RGBA8=0x8058, DEPTH_COMPONENT24=0x81A6, DEPTH_ATTACHMENT=0x8D00 — совпадают с дизасмом форка. uniform'ы WET 1:1 I_method_7a5208f2 (HitThickness 0.98 runtime, не 0.6 из json).

**Ключевые гатчи (пережить и в другие фичи):**
- GL_CURRENT_PROGRAM = 0x8B8D (НЕ 0x8B8E — несуществующий pname → спам «1280 Invalid enum @ Post render» + glGetInteger возвращает 0). CustomFont использует 0x8B8D верно.
- Сэмплировать текстуру, аттачнутую к ЦЕЛЕВОМУ FBO, нельзя (feedback → драйвер молча пропускает рисование).
- game_stubs.jar пересобирать ВСЕМИ классами (GL20, GL30, нативный GL13, lwjglx GL11, lwjglx GL13, kotlin Function1) — частичная пересборка теряла классы → 38 errors.
- Python-генерация java-строк с GLSL: 
 в heredoc превращается в реальный перенос → «unclosed string literal»; генерить построчно с esc = line.replace(скобка, двойная) и хвостом chr(92)+'n'.
- LNK1168: DLL залочена пока игра запущена; rustme.exe убивается ТОЛЬКО юзером (taskkill/Stop-Process → «Отказано в доступе»).
- toggle-модуль обязан читать клавишу в TickEvent-подписке (регистрация в Modules ничего не слушает).
- far у форка = 243м (m10=-1.0004, near=0.05) — геометрия за 243м имеет depth ровно 1.0 = неотличима от неба.

**Источники для повторной попытки:** rockstar-client-src (шейдеры wet_world/rain_screen/sky_nebula, джава-обвязка IIiIII_Class9) — юзер может расшифровать больше ассетов. soyuz-master (C++, нативные хуки): их «Sky» = хуки glClearColor/glFogfv (запрещённый нам путь нативных хуков), подтверждение классического fixed-function GL; modules/ (Aimbot/ESP/Chams/Tracers/WallCheck/ChinaHat/AspectRatio) — источник идей.

**РЕВИЗИЯ ПАЙПЛАЙНА 09-10 (дизасм, дампы tools/decomp/r1..r9 — каждая цепочка снята и перепроверена):**
- **FBO-хелдер `iIlIiIiIiI` (ctor w,h,useDepth), поля public:** fb=lllIIiIiI, color-tex=iIlIIiIiI, depth-RB=illIIiIiI, useDepth=IIlIIiIiI, phys W/H=lIlIIiIiI/iiilIiIiI (для TexImage2D), logical W/H=IllIIiIiI/lilIIiIiI, clearColor=IiilIiIiI[4]. Методы: `iIiliIiIII(w,h)`=create (color: RGBA8-tex 32856 + фильтры/wrap; depth: `glRenderbufferStorage(36161, 33190=DEPTH_COMPONENT24)` → attach 36096), `IllIiIiIII()`=clear (mask 16384 + 0x100 при depth, depthFunc 1.0), `lllIiIiIII(bl)`=bind(+viewport при bl), `IIiliIiIII()`=unbind→0, `lliliIiIII(n)`=фильтры+кламп, `iiIliIiIII(w,h,bl)`=**framebufferViewOrtho**: ortho(0,w,h,0,1000,3000) + translate(0,0,−2000) + fullscreen-квад — выкладка результата на экран.
- **GL-константы liIlIiiIiI (GlStateManager-мост) сняты из clinit (javap):** IIllliiiI=36160 GL_FRAMEBUFFER, IliiiIiiI=36161 GL_RENDERBUFFER, lIllliiiI=36064 GL_COLOR_ATTACHMENT0, lliiiIiiI=36096 GL_DEPTH_ATTACHMENT (так же 36053/36054/36055 — DEPTH-семейство форматов, для swap не нужны). Подтверждает: depth в главном FBO = RENDERBUFFER, не текстура.
- **Главный цикл `lIliIiiIiI` (Minecraft facade, 55 методов):** runGameLoop=`IllillIiII(f,l)` → renderFrame=`llilllIiII(kind,f,l)` (kind=2 из игры). Порядок: setupCamera `iIIlllIiII(f)` — **мод снимает MV=2982/PROJ=2983/viewport=2978 в статики `lliIilliiI` через `iIiliiiiII(SP,flag)`** (glGetFloatv + gluUnProject центра + векторы направления из yaw/pitch) → fog → sky `IiilllIiII(-1,f)` → terrain `iIilllIiII` (если игрок <128+n*128) → сортировка → entities `IIilllIiII`+`iiiiIIliII` → outlines → particles → **weather-маски** (`liillllliI` до мира / `iIillllliI` после) → clouds → rain → HUD → в конце при Illillll — пост `IlIlllIiII(f,n)`.
- **Пост-группа `IllIiIiIiI`** (json-загрузчик): «targets»+«passes» из json (lllililIiI-ресурсы), каждый named-target = `new iIlIiIiIiI(w,h,true)` в карте lillIiIiI; pass `lIlIiIiIiI` = input-FBO → program → output-FBO, uniforms: ProjMatrix, InSize, OutSize, Time, ScreenSize (+AuxSize). FXAA выбирается `liiIllIiII(n)` (shaders/post/fxaa_of_Nx.json, кэш lllIllll[n] — массив, индексы 2/4 особые).
- **Погодный/масочный контроллер `lilllIliiI`** (вызывается из renderFrame ДО и ПОСЛЕ мира): очереди масок **IIiIIlIl[тип4][этап2]** (addLast через illiIlIl, лимит 16384), шейдер-программа IlliIlIl (lIliliiIiI): для масок `IlIIilliII(liiIIlIl)`, для обычного `IilllIiiI`. `liillllliI` = рисование очереди «до мира» (без lightmap), `iIillllliI` = «после» (lightmap 7425 switch по типу). Маска `lililIliiI.IiiIiiiiII` рисует billboard-квад по entity-данным (поворот из yaw/pitch, UV-размотка). Тут же очередь молний/трейсеров llliIlIl.
- **Рецепты 3D-функций (финал):** (1) оверлей поверх мира с окклюзией — из CheatIngame-фазы по статикам lliIilliiI (ESP-путь, depth-тест жив через их renderbuffer); (2) свой пост-процесс — свой `iIlIiIiIiI(w,h,true)` + свой pass; вход = копия color через glCopyTexSubImage2D (GlassGrab-путь) ИЛИ СВОЯ depth-текстура через DepthSwap (подмена RB после iIiliIiIII — ревалидировано 09-08); (3) silhouette-эффекты — по образцу weather-масок (offscreen RT → blur down/up → composite, порт ArmorBodyZoneGlow iiiiiiilii_59).

Связано: [[rustme-depth-swap]], [[rustme-custom-render]], [[rustme-next-features]].
