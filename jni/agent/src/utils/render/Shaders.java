package utils.render;

/**
 * GLSL-шейдеры (строки прямо в Java, как RoundedGlsl в expensive).
 * Координаты — в ФИЗИЧЕСКИХ пикселях экрана через gl_FragCoord
 * (texcoord через lwjglx-шимп в шейдер не приходит — проверено эмпирикой).
 */
public final class Shaders {

    private Shaders() {}

    /** Вершинный шейдер: фиксированный конвейер. */
    public static final String VERT = "" +
        "#version 120\n" +
        "void main() {\n" +
        "    gl_Position = ftransform();\n" +
        "}";

    /**
     * Скруглённый прямоугольник с 4-цветным градиентом (углы TL,TR,BL,BR).
     * rect = (x, y, w, h) в физ.пикселях; fbHeight — высота фреймбуфера
     * (gl_FragCoord.y считается снизу вверх, а мы работаем сверху вниз).
     */
    public static final String ROUND = "" +
        "#version 120\n" +
        "uniform vec4 rect;\n" +
        "uniform float radius;\n" +
        "uniform float RectSmoothness;\n" +
        "uniform float fbHeight;\n" +
        "uniform vec4 color1;\n" +
        "uniform vec4 color2;\n" +
        "uniform vec4 color3;\n" +
        "uniform vec4 color4;\n" +
        "\n" +
        "float roundedBox(vec2 p, vec2 halfSize, float rad) {\n" +
        "    vec2 q = abs(p) - halfSize + rad;\n" +
        "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;\n" +
        "}\n" +
        "\n" +
        "vec4 gradient(vec2 uv, vec4 c1, vec4 c2, vec4 c3, vec4 c4) {\n" +
        "    vec4 top = mix(c1, c2, uv.x);\n" +
        "    vec4 bottom = mix(c3, c4, uv.x);\n" +
        "    return mix(top, bottom, uv.y);\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    p.y = fbHeight - p.y;\n" +                       // верх-лево система
        "    vec2 local = p - rect.xy;\n" +
        "    vec2 halfSize = rect.zw * 0.5;\n" +
        "    float dist = roundedBox(local - halfSize, halfSize, radius);\n" +
        "    float alpha = 1.0 - smoothstep(-RectSmoothness, RectSmoothness, dist);\n" +
        "    if (alpha <= 0.0) discard;\n" +
        "    vec2 uv = clamp(local / rect.zw, 0.0, 1.0);\n" +
        "    vec4 c = gradient(uv, color1, color2, color3, color4);\n" +
        "    gl_FragColor = vec4(c.rgb, c.a * alpha);\n" +
        "}";

    /**
     * Скруглённый прямоугольник с РАЗНЫМИ радиусами углов (rockstar drawSquircle
     * с IIiii_Class8(TL,TR,BR,BL)): radii = (rTL, rTR, rBR, rBL) в физ.пикселях.
     * Выбор радиуса по квадранту gl_FragCoord (y уже перевёрнут: +y = вниз).
     * Остальное 1:1 как ROUND. Нужен хотбару rockstar (панель 1,0,7,8).
     */
    public static final String ROUND_CORNERS = "" +
        "#version 120\n" +
        "uniform vec4 rect;\n" +
        "uniform vec4 radii;\n" +
        "uniform float RectSmoothness;\n" +
        "uniform float fbHeight;\n" +
        "uniform vec4 color1;\n" +
        "uniform vec4 color2;\n" +
        "uniform vec4 color3;\n" +
        "uniform vec4 color4;\n" +
        "\n" +
        "float roundedBoxCorners(vec2 p, vec2 halfSize, vec4 r) {\n" +
        "    float rad = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);\n" +
        "    vec2 q = abs(p) - halfSize + rad;\n" +
        "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;\n" +
        "}\n" +
        "\n" +
        "vec4 gradient(vec2 uv, vec4 c1, vec4 c2, vec4 c3, vec4 c4) {\n" +
        "    vec4 top = mix(c1, c2, uv.x);\n" +
        "    vec4 bottom = mix(c3, c4, uv.x);\n" +
        "    return mix(top, bottom, uv.y);\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    p.y = fbHeight - p.y;\n" +                       // верх-лево система
        "    vec2 local = p - rect.xy;\n" +
        "    vec2 halfSize = rect.zw * 0.5;\n" +
        "    float dist = roundedBoxCorners(local - halfSize, halfSize, radii);\n" +
        "    float alpha = 1.0 - smoothstep(-RectSmoothness, RectSmoothness, dist);\n" +
        "    if (alpha <= 0.0) discard;\n" +
        "    vec2 uv = clamp(local / rect.zw, 0.0, 1.0);\n" +
        "    vec4 c = gradient(uv, color1, color2, color3, color4);\n" +
        "    gl_FragColor = vec4(c.rgb, c.a * alpha);\n" +
        "}";

    /**
     * Скруглённая РАМКА (только полоса по периметру, как drawRoundedBorder rock):
     * band = |dist| <= borderWidth, всё внутри/снаружи discard.
     */    public static final String BORDER = "" +
        "#version 120\n" +
        "uniform vec4 rect;\n" +
        "uniform float radius;\n" +
        "uniform float borderWidth;\n" +
        "uniform float RectSmoothness;\n" +
        "uniform float fbHeight;\n" +
        "uniform vec4 color;\n" +
        "\n" +
        "float roundedBox(vec2 p, vec2 halfSize, float rad) {\n" +
        "    vec2 q = abs(p) - halfSize + rad;\n" +
        "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    p.y = fbHeight - p.y;\n" +
        "    vec2 local = p - rect.xy;\n" +
        "    vec2 halfSize = rect.zw * 0.5;\n" +
        "    float dist = roundedBox(local - halfSize, halfSize, radius);\n" +
        "    float band = smoothstep(-RectSmoothness, RectSmoothness, dist + borderWidth)\n" +
        "               * (1.0 - smoothstep(-RectSmoothness, RectSmoothness, dist - borderWidth));\n" +
        "    if (band <= 0.0) discard;\n" +
        "    gl_FragColor = vec4(color.rgb, color.a * band);\n" +
        "}";

