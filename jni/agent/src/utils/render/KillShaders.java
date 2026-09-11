package utils.render;

/**
 * Шейдеры KillEffect (порт kill_effect_tornado / kill_effect_portal, GLSL 330
 * → наш GLSL 120 стек — как JumpDistort: без layout/in/out, texCoord считается
 * по gl_FragCoord + rect (texcoord через lwjglx в шейдер не доходит, 13.4.2),
 * vertexColor стал uniform'ом (у нас нет вершинных цветов в кваде).
 *
 * ЕДИНЫЕ uniform'ы для обоих режимов:
 *   vec4 rect    — квад на экране в ФИЗИЧЕСКИХ пикселях (x,y — верх-лево, w,h);
 *   float fbHeight — высота фреймбуфера (переворот Y для gl_FragCoord);
 *   vec4 vertexColor — замена вершинного цвета (1,1,1,1);
 *   float uTime/uProgress/uIntensity/uSize; vec4 uColor.
 *
 * texCoord: x слева-направо 0..1, y СНИЗУ-ВВЕРХ 0..1 (y=0 — низ эффекта),
 * как в оригинальных шейдерах (бим растёт из земли).
 */
public final class KillShaders {

    private KillShaders() {}

    private static String head() {
        return "#version 120\n"
            + "uniform vec4 rect;\n"
            + "uniform float fbHeight;\n"
            + "uniform vec4 vertexColor;\n"
            + "uniform float uTime;\n"
            + "uniform float uProgress;\n"
            + "uniform vec4 uColor;\n"
            + "uniform float uIntensity;\n"
            + "uniform float uSize;\n"
            + "\n"
            + "float hash21(vec2 p) {\n"
            + "    p = fract(p * vec2(443.8975, 397.2973));\n"
            + "    p += dot(p, p + 19.19);\n"
            + "    return fract(p.x * p.y);\n"
            + "}\n"
            + "\n"
            + "float smoothNoise2D(vec2 p) {\n"
            + "    vec2 i = floor(p);\n"
            + "    vec2 f = fract(p);\n"
            + "    f = f * f * (3.0 - 2.0 * f);\n"
            + "    return mix(\n"
            + "        mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x),\n"
            + "        mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), f.x),\n"
            + "        f.y\n"
            + "    );\n"
            + "}\n";
    }

    /** texCoord из gl_FragCoord (физ. пиксели): y снизу вверх, как в оригинале. */
    private static String texCoordBlock() {
        return "    vec2 frag = vec2(gl_FragCoord.x, fbHeight - gl_FragCoord.y);\n"
            + "    vec2 texCoord = vec2((frag.x - rect.x) / max(rect.z, 1.0),\n"
            + "                         1.0 - (frag.y - rect.y) / max(rect.w, 1.0));\n";
    }

