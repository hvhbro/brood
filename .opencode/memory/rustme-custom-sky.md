---
name: rustme-custom-sky
description: Custom Sky v3 (звёздное небо, порт skyshader/) — JumpCircle-style
  overlay-проход БЕЗ depth (depth в форке мёртв везде, probe DEAD 1.0/1.0):
  GlassGrab full-res+blur копии, маска неба = |scene-blur|<tol (гладкое=небо),
  проц. звёзды/луна пишутся только в маску; GLSL 130; стаб GL11 дедуплицирован
metadata:
  node_type: memory
  type: project
  originSessionId: sess_108ab9bb-329b-4492-a6b9-07aa20973a59
---

Custom Sky (09-11, DLL 13:33, 94 кл, ждёт теста). Модуль «Custom Sky» (Visuals) — 1:1 порт референса C:\rustme\skyshader (light client, режим Starry Sky: 3 гномонических звёздных чарта + twinkle, Млечный Путь fbm-шумом, процедурная луна с кратерами/ореолом, цвета c1/c2).

**МЕХАНИКА (как у референса — depth LEQUAL на дальнем плане):** CheatIngame.iliIiiIliI ДО super (после JumpCircle, ПЕРЕД Ambience) рисуем фуллскрин-треугольник `glVertex3f(-1,-1,1)/(3,-1,1)/(-1,3,1)` с VERT `gl_Position = gl_Vertex` (z=1 → NDC 1 → depth 1.0, матрицы игры игнорируются) + `glDepthFunc(GL_LEQUAL)` + `glDepthMask(false)`: мир (<1) fail, «небо» (cleared 1.0 — ваниль не пишет depth ни в небо, ни в солнце/луны/облака) pass → закрашивается шейдером. Ванильное солнце/луна/облака накрываются. GL-состояние (DEPTH_TEST/FUNC/WRITEMASK, CULL_FACE, TEXTURE_2D, program) сохраняется/восстанавливается через glGetInteger(int) — кэш GlStateManager консистентен.

**ГАТЧ aa≥2 (критично!):** при MSAA мир рисуется в отдельный MS-FBO, в главный блитится ТОЛЬКО цвет → главный depth пуст (1.0 ВЕЗДЕ) → треугольник закроет ВЕСЬ мир. Решение: depth-проба glReadPixels(центр 64×8, GL_DEPTH_COMPONENT + GL_FLOAT) раз в 3с; живой = есть значения < 0.9999; мёртв → не рисуем (грейсфул). Буфер-нуляк (шим не заполнил, как glGetFloat) → вердикт не менять. Лог переходов один раз: «depth probe: live/dead».