    /** Копия экрана в FBO (uv по gl_FragCoord / размеру цели). */
    public static final String COPY = "" +
        "#version 120\n" +
        "uniform sampler2D tex;\n" +
        "uniform vec2 uTexel; // 1/targetSize\n" +
        "void main() {\n" +
        "    vec2 uv = gl_FragCoord.xy * uTexel;\n" +
        "    gl_FragColor = texture2D(tex, uv);\n" +
        "}";

    /**
     * Иконка из атласа (fixed-pipeline alt): texcoord НЕ живёт через lwjglx
     * (13.4.2 — s/t не доходит до шейдера), поэтому UV строим в фрагменте из
     * gl_FragCoord: uniform uvRect = клетка атласа в ФИЗИЧЕСКИХ пикселях квада.
     * color ARGB-тонировка (иконка в атласе белая, alpha=coverage).
     */
    public static final String ICON = "" +
        "#version 120\n" +
        "uniform sampler2D atlas;\n" +
        "uniform vec4 quad;\n" +      // x,y,w,h квада в физ.пикселях
        "uniform vec4 uvRect;\n" +    // u0,v0,u1,v1 клетки атласа
        "uniform float fbHeight;\n" +
        "uniform vec4 color;\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    p.y = fbHeight - p.y;\n" +
        "    vec2 local = (p - quad.xy) / quad.zw;\n" +
        "    if (local.x < 0.0 || local.x > 1.0 || local.y < 0.0 || local.y > 1.0) discard;\n" +
        "    vec2 uv = uvRect.xy + local * (uvRect.zw - uvRect.xy);\n" +
        "    vec4 s = texture2D(atlas, uv);\n" +
        "    gl_FragColor = vec4(color.rgb * s.r, color.a * s.a);\n" +
        "}";

    /** 9-tap gaussian (expensive-стиль, шаг в текселях). uDir = (1,0)/(0,1). */
    public static final String BLUR = "" +
        "#version 120\n" +
        "uniform sampler2D tex;\n" +
        "uniform vec2 uTexel;\n" +
        "uniform vec2 uDir;\n" +
        "void main() {\n" +
        "    vec2 uv = gl_FragCoord.xy * uTexel;\n" +
        "    vec2 o1 = uDir * uTexel * 1.3846153846;\n" +
        "    vec2 o2 = uDir * uTexel * 3.2307692308;\n" +
        "    vec4 c = texture2D(tex, uv) * 0.2270270270;\n" +
        "    c += (texture2D(tex, uv + o1) + texture2D(tex, uv - o1)) * 0.3162162162;\n" +
        "    c += (texture2D(tex, uv + o2) + texture2D(tex, uv - o2)) * 0.0702702703;\n" +
        "    gl_FragColor = c;\n" +
        "}";

    /**
     * Окно-«стекло»: сэмплит размытый экран-текстуру внутри скруглённого
     * прямоугольника (rock: backdrop blur окна + тёмный тинт).
     * uv.y флипается: FBO-текстура v=0 снизу, экран у нас сверху-вниз.
     */
    public static final String WINDOW = "" +
        "#version 120\n" +
        "uniform sampler2D tex;\n" +
        "uniform vec4 rect;\n" +
        "uniform float radius;\n" +
        "uniform float RectSmoothness;\n" +
        "uniform float fbHeight;\n" +
        "uniform float fbWidth;\n" +
        "uniform vec4 tint;\n" +
        "uniform float blurMix;\n" +
        "\n" +
        "float roundedBox(vec2 p, vec2 halfSize, float rad) {\n" +
        "    vec2 q = abs(p) - halfSize + rad;\n" +
        "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    p.y = fbHeight - p.y;\n" +
        "    vec2 local = p - rect.xy;\n" +
        "    vec2 halfSize = rect.zw * 0.5;\n" +
        "    float dist = roundedBox(local - halfSize, halfSize, radius);\n" +
        "    float alpha = 1.0 - smoothstep(-RectSmoothness, RectSmoothness, dist);\n" +
        "    if (alpha <= 0.0) discard;\n" +
        "    // uv = ЭКРАННАЯ позиция фрагмента (gl_FragCoord — снизу-вверх, и\n" +
        "    // glCopyTexSubImage2D кладёт бэкбуфер в текстуру так же: v=0 = низ).\n" +
        "    // blur-текстура — половинный снапшот ВСЕГО экрана, поэтому стекло\n" +
        "    // показывает задник ПОЗАДИ окна (старый uv=local/rect.zw сжимал весь\n" +
        "    // экран в окно — «блюр на весь экран»).\n" +
        "    vec2 uv = gl_FragCoord.xy / vec2(fbWidth, fbHeight);\n" +
        "    vec3 blur = texture2D(tex, uv).rgb;\n" +
        "    gl_FragColor = vec4(mix(tint.rgb, blur.rgb, blurMix), tint.a * alpha);\n" +
        "}";