    // ===== РЕЖИМ 1: СМЕРЧ (kill_effect_tornado.fsh, 1:1) =====
    public static final String TORNADO_FSH = "" +
        head() +
        "\n" +
        "float fbm4(vec2 p) {\n" +
        "    float v = 0.0; float a = 0.5; float freq = 1.0;\n" +
        "    for (int k = 0; k < 4; k++) {\n" +
        "        v += a * smoothNoise2D(p * freq);\n" +
        "        freq *= 2.03;\n" +
        "        a *= 0.48;\n" +
        "    }\n" +
        "    return v;\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        texCoordBlock() +
        "    if (texCoord.y < 0.0) discard;\n" +
        "\n" +
        "    float x = texCoord.x * 2.0 - 1.0;\n" +
        "    float y = texCoord.y;\n" +
        "    float t = uTime;\n" +
        "    float prog = clamp(uProgress, 0.0, 1.0);\n" +
        "\n" +
        "    float baseFade = smoothstep(0.0, 0.05, y);\n" +
        "    float topFade = 1.0 - smoothstep(0.55, 0.98, y);\n" +
        "\n" +
        "    float rise = 0.10 + prog * 1.0;\n" +
        "    float growing = 1.0 - smoothstep(rise - 0.12, rise, y);\n" +
        "    float body = growing * topFade * baseFade;\n" +
        "    if (body <= 0.001) discard;\n" +
        "\n" +
        "    float radius = mix(0.10, 0.95, pow(y, 1.4));\n" +
        "    float r = abs(x) / max(radius, 1.0e-4);\n" +
        "\n" +
        "    float wall = 1.0 - smoothstep(0.0, 1.0, abs(r - 0.72) * 2.2);\n" +
        "\n" +
        "    vec2 sw = vec2(x * 3.0, y * 6.0 - t * 2.5);\n" +
        "    float twirl = fbm4(sw + vec2(t * 0.3, 0.0));\n" +
        "    float bands = smoothstep(0.30, 0.80, twirl + 0.2 * sin(x * 8.0 + y * 12.0 - t * 4.0));\n" +
        "\n" +
        "    float core = exp(-(x * x) / (2.0 * 0.11 * 0.11)) * smoothstep(0.0, 0.10, y);\n" +
        "\n" +
        "    float particles = 0.0;\n" +
        "    for (int i = 0; i < 20; i++) {\n" +
        "        float seed = float(i) * 1.37 + 3.1;\n" +
        "        float hgt = fract(t * 0.35 + hash21(vec2(seed, 0.0)) * 0.8);\n" +
        "        if (hgt > rise - 0.1) continue;\n" +
        "        float angular = (hgt * 3.0 + t) * 3.0;\n" +
        "        float px = sin(angular + seed) * 0.55;\n" +
        "        float sz = 0.03 + 0.05 * hash21(vec2(seed, 1.0));\n" +
        "        vec2 dc = vec2((x - px) * 1.2, (y - hgt) * 2.0);\n" +
        "        particles += exp(-dot(dc, dc) / (2.0 * sz * sz)) * 0.7;\n" +
        "    }\n" +
        "\n" +
        "    float glow = exp(-abs(x) * 2.6) * 0.4 * (0.6 + 0.4 * sin(t * 2.0));\n" +
        "\n" +
        "    vec3 base = uColor.rgb;\n" +
        "    vec3 spiralColor = mix(base, vec3(0.45, 0.50, 1.00), 0.55);\n" +
        "    vec3 coreColor = vec3(0.90, 0.85, 1.00);\n" +
        "    vec3 particleColor = vec3(0.60, 0.80, 1.00);\n" +
        "\n" +
        "    vec3 finalColor = spiralColor * bands * wall * 1.10\n" +
        "                    + coreColor * core * 1.60\n" +
        "                    + particleColor * particles * 0.90\n" +
        "                    + vec3(0.40, 0.50, 1.00) * glow;\n" +
        "\n" +
        "    float fin = (wall * bands * 0.80 + core + particles * 0.50 + glow * 0.30) * body;\n" +
        "    float alpha = clamp(fin * 1.20, 0.0, 1.0) * vertexColor.a;\n" +
        "\n" +
        "    float fadeIn = smoothstep(0.0, 0.08, prog);\n" +
        "    float fadeOut = 1.0 - smoothstep(0.60, 1.0, prog);\n" +
        "    alpha *= fadeIn * fadeOut * uIntensity;\n" +
        "\n" +
        "    if (alpha < 0.01) discard;\n" +
        "    gl_FragColor = vec4(clamp(finalColor * uIntensity, 0.0, 1.0), alpha);\n" +
        "}\n";

