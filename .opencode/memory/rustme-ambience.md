---
name: rustme-ambience
description: Ambience v3 (тинт БЛОКОВ, небо чистое) — JumpCircle-style overlay-проход
  БЕЗ depth (глубина в форке недоступна): GlassGrab full-res+blur копии, маска
  неба = |scene-blur|<tol (гладкое=небо), out=mix(scene*tint, scene, mask);
  ColorPicker rockstar 1:1 (143×136, пресеты в presets.dat); белый цвет = обычный
  мир
metadata:
  node_type: memory
  type: project
  originSessionId: sess_108ab9bb-329b-4492-a6b9-07aa20973a59
---

**⚠ АКТУАЛЬНО v3 (09-11 17:01): тинт через grab+маску, НЕ fullscreen-multiply — юзер отверг v1/v2 («красит просто экран белый красный»: без depth небо от блоков на overlay не отделить).**
- V3 = JumpCircle-style БЕЗ depth (проба RenderUtil.depthProbeLive: DEAD 1.0/1.0 даже в world-pass — depth в форке недоступен, world-pass триггер IIlIliliII снят).
- CheatIngame порядок: JumpCircle.preOverlay → **GlassGrab.grab(ctx)** (full-res copyTex + пол-res blurTex; геттеры getCopyTex/getBlurTex добавлены) → **Ambience.onWorldPass** → **CustomSky.onWorldPass** → super (HUD).
- Шейдер TINT_FSH (uScene/uBlur/resolution/color/tol): skyMask = 1−smoothstep(tol*0.6,tol,|scene−blur|); out = mix(scene*tint, scene, skyMask) — тинтуется ТОЛЬКО текстурный рельеф, гладкое небо чистое. Opaque (blend off в проходе), SKY_VERT (NDC, матрицы мимо).
- Настройки: Color (пикер), Intensity (лерп к белому 1−k+k*c), Порог неба 0.01..0.12 (деф 0.03). ГАТЧ: гладкий туман у горизонта классифицируется как небо (тинт туда не доходит).
- Техника маски подробно — [[rustme-custom-sky]] (общая для CustomSky+Ambience).

Ambience + ColorPicker (09-11, DLL 12:53, 91 кл, ждёт теста).

**Ambience (modules/impl/Ambience.java):** тонировка мира = ПОЛНОЭКРАННЫЙ multiply-квад из CheatIngame.iliIiiIliI **ДО super.iliIiiIliI** (после JumpCircle.preOverlay) — HUD/меню рисуются после и не тонируются. `glBlendFunc(GL_DST_COLOR=0x0306, GL_ZERO=0)` → dst.rgb = src.rgb×dst.rgb, альфа сохраняется (1×dst.a). Цвет-настройка = МНОЖИТЕЛЬ: белый = обычный мир, «наполовину белый+фиолетовый» = слегка фиолетовый; FloatSetting Intensity дополнительно лерпит к белому (`1-k+k*channel`); при r,g,b≥0.999 квад не рисуем. Квад в scaled-координатах (liIIiIliiI res = new ScaledResolution(gs)); текстуру выключаем, после — glColor(1,1,1,1) + glBlendFunc восстановление: читаем GL_BLEND_SRC/DST через glGetInteger(0x0BE1/0x0BE0) (single-int форма работает, в отличие от буферной glGetFloat), при нуле — стандартный (SRC_ALPHA, ONE_MINUS_SRC_ALPHA). ColorSetting дефолт 0xFFFFFFFF, флаг picker=true.

**ColorPicker (rockstar iIii_Class12/Class149 1:1, в CheatMenuScreen):**
- Окно 143×136 (без альфа-секции; у rock с альфой 160), r7, BG@a229 + рамка OUTLINE@a89; заголовок 7px по центру y+7; xmark 10×10 r5 на (w−15,5); драг окна за любую свободную точку, кламп к экрану с полем 5; клик мимо окна = закрыть.
- SV-бокс (6,20) 114×70 r4 — drawRoundedRectShader 4 угла: TL=white, TR=hue(s1,v1), BL/BR=black (шейдер ROUND: c1=TL,c2=TR,c3=BL,c4=BR, top=mix(c1,c2,uv.x)); курсор 7×7 r2.5 белый + 5×5 r1.5 цвет. s растёт вправо, v убывает вниз (у rock ось s инвертирована — дешифровка неоднозначна, взяли классику).
- Hue-бар (w−18,20) 12×70: 6 сегментов радуги drawRect + рамка r4 (у rock текстура hue.png; у нас иконки только xmark/search/setting/logo/category — «plus» рисуется двумя квадами); маркер 8×2 white на (w−16, 22+64*hue); drag: hue=(py−22)/66.
- Текущий цвет 29×29 r5 на (6,h−36); пресеты 11×11 r4.5 с x=45, шаг 20, перенос при 45+f>143 → f=0,f2+=18; LMB применить, RMB удалить, «+» добавить текущий (макс 10); дефолты rock: 007AFF/34C759/FFCC00/FF3B30/9747FF. Выбранный (rgb==текущий) — белая рамка.
- Значение пишется ЖИВО в ColorSetting.argb при каждом изменении (consumer у rock так же). Пресеты персистятся в %LOCALAPPDATA%\RustMe\presets.dat (ConfigManager.save/loadPresets, тот же XOR-crypt; int[] big-endian).
- Строка настройки с пикером: SRow kind 6, h18, label 8px + чип текущего цвета 10×10 r3 справа (rock ColorSetting.createComponent: h17, чип 10×10); клик открывает пикер у мыши. Обычные ColorSetting (kind 5, свотчи) не тронуты.
- HSV↔RGB — ручные функции в CheatMenuScreen (НЕ java.awt.Color — java.desktop может отсутствовать в форк-JVM).

Связано: [[rustme-menu-elements]] (панель настроек), [[rustme-config-system]] (папка/шифрование), [[rustme-custom-render]] (SDF-шейдер).
