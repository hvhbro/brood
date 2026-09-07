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
}