    /**
     * MSDF-текст — порт fragment.fsh из expensive, но UV вычисляются из
     * gl_FragCoord по uniform-кваду глифа (glTexCoord2f через immediate mode
     * lwjglx в GLSL НЕ доходит — подтверждено: квад сэмплился в одной точке,
     * «квадратики»; gl_TexCoord остаётся константой, ZNANIA 12.3 п.8).
     * quad — ФИЗИЧЕСКИЙ квад глифа (проекция через фактические MV/PR),
     * поэтому UV-маппинг совпадает с растеризацией при любой матрице.
     * alpha-тест на время отрисовки выключается (режет градиент в ступеньки).
     */
    public static final String MSDF = "" +
        "#version 120\n" +
        "uniform sampler2D atlas;\n" +
        "uniform vec4 quad;       // x,y,w,h глифа в физ.пикселях (y сверху вниз)\n" +
        "uniform vec4 uvRect;     // minU, minV, maxU, maxV\n" +
        "uniform float pxRange;   // distanceRange, пересчитанный в экранные px\n" +
        "uniform float thickness; // сдвиг края в px атласа (0 = обычный текст)\n" +
        "uniform float smoothness;\n" +
        "uniform float fbHeight;\n" +
        "uniform vec4 color;      // RGBA 0..1\n" +
        "\n" +
        "float median(vec3 v) {\n" +
        "    return max(min(v.r, v.g), min(max(v.r, v.g), v.b));\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    p.y = fbHeight - p.y;                  // верх-лево система\n" +
        "    vec2 local = p - quad.xy;\n" +
        "    vec2 uv = uvRect.xy + (local / quad.zw) * (uvRect.zw - uvRect.xy);\n" +
        "    float dist = median(texture2D(atlas, uv).rgb) - 0.5 + thickness;\n" +
        "    float alpha = smoothstep(-smoothness, smoothness, dist * pxRange);\n" +
        "    gl_FragColor = vec4(color.rgb, color.a * alpha);\n" +
        "}";

    /**
     * ДИАГНОСТИЧЕСКИЙ MSDF-шейдер (включается CustomFont.DEBUG_SHADER):
     * R = сырой медианный сэмпл атласа (форма буквы в R-канале = сэмплинг ок),
     * G = вычисленная alpha (если R.shape, а G=1 везде — pxRange/смус),
     * B = fract(uv.x*8) (полосы = uv варьируется; ровный цвет = uv константа).
     * A = 1. По одному скрину разделяет: сэмплинг / smoothstep / uniform-путь.
     */
    public static final String MSDF_DEBUG = "" +
        "#version 120\n" +
        "uniform sampler2D atlas;\n" +
        "uniform vec4 quad;\n" +
        "uniform vec4 uvRect;\n" +
        "uniform float pxRange;\n" +
        "uniform float thickness;\n" +
        "uniform float smoothness;\n" +
        "uniform float fbHeight;\n" +
        "uniform vec4 color;\n" +
        "\n" +
        "float median(vec3 v) {\n" +
        "    return max(min(v.r, v.g), min(max(v.r, v.g), v.b));\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    p.y = fbHeight - p.y;\n" +
        "    vec2 local = p - quad.xy;\n" +
        "    vec2 uv = uvRect.xy + (local / quad.zw) * (uvRect.zw - uvRect.xy);\n" +
        "    float m = median(texture2D(atlas, uv).rgb);\n" +
        "    float dist = m - 0.5 + thickness;\n" +
        "    float alpha = smoothstep(-smoothness, smoothness, dist * pxRange);\n" +
        "    gl_FragColor = vec4(m, alpha, fract(uv.x * 8.0), 1.0);\n" +
        "}";

    /**
     * ИКОНКИ ПРЕДМЕТОВ (GearESP): прямой сэмпл их блок-атласа по uniform-UV.
     * Механизм как у MSDF (texcoord через lwjglx не доходит, 13.4.2), но без
     * медианы — RGBA спрайты. Атлас уже загружен и дешифрован игрой; UV спрайтов
     * читаем через их ItemModelMesher (см. ItemIcons).
     */
    public static final String ITEM_ICON = "" +
        "#version 120\n" +
        "uniform sampler2D atlas;\n" +
        "uniform vec4 rect;      // x,y,w,h в физ.пикселях (y сверху вниз)\n" +
        "uniform vec4 uvRect;    // minU, minV, maxU, maxV\n" +
        "uniform float fbHeight;\n" +
        "uniform vec4 tint;\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    p.y = fbHeight - p.y;\n" +
        "    vec2 local = p - rect.xy;\n" +
        "    vec2 uv = uvRect.xy + (local / rect.zw) * (uvRect.zw - uvRect.xy);\n" +
        "    vec4 tex = texture2D(atlas, uv);\n" +
        "    gl_FragColor = vec4(tex.rgb * tint.rgb, tex.a * tint.a);\n" +
        "}";