**ШЕЙДЕР:** GLSL **130** (не 120 — нужны dFdx/fwidth/pixelAngle; 130 доказано рабочим — их ui_batch.fsh #version 130 юзает fwidth). UBO референса → плоские uniform'ы (time, resolution=физ.пиксели, color1/2, params=(skyBright,speed,size,exposure), cameraData=(-yaw рад, pitch рад, fovY град, 0), starryParams=(density,brightness,milkyWay,moon)), out→gl_FragColor. VERT: gl_Position=gl_Vertex (deprecated, но валиден в 130+compat). ShaderUtil fail-fast: programId()==0 → модуль молча не рисует.

**УНИФОРМЫ-ИСТОЧНИКИ:** fovY из статик PROJ-буфера `lliIilliiI.lIIlIlIl` (перспективная сигнатура как Esp: |m5|>0.5, |m15|<0.5, m10<0; fovY=2*atan(1/m5), иначе 90); yaw/pitch — геттеры Entity `IIiIillIII`/`iilIIIlIII` (иерархия от player.getClass(); референс ждёт **отрицательный** yaw).

**СТАБЫ:** tools/compile_stubs/org/lwjglx/opengl/GL11.java был с дублями методов (glGetError/glGetInteger IntBuffer/группа merge — javac падал) — дедуплицирован при добавлении glDepthFunc/glReadPixels(FloatBuffer)/GL_LEQUAL/GL_DEPTH_FUNC/GL_DEPTH_WRITEMASK/GL_DEPTH_COMPONENT/GL_FLOAT/GL_TRIANGLES; game_stubs.jar пересобран командой `javac -d stubs_cls $(find compile_stubs -name "*.java") && jar cf game_stubs.jar -C stubs_cls .` (старый jar был кривой — только 3 класса, build/ попадал внутрь). GL20 = org.lwjgl.opengl (НЕ org.lwjglx!) — стаб org/lwjgl/opengl/GL20.java.



**ПЛАН V4 — ЧЕСТНЫЙ ХУК НЕБА (реверс собран, реализация — следующий шаг; юзер отклонил пиксельную эвристику как костыль):**
- Небо рендерит **RenderGlobal = rustme.llIlIiiIiI**, метод **IilIIIliII(F,I)V** (градиент+солнце textures/environment/sun.png+луна moon_phases.png+звёзды display list+войда; старый decomp lliliiiiii_2.java:2530-2640: sun-квад 30.0f по полю illiIIiiI=RL(sun), луна 20.0f с фазами IIilIllllI(), звёзды — callList IlliIIiiI с альфой Iliiiiiiil(f)*darken, войда-плоскость при lillillIII<0). **iiIIIIliII(liIlIliIiI, F)V = renderEntities** (после неё depth содержит рельеф+сущности — точка для тинта Ambience). Конструктор RenderGlobal: **(iilliIliiI)** (GameSettings). Порядок имён старый↔текущий СОВПАДАЕТ (IilIIIliII/iiIIIIliII есть в текущем javap).
- RenderGlobal STATEFUL (листы 69696 cap) — новый инстанс теряет состояние. Решение: **прокси-подкласс rustme.SkyRenderGlobal extends llIlIiiIiI + ПОЛЕВАЯ КОПИЯ** всех non-static полей оригинала (setAccessible; ссылки шарятся — состояние сохраняется); оверрайды: IilIIIliII → CustomSky.drawSkyPass() (процедурное небо opaque, depth off, SKY_VERT NDC z=1) + return (ванильное небо/солнце/луна/войда скипаются, terrain рисуется ПОСЛЕ и перекрывает небо естественно); iiIIIIliII → super (сущности) → Ambience.drawEntitiesPass() (тинт: треугольник z=1 + glDepthFunc(GL_GREATER) — depth уже содержит рельеф+сущности, небо 1.0 мимо; блендинг DST_COLOR/ZERO).
- **Холдеры RenderGlobal (поля Lrustme/llIlIiiIiI;):** rustme/IiIillliiI.IIiiIIll (pub final; этот класс держит world+RenderGlobal+ещё — вероятно EntityRenderer-аналог), rustme/IlilliiIiI.IiiilliiI, rustme/iIiiiiiliI.lIiIIIIil (private), rustme/lliIIIliiI.iilIiIIl. Свапать ВСЕ (это один инстанс); при world-reload поле меняется — детект «поле != прокси» → ре-свап. 
- Облака рисует EntityRenderer ОТДЕЛЬНЫМ методом (iIilllIiII-analog, проверка 128 + fog*128 — уровень облаков) — из прокси RenderGlobal не скипнуть; убирать подменой текстуры textures/environment/clouds.png → rustme.TransparentTexture в TextureManager (паттерн AntiOverlay pumpkin: tm=gs.IIiIiilliI(), карта tm.lIillIiiI, метод lliIilliII(rl, tex); rl = new lllililIiI("minecraft","textures/environment/clouds.png")).
- ГЛУБИНА НЕДОСТУПНА ВООБЩЕ (финально): проба DEAD 1.0/1.0 даже на entity-триггере; мир рендерится в отдельный таргет (MS-FBO при aa>=2 — getAaLevel в GraphicSettings), blit color-only. Far-plane трюки работают ТОЛЬКО изнутри world-прохода (см. прокси выше: тинт на iiIIIIliII-хвосте — depth уже с рельефом).

Связано: [[rustme-wet-world-shader]] (depth=RENDERBUFFER, MSAA-blit color-only — фундамент пробы), [[rustme-ambience]] (сосед по preOverlay, порядок: JumpCircle→CustomSky→Ambience), [[rustme-custom-render]] (SDF/шейдерная база).

**V3 (09-11 17:01, 106 кл) — ОТКАТ от world-pass: depth МЁРТВ ВЕЗДЕ.** Проба на entity-триггере (IIlIliliII) дала DEAD 1.0/1.0 — мир рендерится в MS-FBO/другой таргет, glReadPixels DEPTH возвращает чистую 1.0 в любой точке кадра. Far-plane трюки (LEQUAL/GREATER) отменены окончательно, world-pass триггер (IIlIliliII оверрайд) УДАЛЁН.

**V3 МЕХАНИКА = JumpCircle-style (по подсказке юзера — «JumpCircle же работает»): overlay-фаза БЕЗ depth.** CheatIngame.iliIiiIliI: JumpCircle.preOverlay → **GlassGrab.grab(ctx)** (full-res copyTex + пол-res blurTex — добавлены геттеры getCopyTex/getBlurTex) → **Ambience.onWorldPass** → **CustomSky.onWorldPass** → super (HUD).
- МАСКА НЕБА без depth: сэмплируем кадр (uScene) и его размытие (uBlur); |scene-blur| < tol = ГЛАДКИЙ пиксель = небо (небо — плавный градиент), иначе рельеф (текстурный). skyMask = 1-smoothstep(tol*0.6,tol,dist).
- CustomSky: SKY_FSH v2 — проц. небо (звёзды/луна/пыль из референса) в skyMask-пикселях, blend (SRC_ALPHA,*) с alpha=skyMask — рельеф не трогаем. Толер «Порог неба» слайдер 0.01..0.12 (деф 0.03).
- Ambience v3: TINT_FSH v2 — out = mix(scene*tint, scene, skyMask) opaque — тинтуем ТОЛЬКО рельеф, небо чистое. Порядок: Ambience ПЕРЕД CustomSky (иначе небо перезатрёт тинт).
- ГАТЧИ: distant fog-террейн гладкий → маска считает его небом (звёзды/тинт «протекают» у линии горизонта — лечится порогом); ванильные облака гладкие → заменяются небом (ок: у референса cancelClouds); GlassGrab.grab дергается каждый кадр (full-res copy + пол-res blur — приемлемо).
- Overlays.resolve СПАМ-баг (858 строк «resolved (21 widgets)»): resolved=true не ставился в success-ветке — ИСПРАВЛЕН (ставить флаг ДО return true!).

Связано: [[rustme-wet-world-shader]] (depth=RENDERBUFFER, MSAA-blit — фундамент), [[rustme-ambience]], [[rustme-viewmodel]] (носитель хука — триггер снят), [[rustme-custom-render]].