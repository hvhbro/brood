package utils.render;

import org.lwjgl.opengl.GL20;
import org.lwjglx.opengl.GL11;
import utils.etc.Log;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Данные MSDF-атласа (формат msdf-atlas-gen) + загрузка PNG в GL-текстуру.
 * Экземпляр = один шрифт (get("sf_semibold") для HUD, get("medium") для
 * ватермарки rockstar).
 *
 * Поддерживаются оба yOrigin: bottom (expensive sf_*) и top (rockstar medium):
 *  - UV: bottom → v=1-row/H; top → v=row/H
 *  - planeBounds: bottom → top положительный; top → top отрицательный
 *    (нормализуем к нашей системе: topEm > 0 — высота ink над baseline)
 *  - метрики: top → инвертируем знаки ascender/descender
 *
 * medium — properAdvance: шаг пера по настоящему advance (+0.025em трекинг,
 * как I_method_e71fc7e2 rockstar) и учёт left bearing (у medium он
 * отрицательный); sf_* — старый expensive-трюк width*(size-1).
 */
public final class MsdfFont {

    /** Кэш экземпляров (sf_semibold — HUD, medium — ватермарка). */
    public static MsdfFont get(String fontBase) {
        MsdfFont f = CACHE.get(fontBase);
        if (f == null) {
            f = new MsdfFont(fontBase);
            CACHE.put(fontBase, f);
        }
        return f;
    }

    private static final Map<String, MsdfFont> CACHE = new HashMap<String, MsdfFont>();

    public final String fontBase;
    public final boolean properAdvance;

    public int ATLAS_WIDTH;
    public int ATLAS_HEIGHT;
    public float DISTANCE_RANGE = 4f; // px атласа
    public float LINE_HEIGHT = 1.2f;  // em
    public float ASCENDER = 1f;       // em (нормализовано: > 0, вверх от baseline)
    public float DESCENDER = 0.2f;    // em (нормализовано: > 0, вниз от baseline)

    // глиф: [minU, minV, maxU, maxV, wEm, hEm, topEm, advanceEm, leftEm]
    // (minV = верх глифа, topEm = ink над baseline, leftEm = смещение ink от пера)
    private final Map<Integer, float[]> GLYPH = new HashMap<Integer, float[]>();

    private int texId;
    private boolean loaded;
    private boolean failed;
    private boolean logged;

    private static boolean sourceLogged;

    /**
     * Атласы БЕРУТСЯ ТОЛЬКО ИЗ PAYLOAD (FontData — сгенерирован из fonts/msdf
     * на этапе сборки и вшит в DLL вместе с классами). Никакого чтения с диска:
     * DLL самодостаточна. Чтобы обновить шрифт — положи файлы обратно в
     * fonts/msdf и пересобери (pack_agent регенерирует FontData.java).
     */
    private static byte[] loadAtlas(String fileName) {
        int dot = fileName.lastIndexOf('.');
        byte[] embedded = FontData.get(fileName.substring(0, dot), fileName.substring(dot + 1));
        if (embedded != null) logSource();
        return embedded;
    }

    private static void logSource() {
        if (sourceLogged) return;
        sourceLogged = true;
        Log.info("MsdfFont", "atlas source: embedded payload (disk не используется)");
    }