    /**
     * JUMP_FSH — порт 1:1 kimiko jumpdistort.fsh (screen-space волна от круга
     * прыжка: искажение UV по кольцу на плоскости земли, 4-цветный градиент по
     * углу, турбулентность, сатурация, подкраска, дым-конус raymarch'ом 24 шага).
     * Отличия от оригинала — только вынужденные: depth-текстуры в пайплайне форка
     * НЕТ (wet-world анализ), поэтому: луч строится из камеры через пиксель по
     * камерным базисам (uCamF/R/U + tanF), окклюзии по depth нет (волна видна
     * сквозь рельеф), дым не обрезается сценой. В остальном математика 1:1.
     */
    public static final String JUMP_FSH = "" +
        "#version 120\n" +
        "uniform sampler2D uScene;\n" +
        "uniform vec2 uFbSize;\n" +
        "uniform vec3 uCamF;\n" +
        "uniform vec3 uCamR;\n" +
        "uniform vec3 uCamU;\n" +
        "uniform float uTanF;\n" +
        "uniform float uAspect;\n" +
        "uniform float uTime;\n" +
        "uniform float uWarpAmp;\n" +
        "uniform float uTintAmount;\n" +
        "uniform float uSatFactor;\n" +
        "uniform float uGlowOn;\n" +
        "uniform float uGlowIntensity;\n" +
        "uniform float uGlowHeightMul;\n" +
        "uniform float uGlowWidthMul;\n" +
        "uniform float uGlowTint;\n" +
        "uniform float uGlowAlpha;\n" +
        "uniform int uCount;\n" +
        "uniform vec4 uData[16];\n" +
        "uniform vec4 uColors[32];\n" +
        "\n" +
        "const float PI = 3.14159265;\n" +
        "const float TAU = 6.28318531;\n" +
        "\n" +
        "vec3 ringColorByAngle(int i, float ang) {\n" +
        "    int base = i * 4;\n" +
        "    float t = (ang < 0.0 ? ang + TAU : ang) / TAU * 4.0;\n" +
        "    int seg = int(mod(floor(t), 4.0));\n    int nx = int(mod(float(seg) + 1.0, 4.0));\n" +
        "    float f = fract(t);\n" +
        "    vec3 a = uColors[base + seg].rgb;\n" +
        "    vec3 b = uColors[base + nx].rgb;\n" +
        "    return mix(a, b, f);\n" +
        "}\n" +
        "\n" +
        "vec3 ringColor(int i, vec2 dir) {\n" +
        "    return ringColorByAngle(i, atan(dir.y, dir.x));\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 uv = gl_FragCoord.xy / uFbSize;\n" +
        "    float aspect = uAspect;\n" +
        "    float time = uTime;\n" +
        "    float warpAmp = uWarpAmp;\n" +
        "    float tintAmount = uTintAmount;\n" +
        "    float satFactor = uSatFactor;\n" +
        "    float glowOn = uGlowOn;\n" +
        "    float glowIntensity = uGlowIntensity;\n" +
        "    float glowHeightMul = uGlowHeightMul;\n" +
        "    float glowWidthMul = uGlowWidthMul;\n" +
        "    float glowTint = uGlowTint;\n" +
        "    float glowAlpha = uGlowAlpha;\n" +
        "\n" +
        "    float CONE_HEIGHT = 1.6 * glowHeightMul;\n" +
        "\n" +
        "    vec2 ndc = uv * 2.0 - 1.0;\n" +
        "    vec3 dirView = vec3(ndc.x * uTanF * aspect, ndc.y * uTanF, -1.0);\n" +
        "    vec3 rayDir = normalize(uCamR * dirView.x + uCamU * dirView.y + uCamF);\n" +
        "\n" +
        "    vec2 offset = vec2(0.0);\n" +
        "    float waveInfluence = 0.0;\n" +
        "    float tintAccum = 0.0;\n" +
        "    vec3 tintColor = vec3(0.0);\n" +
        "    float coneCovAccum = 0.0;\n" +
        "    vec3 coneGlow = vec3(0.0);\n" +
        "    for (int i = 0; i < 8; i++) {\n" +
        "        if (i >= uCount) break;\n" +
        "        vec3 center = uData[i * 2].xyz;\n" +
        "        float ringRadius = uData[i * 2].w;\n" +
        "        float ringWidth = max(uData[i * 2 + 1].x, 1e-4);\n" +
        "        float amp = uData[i * 2 + 1].y;\n" +
        "        float footprint = uData[i * 2 + 1].z;\n" +
        "        float env = uData[i * 2 + 1].w;\n" +
        "\n" +
        "        if (glowOn > 0.5 && env > 0.001 && abs(rayDir.y) > 1e-5) {\n" +
        "            float smokeH = footprint * CONE_HEIGHT * env;\n" +
        "            float wallR = max(ringRadius, 1e-3);\n" +
        "            float wallHalf = max(ringWidth * 1.6, footprint * 0.10) * glowWidthMul;\n" +
        "            if (smokeH > 1e-4) {\n" +
        "                float tA = center.y / rayDir.y;\n" +
        "                float tB = (center.y + smokeH) / rayDir.y;\n" +
        "                float tLo = min(tA, tB);\n" +
        "                float tHi = max(tA, tB);\n" +
        "                tLo = max(tLo, 0.0);\n" +
        "                float maxHalf = wallHalf * 2.3;\n" +
        "                float outerMax = wallR + footprint * 0.04 + maxHalf;\n" +
        "                float innerMin = max(0.0, wallR - footprint * 0.07 - maxHalf);\n" +
        "                vec2 oXZ = -center.xz;\n" +
        "                vec2 dXZ = rayDir.xz;\n" +
        "                float aa = dot(dXZ, dXZ);\n" +
        "                float bb = dot(oXZ, dXZ);\n" +
        "                float rLoSq = dot(oXZ + dXZ * tLo, oXZ + dXZ * tLo);\n" +
        "                float rHiSq = dot(oXZ + dXZ * tHi, oXZ + dXZ * tHi);\n" +
        "                float rMaxSq = max(rLoSq, rHiSq);\n" +
        "                float rMinSq;\n" +
        "                if (aa > 1e-12) {\n" +
        "                    float tStar = clamp(-bb / aa, tLo, tHi);\n" +
        "                    vec2 pStar = oXZ + dXZ * tStar;\n" +
        "                    rMinSq = dot(pStar, pStar);\n" +
        "                } else {\n" +
        "                    rMinSq = min(rLoSq, rHiSq);\n" +
        "                }\n" +
        "                bool radialMiss = rMinSq > outerMax * outerMax || rMaxSq < innerMin * innerMin;\n" +
        "                if (tHi > tLo && !radialMiss) {\n" +
        "                    const int STEPS = 24;\n" +
        "                    float stepLen = (tHi - tLo) / float(STEPS);\n" +
        "                    float accum = 0.0;\n" +
        "                    vec3 accumCol = vec3(0.0);\n" +
        "                    for (int st = 0; st < STEPS; st++) {\n" +
        "                        float t = tLo + (float(st) + 0.5) * stepLen;\n" +
        "                        vec3 pos = rayDir * t;\n" +
        "                        float hWorld = pos.y - center.y;\n" +
        "                        if (hWorld <= 0.0 || hWorld >= smokeH) {\n" +
        "                            continue;\n" +
        "                        }\n" +
        "                        float ct = hWorld / smokeH;\n" +
        "                        vec2 cradial = pos.xz - center.xz;\n" +
        "                        float crd = length(cradial);\n" +
        "                        float halfHere = wallHalf * (1.0 + ct * 1.3);\n" +
        "                        float bandLo = wallR - footprint * 0.07 - halfHere;\n" +
        "                        float bandHi = wallR + footprint * 0.04 + halfHere;\n" +
        "                        if (crd <= bandLo || crd >= bandHi) {\n" +
        "                            continue;\n" +
        "                        }\n" +
        "                        float ang = atan(cradial.y, cradial.x);\n" +
        "                        float ripple = sin(ang * 3.0 + ct * 4.0 + time * 0.8)\n" +
        "                                     + 0.5 * sin(ang * 6.0 - ct * 5.0 - time * 1.1);\n" +
        "                        ripple = ripple / 1.5;\n" +
        "                        float wobble = ripple < 0.0 ? ripple * 0.07 : ripple * 0.04;\n" +
        "                        float curtainR = wallR + footprint * wobble;\n" +
        "                        float halfT = halfHere;\n" +
        "                        float radialDist = abs(crd - curtainR);\n" +
        "                        if (radialDist >= halfT) {\n" +
        "                            continue;\n" +
        "                        }\n" +
        "                        float across = 1.0 - smoothstep(halfT * 0.35, halfT, radialDist);\n" +
        "                        float churn = 0.78 + 0.22 * sin(ang * 5.0 + time * 2.5 - ct * 6.0);\n" +
        "                        float rise = (1.0 - ct);\n" +
        "                        rise *= rise;\n" +
        "                        float dens = across * rise * churn * env;\n" +
        "                        if (dens <= 0.0) {\n" +
        "                            continue;\n" +
        "                        }\n" +
        "                        accum += dens * stepLen;\n" +
        "                        accumCol += ringColorByAngle(i, ang) * dens;\n" +
        "                    }\n" +
        "                    if (accum > 1e-4) {\n" +
        "                        float cov = 1.0 - exp(-accum * 2.2);\n" +
        "                        coneCovAccum = coneCovAccum + cov - coneCovAccum * cov;\n" +
        "                        coneGlow += (accumCol / accum) * cov;\n" +
        "                    }\n" +
        "                }\n" +
        "            }\n" +
        "        }\n" +
        "\n" +
        "        if (abs(rayDir.y) < 1e-4) {\n" +
        "            continue;\n" +
        "        }\n" +
        "        float tPlane = center.y / rayDir.y;\n" +
        "        if (tPlane <= 0.0) {\n" +
        "            continue;\n" +
        "        }\n" +
        "        vec3 hit = rayDir * tPlane;\n" +
        "        vec2 d = hit.xz - center.xz;\n" +
        "        float dist = length(d);\n" +
        "        vec2 dir = dist > 1e-5 ? d / dist : vec2(0.0);\n" +
        "        float w = (dist - ringRadius) / ringWidth;\n" +
        "        float band = abs(w) < 1.0 ? smoothstep(0.0, 1.0, 1.0 - abs(w)) * env : 0.0;\n" +
        "        if (band <= 0.0) {\n" +
        "            continue;\n" +
        "        }\n" +
        "        waveInfluence = waveInfluence + band - waveInfluence * band;\n" +
        "        tintColor += ringColor(i, dir) * band;\n" +
        "        tintAccum += band;\n" +
        "        float wave = sin(w * PI) * (1.0 - abs(w));\n" +
        "        vec2 screenDir = dir;\n" +
        "        screenDir.x /= aspect;\n" +
        "        offset += screenDir * wave * amp;\n" +
        "        if (warpAmp > 0.0) {\n" +
        "            vec2 turb = vec2(\n" +
        "                sin(hit.z * 2.0 + time * 3.0) + 0.5 * sin(hit.z * 4.0 - time * 2.0),\n" +
        "                cos(hit.x * 2.0 - time * 3.0) + 0.5 * cos(hit.x * 4.0 + time * 2.0)\n" +
        "            );\n" +
        "            turb.x /= aspect;\n" +
        "            offset += turb * warpAmp * band;\n" +
        "        }\n" +
        "    }\n" +
        "\n" +
        "    vec3 col = texture2D(uScene, uv + offset).rgb;\n" +
        "\n" +
        "    if (abs(satFactor - 1.0) > 0.001 && waveInfluence > 0.001) {\n" +
        "        float f = mix(1.0, satFactor, waveInfluence);\n" +
        "        float lum = dot(col, vec3(0.299, 0.587, 0.114));\n" +
        "        col = max(mix(vec3(lum), col, f), vec3(0.0));\n" +
        "    }\n" +
        "\n" +
        "    if (tintAmount > 0.001 && tintAccum > 0.001) {\n" +
        "        vec3 tint = tintColor / tintAccum;\n" +
        "        col += tint * (tintAmount * waveInfluence);\n" +
        "    }\n" +
        "\n" +
        "    if (glowOn > 0.5 && coneCovAccum > 0.001) {\n" +
        "        vec3 smoke = coneGlow / max(coneCovAccum, 1e-4);\n" +
        "        float smokeLum = dot(smoke, vec3(0.299, 0.587, 0.114));\n" +
        "        smoke = mix(vec3(smokeLum), smoke, clamp(glowTint, 0.0, 1.0));\n" +
        "        col += smoke * (glowIntensity * coneCovAccum * glowAlpha);\n" +
        "    }\n" +
        "\n" +
        "    gl_FragColor = vec4(min(col, vec3(1.0)), 1.0);\n" +
        "}";