    // ===== РЕЖИМ 2: ПОРТАЛ (kill_effect_portal.fsh, 1:1) =====
    public static final String PORTAL_FSH = "" +
        head() +
        "\n" +
        "float fbm(vec2 p) {\n" +
        "    float v = 0.0; float a = 0.5; float freq = 1.0;\n" +
        "    for (int k = 0; k < 4; k++) {\n" +
        "        v += a * smoothNoise2D(p * freq);\n" +
        "        freq *= 2.1;\n" +
        "        a *= 0.48;\n" +
        "    }\n" +
        "    return v;\n" +
        "}\n" +
        "\n" +
        "vec3 hsv2rgb(vec3 c) {\n" +
        "    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);\n" +
        "    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);\n" +
        "    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        texCoordBlock() +
        "    vec2 center = vec2(0.5);\n" +
        "    vec2 uv = texCoord;\n" +
        "    vec2 dir = uv - center;\n" +
        "    float dist = length(dir);\n" +
        "    float angle = atan(dir.y, dir.x);\n" +
        "\n" +
        "    float progress = uProgress;\n" +
        "    float time = uTime;\n" +
        "    float radius = 0.05 + 0.55 * progress;\n" +
        "    radius *= (0.9 + 0.1 * sin(time * 2.5));\n" +
        "\n" +
        "    float phi = dist * 3.14159;\n" +
        "    float theta = angle;\n" +
        "    float sx = sin(phi) * cos(theta);\n" +
        "    float sy = sin(phi) * sin(theta);\n" +
        "    float sz = cos(phi);\n" +
        "    float rotAngle = time * 0.6;\n" +
        "    float cx = sx * cos(rotAngle) - sz * sin(rotAngle);\n" +
        "    float cz = sx * sin(rotAngle) + sz * cos(rotAngle);\n" +
        "    sx = cx;\n" +
        "    sz = cz;\n" +
        "\n" +
        "    float sphereMask = 1.0 - smoothstep(0.0, radius, dist);\n" +
        "\n" +
        "    float grid = 0.0;\n" +
        "    float lat = sin(phi * 8.0 + time * 0.2) * 0.5 + 0.5;\n" +
        "    float lon = sin(theta * 6.0 + time * 0.4) * 0.5 + 0.5;\n" +
        "    grid = (lat > 0.97 || lon > 0.97) ? 1.0 : 0.0;\n" +
        "    grid *= sphereMask * (0.6 + 0.4 * sz);\n" +
        "    grid *= 1.5;\n" +
        "\n" +
        "    float rings = 0.0;\n" +
        "    for (int i = 0; i < 4; i++) {\n" +
        "        float fi = float(i);\n" +
        "        float tilt = 0.2 + fi * 0.25 + 0.1 * sin(time * 0.3 + fi);\n" +
        "        float radiusRing = 0.25 + fi * 0.15 + 0.05 * sin(time * 0.4 + fi * 1.1);\n" +
        "        vec2 ringDir = dir;\n" +
        "        float ringDist = length(ringDir);\n" +
        "        float ringAngle = atan(ringDir.y, ringDir.x);\n" +
        "        float ringWidth = 0.01 + 0.01 * (1.0 - progress);\n" +
        "        float ring = 1.0 - smoothstep(radiusRing - ringWidth, radiusRing + ringWidth, ringDist);\n" +
        "        float tiltFactor = 1.0 + 0.4 * sin(ringAngle + tilt);\n" +
        "        ring *= (1.0 - abs(ringDist - radiusRing) * 4.0);\n" +
        "        ring *= (0.7 + 0.3 * sin(time * 1.0 + fi * 1.3));\n" +
        "        ring *= sphereMask * (0.5 + 0.5 * sz);\n" +
        "        rings += ring * 0.8;\n" +
        "    }\n" +
        "    rings *= 1.8;\n" +
        "\n" +
        "    float lightning = 0.0;\n" +
        "    for (int i = 0; i < 8; i++) {\n" +
        "        float seed = float(i) * 1.73 + 0.5;\n" +
        "        float a = hash21(vec2(seed, 0.0)) * 6.2832;\n" +
        "        float len = 0.3 + 0.7 * hash21(vec2(seed, 1.0));\n" +
        "        float life = fract(time * 0.8 + seed * 0.5);\n" +
        "        float bright = 1.0 - smoothstep(0.1, 0.9, life);\n" +
        "        float rad = radius * (0.2 + 0.8 * len);\n" +
        "        vec2 pos = vec2(0.5 + rad * cos(a + time * 0.4), 0.5 + rad * sin(a + time * 0.4));\n" +
        "        vec2 delta = uv - pos;\n" +
        "        float d = length(delta);\n" +
        "        vec2 dirToPoint = normalize(pos - center);\n" +
        "        vec2 perp = vec2(-dirToPoint.y, dirToPoint.x);\n" +
        "        float proj = dot(delta, dirToPoint);\n" +
        "        float perpDist = abs(dot(delta, perp));\n" +
        "        float line = 1.0 - smoothstep(0.0, 0.02, perpDist);\n" +
        "        line *= smoothstep(0.0, 0.1, proj) * smoothstep(1.0, 0.85, proj / (rad + 0.001));\n" +
        "        lightning += line * bright * 1.2;\n" +
        "    }\n" +
        "    lightning *= sphereMask * (0.5 + 0.5 * sz);\n" +
        "    lightning *= 1.5;\n" +
        "\n" +
        "    float coreDist = length(dir) / (0.03 + 0.02 * progress);\n" +
        "    float core = exp(-coreDist * coreDist) * 2.5;\n" +
        "    core *= (1.0 - progress * 0.2);\n" +
        "    core *= (0.6 + 0.4 * sz);\n" +
        "\n" +
        "    float particles = 0.0;\n" +
        "    for (int i = 0; i < 60; i++) {\n" +
        "        float seed = float(i) * 1.37;\n" +
        "        float a = hash21(vec2(seed, 0.0)) * 6.2832;\n" +
        "        float r = radius * (0.1 + 0.9 * hash21(vec2(seed, 1.0)));\n" +
        "        float life = fract(time * 0.5 + seed * 0.3);\n" +
        "        float bright = 1.0 - smoothstep(0.1, 0.9, life);\n" +
        "        float angleOff = time * (0.4 + 0.6 * hash21(vec2(seed, 2.0)));\n" +
        "        float phiP = (r / radius) * 3.14159;\n" +
        "        float thetaP = a + angleOff;\n" +
        "        float sxP = sin(phiP) * cos(thetaP);\n" +
        "        float syP = sin(phiP) * sin(thetaP);\n" +
        "        float szP = cos(phiP);\n" +
        "        vec2 pos = center + vec2(sxP, syP) * radius * 0.5;\n" +
        "        float szFactor = 0.5 + 0.5 * szP;\n" +
        "        float sz2 = 0.005 + 0.025 * hash21(vec2(seed, 3.0));\n" +
        "        float d = length(uv - pos);\n" +
        "        float p = exp(-d * d / (2.0 * sz2 * sz2));\n" +
        "        particles += p * bright * 0.8 * szFactor;\n" +
        "    }\n" +
        "    particles *= (1.0 - progress * 0.5);\n" +
        "    particles *= 1.8;\n" +
        "\n" +
        "    float glow = exp(-dist * 5.0) * 0.6 * (1.0 + 0.3 * sin(time * 2.5));\n" +
        "    glow *= (1.0 - progress * 0.3);\n" +
        "    glow *= (0.6 + 0.4 * sz);\n" +
        "    float bloom = exp(-dist * 3.0) * 0.3;\n" +
        "\n" +
        "    float ring2 = 0.0;\n" +
        "    float r2 = radius * 1.7 * progress;\n" +
        "    ring2 = 1.0 - smoothstep(r2 - 0.03, r2 + 0.03, dist);\n" +
        "    ring2 *= 0.5 * (1.0 - progress) * sin(progress * 3.14159);\n" +
        "    ring2 *= 1.5;\n" +
        "\n" +
        "    vec3 baseColor = uColor.rgb;\n" +
        "    vec3 gridColor = mix(baseColor, vec3(0.9, 0.95, 1.0), 0.4);\n" +
        "    vec3 ringColor = mix(baseColor, vec3(1.0, 0.9, 0.5), 0.5);\n" +
        "    vec3 lightningColor = vec3(0.8, 0.9, 1.0);\n" +
        "    vec3 coreColor = vec3(1.0, 0.98, 0.9);\n" +
        "    vec3 particleColor = vec3(1.0, 0.85, 0.5);\n" +
        "    vec3 glowColor = mix(baseColor, vec3(0.6, 0.8, 1.0), 0.5);\n" +
        "\n" +
        "    float finalIntensity = grid + rings + lightning + core + particles + glow + bloom + ring2;\n" +
        "    vec3 finalColor = gridColor * grid * 1.5\n" +
        "                    + ringColor * rings * 1.2\n" +
        "                    + lightningColor * lightning * 1.0\n" +
        "                    + coreColor * core * 2.5\n" +
        "                    + particleColor * particles * 0.9\n" +
        "                    + glowColor * glow * 0.6\n" +
        "                    + vec3(0.8, 0.9, 1.0) * bloom * 0.5\n" +
        "                    + baseColor * ring2 * 0.6;\n" +
        "    finalColor *= uIntensity * 1.2;\n" +
        "\n" +
        "    float finalAlpha = clamp(finalIntensity * 1.6, 0.0, 1.0) * vertexColor.a;\n" +
        "    float vignette = 1.0 - dist * 0.5;\n" +
        "    finalAlpha *= mix(vignette, 1.0, 0.5);\n" +
        "\n" +
        "    if (finalAlpha < 0.01) discard;\n" +
        "    gl_FragColor = vec4(clamp(finalColor, 0.0, 1.0), finalAlpha);\n" +
        "}\n";
}