    private MsdfFont(String fontBase) {
        this.fontBase = fontBase;
        this.properAdvance = fontBase.startsWith("medium");
        try {
            byte[] raw = loadAtlas(fontBase + ".json");
            if (raw == null) throw new java.io.IOException("atlas not found (disk + embedded)");
            String json = new String(raw, "UTF-8");
            JsonParser p = new JsonParser(json);
            p.skipTo('{');
            while (true) {
                String key = p.nextString();
                p.skipTo(':');
                if ("atlas".equals(key)) {
                    p.skipTo('{');
                    while (true) {
                        String k2 = p.nextString();
                        p.skipTo(':');
                        if ("width".equals(k2)) ATLAS_WIDTH = (int) p.nextNumber();
                        else if ("height".equals(k2)) ATLAS_HEIGHT = (int) p.nextNumber();
                        else if ("distanceRange".equals(k2)) DISTANCE_RANGE = p.nextNumber();
                        else if ("yOrigin".equals(k2)) yOriginTop = "top".equals(p.nextString());
                        else p.skipValue();
                        p.skipWs();
                        if (p.peek() == ',') p.advance();
                        p.skipWs();
                        if (p.peek() == '}') { p.advance(); break; }
                    }
                } else if ("metrics".equals(key)) {
                    p.skipTo('{');
                    while (true) {
                        String k2 = p.nextString();
                        p.skipTo(':');
                        if ("lineHeight".equals(k2)) LINE_HEIGHT = p.nextNumber();
                        else if ("ascender".equals(k2)) ASCENDER = p.nextNumber();
                        else if ("descender".equals(k2)) DESCENDER = p.nextNumber();
                        else p.skipValue();
                        p.skipWs();
                        if (p.peek() == ',') p.advance();
                        p.skipWs();
                        if (p.peek() == '}') { p.advance(); break; }
                    }
                    if (yOriginTop) {
                        ASCENDER = -ASCENDER;
                        DESCENDER = -DESCENDER;
                    }
                } else if ("glyphs".equals(key)) {
                    p.skipTo('[');
                    while (p.peek() != ']') {
                        parseGlyph(p);
                        p.skipWs();
                        if (p.peek() == ',') p.advance();
                        p.skipWs();
                    }
                    p.advance();
                } else {
                    p.skipValue();
                }
                p.skipWs();
                if (p.peek() == ',') p.advance();
                p.skipWs();
                if (p.peek() == '}') break;
            }
        } catch (Throwable t) {
            failed = true;
            Log.error("MsdfFont", "atlas json load failed: " + fontBase, t);
        }
    }

    private boolean yOriginTop = false;

    private void parseGlyph(JsonParser p) throws IOException {
        p.skipTo('{');
        int unicode = 0;
        float advance = 0f;
        float[] bounds = null; // planeBounds: left,bottom,right,top
        float[] atlas = null;  // atlasBounds: left,bottom,right,top
        while (true) {
            String k = p.nextString();
            p.skipTo(':');
            if ("unicode".equals(k)) unicode = (int) p.nextNumber();
            else if ("advance".equals(k)) advance = p.nextNumber();
            else if ("planeBounds".equals(k)) bounds = parseBounds(p);
            else if ("atlasBounds".equals(k)) atlas = parseBounds(p);
            else p.skipValue();
            p.skipWs();
            if (p.peek() == ',') p.advance();
            p.skipWs();
            if (p.peek() == '}') { p.advance(); break; }
        }

        float minU = 0f, minV = 0f, maxU = 0f, maxV = 0f;
        float wEm = 0f, hEm = 0f, topEm = 0f, leftEm = 0f;
        if (atlas != null && ATLAS_WIDTH > 0) {
            minU = atlas[0] / ATLAS_WIDTH;
            maxU = atlas[2] / ATLAS_WIDTH;
            if (yOriginTop) {
                // atlasBounds.top/bottom — строки PNG от верха; v = row/H
                minV = atlas[3] / ATLAS_HEIGHT; // верх глифа
                maxV = atlas[1] / ATLAS_HEIGHT; // низ
            } else {
                // MsdfGlyph (expensive): minV = 1 - top/H, maxV = 1 - bottom/H
                minV = 1f - atlas[3] / ATLAS_HEIGHT;
                maxV = 1f - atlas[1] / ATLAS_HEIGHT;
            }
        }
        if (bounds != null) {
            leftEm = bounds[0];
            if (yOriginTop) {
                // planeBounds y-down: top отрицательный (над baseline)
                wEm = bounds[2] - bounds[0];
                hEm = bounds[1] - bounds[3];
                topEm = -bounds[3];
            } else {
                wEm = bounds[2] - bounds[0];
                hEm = bounds[3] - bounds[1];
                topEm = bounds[3];
            }
        }
        GLYPH.put(Integer.valueOf(unicode),
            new float[]{minU, minV, maxU, maxV, wEm, hEm, topEm, advance, leftEm});
    }