    /**
     * Вершинный: NDC-треугольник напрямую (z=1 -> глубина 1.0), матрицы игры
     * не участвуют. #version 120 + gl_Vertex: те же конструкции, что весь
     * fixed pipeline игры — компилируется ВЕЗДЕ включая строгий AMD
     * (130-й gl_Vertex там не собирался → GL ERROR 1280).
     */
    public static final String SKY_VERT = "" +
        "#version 120\n" +
        "void main() {\n" +
        "    gl_Position = gl_Vertex;\n" +
        "}";

    /**
     * CustomSky — 1:1 порт skyshader_starry.fsh (референс skyshader/, light
     * client) на GLSL 120 (как весь остальной наш GL и blit-шейдеры форка —
     * AMD-safe; было 130 с dFdx/dFdy/fwidth и gl_FragColor-стилем).
     * Производные заменены аналитикой: pixelAngle = fovY/scrH (то же самое,
     * что мерял dFdx, но стабильнее), moonAA — тем же членом (fwidth(moonR)
     * и был равен pixelAngle/tan(1.65°) с точностью до дисторсии).
     * ЧИСТАЯ процедурная версия: uv → cameraRay(uv*2-1) → градиент/Млечный
     * Путь/звёзды/луна → НЕПРОЗРАЧНОЕ небо. Никаких сэмплеров и масок:
     * рисуется ВМЕСТО ванильного неба (override IilIIIliII в CheatRenderGlobal),
     * террейн идёт после и сам перекрывает небо там, где нужно.
     * Отличия от оригинала — вынужденные: UBO -> плоские uniform'ы,
     * out fragColor -> gl_FragColor.
     */
    public static final String SKY_FSH = "" +
        "#version 120\n" +
        "uniform vec2 resolution;\n" +
        "uniform vec3 color1;\n" +
        "uniform vec3 color2;\n" +
        "uniform float time;\n" +
        "// alpha (яркость неба) / speed (twinkle) / size (звёзды) / intensity (экспозиция)\n" +
        "uniform vec4 params;\n" +
        "// отрицательный yaw (рад), pitch (рад), вертикальный FOV (град), -\n" +
        "uniform vec4 cameraData;\n" +
        "// density / brightness / milkyWay / moon\n" +
        "uniform vec4 starryParams;\n" +
        "\n" +
        "float hash13(vec3 p) {\n" +
        "    p = fract(p * 0.1031);\n" +
        "    p += dot(p, p.zyx + 31.32);\n" +
        "    return fract((p.x + p.y) * p.z);\n" +
        "}\n" +
        "\n" +
        "float noise3(vec3 p) {\n" +
        "    vec3 i = floor(p), f = fract(p);\n" +
        "    vec3 u = f * f * (3.0 - 2.0 * f);\n" +
        "    return mix(mix(mix(hash13(i), hash13(i+vec3(1,0,0)),u.x),\n" +
        "                   mix(hash13(i+vec3(0,1,0)), hash13(i+vec3(1,1,0)),u.x),u.y),\n" +
        "               mix(mix(hash13(i+vec3(0,0,1)), hash13(i+vec3(1,0,1)),u.x),\n" +
        "                   mix(hash13(i+vec3(0,1,1)), hash13(i+vec3(1,1,1)),u.x),u.y),u.z);\n" +
        "}\n" +
        "\n" +
        "float fbm3(vec3 p) {\n" +
        "    float v = 0.0, a = 0.5;\n" +
        "    for (int i=0; i<4; ++i) {\n" +
        "        v += a * noise3(p);\n" +
        "        p = p * 2.03 + vec3(11.7,3.1,19.2);\n" +
        "        a *= 0.5;\n" +
        "    }\n" +
        "    return v;\n" +
        "}\n" +
        "\n" +
        "vec3 cameraRay(vec2 uv) {\n" +
        "    float yaw = cameraData.x, pitch = cameraData.y;\n" +
        "    float cy=cos(yaw), sy=sin(yaw), cp=cos(pitch), sp=sin(pitch);\n" +
        "    // MC: yaw 0 смотрит на +Z, вправо экрана -X; положительный pitch вниз.\n" +
        "    vec3 forward = vec3(sy*cp, -sp, cy*cp);\n" +
        "    vec3 right = vec3(-cy, 0.0, sy);\n" +
        "    vec3 up = vec3(sy*sp, cp, cy*sp);\n" +
        "    vec2 safeRes = max(resolution, vec2(1.0));\n" +
        "    float lens = tan(radians(clamp(cameraData.z, 1.0, 170.0))*0.5);\n" +
        "    return normalize(forward + right * uv.x * lens * safeRes.x/safeRes.y\n" +
        "                             + up * uv.y * lens);\n" +
        "}\n" +
        "\n" +
        "// Три перекрывающихся гномонических чарта — без полюсов/швов. У каждой\n" +
        "// звезды своё мировое положение, мерцает только яркость.\n" +
        "vec3 starChart(vec3 d, float chartSeed, float pixelAngle) {\n" +
        "    float axis = abs(d.z);\n" +
        "    vec2 p = d.xy / max(axis, 0.35) * 78.0;\n" +
        "    float chartWeight = smoothstep(0.48, 0.76, axis);\n" +
        "    if (axis <= 0.48) return vec3(0.0);\n" +
        "    vec2 cell = floor(p);\n" +
        "    vec3 light = vec3(0.0);\n" +
        "    float density = clamp(starryParams.x,0.25,2.0);\n" +
        "    float size = mix(0.60,1.65,(clamp(params.z,1.0,20.0)-1.0)/19.0);\n" +
        "    float seed = chartSeed + (d.z < 0.0 ? 47.0 : 0.0);\n" +
        "    for (int y=-1; y<=1; ++y) {\n" +
        "        for (int x=-1; x<=1; ++x) {\n" +
        "            vec2 id = cell + vec2(float(x),float(y));\n" +
        "            float h = hash13(vec3(id,seed));\n" +
        "            if (h > 0.095*density) continue;\n" +
        "            float a = hash13(vec3(id+13.4,seed+8.7));\n" +
        "            float b = hash13(vec3(id+57.8,seed+21.2));\n" +
        "            vec2 center = id + 0.22 + 0.56*vec2(a,b);\n" +
        "            vec3 starDir=normalize(vec3(center/78.0,1.0));\n" +
        "            starDir.z *= (d.z < 0.0 ? -1.0 : 1.0);\n" +
        "            float radius = (0.040 + 0.060*pow(a,7.0))*size/78.0;\n" +
        "            float sigma = max(radius, pixelAngle*0.43);\n" +
        "            float dist2 = dot(d-starDir,d-starDir);\n" +
        "            float profile = exp(-0.5*dist2/(sigma*sigma));\n" +
        "            float coverage = min(1.0, radius*radius/(sigma*sigma));\n" +
        "            float lum = mix(0.55,3.8,pow(b,5.0));\n" +
        "            float twinkle = 1.0 + 0.075*sin(time*clamp(params.y,0.1,5.0)\n" +
        "                                          *(0.65+1.25*a)+b*62.83);\n" +
        "            vec3 tint = mix(vec3(0.73,0.84,1.0),vec3(1.0,0.91,0.76),a);\n" +
        "            light += tint*profile*coverage*lum*twinkle*chartWeight;\n" +
        "        }\n" +
        "    }\n" +
        "    return light;\n" +
        "}\n" +
        "\n" +
        "float crater(vec3 n, vec3 center, float radius) {\n" +
        "    float q=length(n-normalize(center))/radius;\n" +
        "    return -0.13*exp(-q*q*3.0)+0.075*exp(-pow((q-0.90)*7.0,2.0));\n" +
        "}\n" +
        "\n" +
        "vec3 lunarSurface(vec2 q) {\n" +
        "    vec3 n=vec3(q,sqrt(max(0.0,1.0-dot(q,q))));\n" +
        "    float coarse=fbm3(n*5.5+vec3(4.2,11.1,8.7));\n" +
        "    float fine=noise3(n*95.0+vec3(14.0));\n" +
        "    float maria=smoothstep(0.43,0.65,coarse+0.06*n.x-0.035*n.y);\n" +
        "    float albedo=0.91-0.34*maria+0.10*(fine-0.5);\n" +
        "    albedo += crater(n,vec3(-0.38,-0.45,0.81),0.16);\n" +
        "    albedo += crater(n,vec3(0.39,-0.28,0.88),0.12);\n" +
        "    albedo += crater(n,vec3(0.60,0.35,0.71),0.105);\n" +
        "    albedo += crater(n,vec3(-0.62,0.22,0.75),0.085);\n" +
        "    albedo += crater(n,vec3(0.09,0.55,0.83),0.075);\n" +
        "    float illumination=max(dot(n,normalize(vec3(-0.34,0.22,0.91))),0.0);\n" +
        "    float limb=0.82+0.18*sqrt(n.z);\n" +
        "    return vec3(1.0,0.965,0.90)*albedo*(0.055+1.65*illumination)*limb;\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 uv = gl_FragCoord.xy / max(resolution, vec2(1.0));\n" +
        "    vec3 d = cameraRay(uv * 2.0 - 1.0);\n" +
        // Аналитический угол пикселя (вместо dFdx: то же значение =
        // fovY/высоту, без деривативов — GLSL 120, AMD-safe).
        "    float pixelAngle = radians(clamp(cameraData.z, 1.0, 170.0)) / max(resolution.y, 1.0);\n" +
        "    float visibility=smoothstep(-0.025,0.22,d.y);\n" +
        "    vec3 c1=clamp(color1,vec3(0.0),vec3(1.0));\n" +
        "    vec3 c2=clamp(color2,vec3(0.0),vec3(1.0));\n" +
        "    vec3 sky=mix(vec3(0.003,0.005,0.012),vec3(0.024,0.044,0.092),\n" +
        "                 smoothstep(-0.18,0.12,d.y));\n" +
        "    float haze=exp(-pow((d.y-0.025)*7.5,2.0))*smoothstep(-0.20,0.015,d.y);\n" +
        "    sky += mix(vec3(0.023,0.045,0.084),c1*0.13,0.23)*haze;\n" +
        "\n" +
        "    // Млечный Путь: пылевая полоса в мировых координатах\n" +
        "    float bandDistance=dot(d,normalize(vec3(0.55,0.40,-0.73)));\n" +
        "    float warp=(noise3(d*8.0+vec3(3.7))-0.5)*0.055;\n" +
        "    float band=exp(-pow((bandDistance+warp)*7.6,2.0));\n" +
        "    float wisps=fbm3(d*17.0+vec3(21.7));\n" +
        "    float rift=smoothstep(0.38,0.67,fbm3(d*31.0+vec3(5.3)));\n" +
        "    float dust=band*(0.21+0.79*wisps)*(1.0-0.78*rift);\n" +
        "    vec3 dustTint=mix(vec3(0.65,0.70,0.80),mix(c1,c2,wisps),0.20);\n" +
        "    sky += dustTint*dust*0.18*clamp(starryParams.z,0.0,1.5)*visibility;\n" +
        "\n" +
        "    vec3 stars=starChart(d.xyz,7.0,pixelAngle)\n" +
        "              +starChart(d.yzx,103.0,pixelAngle)\n" +
        "              +starChart(d.zxy,213.0,pixelAngle);\n" +
        "    sky += stars*clamp(starryParams.y,0.2,2.5)*visibility;\n" +
        "\n" +
        "    // Луна: угловой радиус 1.65 град, кратеры, ореол до ~3 радиусов\n" +
        "    vec3 moonDir=normalize(vec3(0.4,0.55,0.7));\n" +
        "    vec3 moonRight=normalize(cross(vec3(0,1,0),moonDir));\n" +
        "    vec3 moonUp=cross(moonDir,moonRight);\n" +
        "    float facing=dot(d,moonDir);\n" +
        "    vec2 moonQ=vec2(dot(d,moonRight),dot(d,moonUp))\n" +
        "                    /max(facing,0.01)/tan(radians(1.65));\n" +
        "    float moonR=length(moonQ);\n" +
        // fwidth(moonR) и был равен pixelAngle/tan(1.65°) — оставляем член.
        "    float moonAA=pixelAngle/tan(radians(1.65))*0.65;\n" +
        "    if (starryParams.w > 0.5 && facing > 0.0) {\n" +
        "        float halo=exp(-max(moonR-1.0,0.0)*3.8)\n" +
        "                  *(1.0-smoothstep(2.2,3.2,moonR));\n" +
        "        sky += vec3(0.018,0.024,0.035)*halo*visibility;\n" +
        "        if (moonR < 1.0+moonAA) {\n" +
        "            float mask=1.0-smoothstep(1.0-moonAA,1.0+moonAA,moonR);\n" +
        "            vec2 surfaceQ=moonQ/max(1.0,moonR);\n" +
        "            sky=mix(sky,lunarSurface(surfaceQ)*visibility,mask);\n" +
        "        }\n" +
        "    }\n" +
        "    float exposure=clamp(pow(clamp(params.w,0.001,0.05)/0.01,0.28),0.52,1.57);\n" +
        "    sky *= exposure*clamp(params.x,0.3,1.0);\n" +
        "    vec3 skyOut=vec3(1.0)-exp(-max(sky,vec3(0.0)));\n" +
        "    gl_FragColor=vec4(skyOut,1.0);\n" +
        "}";