    /** Bounds: left, bottom, right, top. */
    private static float[] parseBounds(JsonParser p) throws IOException {
        float[] out = new float[4];
        p.skipTo('{');
        while (true) {
            String k = p.nextString();
            p.skipTo(':');
            float v = p.nextNumber();
            if ("left".equals(k)) out[0] = v;
            else if ("bottom".equals(k)) out[1] = v;
            else if ("right".equals(k)) out[2] = v;
            else if ("top".equals(k)) out[3] = v;
            p.skipWs();
            if (p.peek() == ',') p.advance();
            p.skipWs();
            if (p.peek() == '}') { p.advance(); break; }
        }
        return out;
    }

    public int glyphCount() {
        return GLYPH.size();
    }

    public float[] glyph(int code) {
        return GLYPH.get(Integer.valueOf(code));
    }

    /** Загрузка PNG в GL-текстуру (лениво, только главный/GL-поток). */
    public boolean ensureLoaded() {
        if (loaded || failed) return loaded;
        try {
            byte[] png = loadAtlas(fontBase + ".png");
            if (png == null) throw new java.io.IOException("atlas png not found (disk + embedded)");
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) throw new IOException("ImageIO returned null");
            int w = img.getWidth(), h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < argb.length; i++) {
                int px = argb[i];
                buf.put((byte) ((px >> 16) & 0xFF)); // R — MSDF-канал, НЕ трогаем
                buf.put((byte) ((px >> 8) & 0xFF));  // G
                buf.put((byte) (px & 0xFF));         // B
                buf.put((byte) 255);                 // A не участвует (alpha из шейдера)
            }
            buf.flip();

            int prevTex = GL11.glGetInteger(0x8069);     // GL_TEXTURE_BINDING_2D
            int prevProgram = GL11.glGetInteger(0x8B8D); // GL_CURRENT_PROGRAM
            if (prevProgram != 0) GL20.glUseProgram(0);
            texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            // expensive: «to correct msdf font anti-aliasing set MIN/MAG to GL_LINEAR»
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            if (prevProgram != 0) GL20.glUseProgram(prevProgram);

            loaded = true;
            if (!logged) {
                logged = true;
                Log.info("MsdfFont", "atlas loaded [" + fontBase + "]: " + w + "x" + h
                    + " range=" + DISTANCE_RANGE + " glyphs=" + GLYPH.size() + " tex=" + texId
                    + " yOriginTop=" + yOriginTop);
            }
        } catch (Throwable t) {
            failed = true;
            Log.error("MsdfFont", "atlas png load failed: " + fontBase, t);
        }
        return loaded;
    }

    public boolean available() {
        return loaded && !failed;
    }

    public void bind() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
    }

    public void unbind(int prevTex) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
    }

    /** Мини-парсер JSON — только то, что нужно для атласа msdf-atlas-gen. */
    static final class JsonParser {
        private final String s;
        private int i;

        JsonParser(String s) { this.s = s; }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
                i++;
            }
        }

        char peek() {
            skipWs();
            return i < s.length() ? s.charAt(i) : '\0';
        }

        void advance() { i++; }

        void skipTo(char c) {
            while (i < s.length() && s.charAt(i) != c) i++;
            if (i < s.length()) i++;
        }

        String nextString() {
            skipWs();
            if (i >= s.length() || s.charAt(i) != '"') return "";
            i++;
            int start = i;
            while (i < s.length() && s.charAt(i) != '"') {
                if (s.charAt(i) == '\\') i++;
                i++;
            }
            String out = s.substring(start, i);
            i++;
            return out;
        }

        float nextNumber() {
            skipWs();
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
                else break;
            }
            return Float.parseFloat(s.substring(start, i));
        }

        /** Пропускает значение любого типа (строка/число/объект/массив/литерал). */
        void skipValue() {
            skipWs();
            if (i >= s.length()) return;
            char c = s.charAt(i);
            if (c == '"') { nextString(); return; }
            if (c == '{' || c == '[') {
                char open = c;
                char close = c == '{' ? '}' : ']';
                int depth = 0;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (d == '"') { nextString(); continue; }
                    if (d == open) depth++;
                    else if (d == close) {
                        depth--;
                        if (depth == 0) { i++; return; }
                    }
                    i++;
                }
                return;
            }
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d == ',' || d == '}' || d == ']') break;
                i++;
            }
        }
    }
}