    /** Плоский uniform-цвет на NDC-треугольник (SKY_VERT) — тинт блоков
     *  Ambience: красит multiply-блендом (ZERO, SRC_COLOR), маска — сама
     *  глубина (GREATER на проходе террейна), сэмплеров нет. */
    public static final String TINT_FSH = "" +
        "#version 120\n" +
        "uniform vec4 color;\n" +
        "void main() {\n" +
        "    gl_FragColor = color;\n" +
        "}";

    /**
     * Фулскрин-грейд насыщенности (модуль Saturation): кадр уже лежит в tex
     * (glCopyTexSubImage2D из бэкбуфера), mix(luma, rgb, saturation):
     * 0 = ч/б, 1 = как было, >1 = vivid. UV из gl_FragCoord (texcoord через
     * lwjglx не доходит, 13.4.2); texOffset — оффсет вьюпорта в окне.
     */
    public static final String SATURATE_FSH = "" +
        "#version 120\n" +
        "uniform sampler2D tex;\n" +
        "uniform vec2 texSize;\n" +
        "uniform vec2 texOffset;\n" +
        "uniform float saturation;\n" +
        "void main() {\n" +
        "    vec2 uv = (gl_FragCoord.xy - texOffset) / texSize;\n" +
        "    vec4 c = texture2D(tex, uv);\n" +
        "    float luma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));\n" +
        "    vec3 g = mix(vec3(luma), c.rgb, saturation);\n" +
        "    gl_FragColor = vec4(g, c.a);\n" +
        "}";

    /**
     * Motion blur (модуль MotionBlur, по мотивам EvaWare): текущий кадр в cur,
     * предыдущий результат в prv; сначала направленные тапы вдоль вектора
     * движения камеры motion (пиксели, симметрично — знак не важен), затем
     * временной фидбэк mix(cur_blurred, prv, keep). UV из gl_FragCoord.
     */
    public static final String MOTIONBLUR_FSH = "" +
        "#version 120\n" +
        "uniform sampler2D cur;\n" +
        "uniform sampler2D prv;\n" +
        "uniform vec2 texSize;\n" +
        "uniform vec2 texOffset;\n" +
        "uniform vec2 motion;\n" +
        "uniform float keep;\n" +
        "void main() {\n" +
        "    vec2 uv = (gl_FragCoord.xy - texOffset) / texSize;\n" +
        "    vec2 step = motion / texSize;\n" +
        "    vec3 acc = vec3(0.0);\n" +
        "    acc += texture2D(cur, uv + step * -0.5);\n" +
        "    acc += texture2D(cur, uv + step * -0.375);\n" +
        "    acc += texture2D(cur, uv + step * -0.25);\n" +
        "    acc += texture2D(cur, uv + step * -0.125);\n" +
        "    acc += texture2D(cur, uv);\n" +
        "    acc += texture2D(cur, uv + step * 0.125);\n" +
        "    acc += texture2D(cur, uv + step * 0.25);\n" +
        "    acc += texture2D(cur, uv + step * 0.375);\n" +
        "    acc += texture2D(cur, uv + step * 0.5);\n" +
        "    acc /= 9.0;\n" +
        "    vec3 p = texture2D(prv, uv).rgb;\n" +
        "    gl_FragColor = vec4(mix(acc, p, keep), 1.0);\n" +
        "}";
}
