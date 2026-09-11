package utils.render;

import modules.api.Module;
import modules.api.Modules;
import org.lwjglx.opengl.GL11;
import utils.etc.GameContext;
import utils.etc.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Keybinds HUD-виджет — порт rich HotKeys (rich.screens.hud.HotKeys) 1:1,
 * адаптирован под наш стек (MSDF-шрифт, SDF-плашки, scissor).
 *
 * МЕТРИКИ rich (1:1):
 *   HEADER_HEIGHT = 22, ROW_HEIGHT = 16 (шаг ряда 14);
 *   фон: blur (20,20,20,70) r10, углы (9,9,9,9) — у нас единый r9;
 *   шапка: градиент (28,28,28,110 → 24,24,24,120) высотой 19.5, r9 —
 *     у нас сплошной (27,27,27,114) r9 (градиент незаметен на 19.5px);
 *   "Keybinds" BOLD 7px на (x+6, y+6), иконка справа x+w-12, y+6 (180,180,180);
 *   ряды с y+19 (HEADER-3): имя 7px (214,214,214) на x+8,
 *     rowY + (16-7)/2 + 0.5; бинд 7px (130,130,140) в боксе 12px высотой,
 *     boxWidth = max(12, bindWidth+6), boxX = x + w - boxWidth - 5,
 *     boxY = rowY + (16-12)/2, текст центрирован в боксе;
 *   ширина = max(100, nameWidth + bindWidth + 28) — анимируется lerp'ом
 *     (factor = 1 - 0.001^(dt*8)); высота = 22 + rows*14;
 *   весь виджет под scissor (хвост списка обрезается при сжатии высоты).
 *
 * Иконка — наша атласная (IconRender "setting"), вместо глифа "L".
 */
public final class KeybindsWidget {

    private static final float HEADER_HEIGHT = 22f;
    private static final float ROW_STEP = 14f;
    private static final float ROW_HEIGHT = 16f;
    private static final float FONT_SIZE = 7f;
    private static final float ANIMATION_SPEED = 8.0f;

    // rich-цвета
    private static final int BG_BLUR = 0x46141414;      // (20,20,20,70)
    private static final int BG_HEADER = 0x721B1B1B;    // (27,27,27,114)
    private static final int COL_TITLE = 0xFFFFFFFF;
    private static final int COL_ICON = 0xFFB4B4B4;     // (180,180,180)
    private static final int COL_NAME = 0xFFD6D6D6;     // (214,214,214)
    private static final int COL_BIND = 0xFF82828C;     // (130,130,140)

    // позиция (драг из меню); x<0 = авто-прилипание справа
    private static float posX = -1f;
    private static float posY = 120f;

    // анимация (lerp как в rich)
    private static float alphaAnimation;
    private static float animatedWidth = 100f;
    private static float animatedHeight = HEADER_HEIGHT;
    private static long lastUpdateTime;

    // последний рендер — для драга
    private static float lastW = 100f;
    private static float lastH = HEADER_HEIGHT;
    private static float lastX;
    private static float lastY;

    private KeybindsWidget() {}

    // ===== позиция + драг =====

    public static void setPos(float x, float y) {
        posX = Math.max(0f, x);
        posY = Math.max(0f, y);
    }

    /** Hit-рект для драга: out = {x,y,w,h}. */
    public static void getRect(float[] out) {
        out[0] = lastX;
        out[1] = lastY;
        out[2] = lastW;
        out[3] = lastH;
    }

    // ===== сбор биндов =====

    /** Активные бинды: [имя модуля, имя клавиши]. */
    private static List<String[]> collectBinds() {
        List<String[]> map = new ArrayList<String[]>();
        Module[] all = Modules.all().toArray(new Module[0]);
        for (Module m : all) {
            if (m.isState() && m.bindKey >= 0) {
                map.add(new String[] {m.name, keyName(m.bindKey)});
            }
        }
        return map;
    }

    // ===== рендер =====

    public static float render(int scaledWidth) {
        try {
            GameContext ctx = GameContext.get();
            float x = posX >= 0f ? posX : scaledWidth - lastW - 3f;
            float y = posY;

            List<String[]> binds = collectBinds();

            long currentTime = System.currentTimeMillis();
            float deltaTime = Math.min((currentTime - lastUpdateTime) / 1000f, 0.1f);
            lastUpdateTime = currentTime;

            // alpha появления/скрытия (rich: startAnimation/stopAnimation)
            float alphaTarget = binds.isEmpty() ? 0f : 1f;
            alphaAnimation = lerp(alphaAnimation, alphaTarget, deltaTime);

            // целевые размеры (rich: max(100, name+bind+28))
            float targetWidth = 100f;
            for (String[] e : binds) {
                float nameW = CustomFont.getWidth(e[0], FONT_SIZE, CustomFont.HUD_FONT);
                float bindW = CustomFont.getWidth(e[1], FONT_SIZE, CustomFont.HUD_FONT);
                targetWidth = Math.max(targetWidth, nameW + bindW + 28f);
            }
            float targetHeight = HEADER_HEIGHT + binds.size() * ROW_STEP;

            animatedWidth = lerp(animatedWidth, targetWidth, deltaTime);
            animatedHeight = lerp(animatedHeight, targetHeight, deltaTime);

            float w = animatedWidth;
            float h = animatedHeight;
            float alphaFactor = alphaAnimation;
            if (alphaFactor <= 0.01f || w < 40f || h < 10f) {
                lastX = x; lastY = y; lastW = 100f; lastH = HEADER_HEIGHT;
                return 0f;
            }
            lastW = w;
            lastH = h;
            lastX = x;
            lastY = y;

            int a = (int) (255f * alphaFactor);

            // фон: blur-плашка r9 (rich: blur 20,20,20,70, corners 9)
            RenderUtil.drawRoundedRectShader(ctx, x, y, w, h, 9f,
                mixA(BG_BLUR, a), mixA(BG_BLUR, a), mixA(BG_BLUR, a), mixA(BG_BLUR, a), 0.25f);
            // шапка: r9 (rich: градиент 110→120 на 19.5px — незаметен, сплошной)
            RenderUtil.drawRoundedRectShader(ctx, x, y, w, 19.5f, 9f,
                mixA(BG_HEADER, a), mixA(BG_HEADER, a), mixA(BG_HEADER, a), mixA(BG_HEADER, a), 0.25f);

            // scissor по габаритам виджета (rich: Scissor.enable(x,y,w,h))
            int gs = Math.max(1, Math.round(GuiScale.get(ctx)));
            int fbH = ctx.fbHeight > 0 ? ctx.fbHeight : (int) (scaledHeightSafe(ctx) * gs);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(Math.round(x * gs), Math.round(fbH - (y + h) * gs),
                Math.max(1, Math.round(w * gs)), Math.max(1, Math.round(h * gs)));

            // заголовок + иконка справа
            CustomFont.drawString("Keybinds", x + 6f, y + 6f,
                mixA(COL_TITLE, a), false, FONT_SIZE, CustomFont.HUD_FONT);
            IconRender.drawIcon("setting", x + w - 12f, y + 6f, 7f, mixA(COL_ICON, a));

            // ряды: с y + HEADER - 3, шаг 14 (rich 1:1)
            float rowY = y + HEADER_HEIGHT - 3f;
            for (String[] e : binds) {
                drawKeyRow(ctx, x, rowY, w, e[0], e[1], a);
                rowY += ROW_STEP;
            }

            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return w;
        } catch (Throwable t) {
            utils.etc.Log.error("Keybinds", "render failed", t);
            return 0f;
        }
    }

    /** Один ряд: имя слева, бинд в боксе справа.
     *  Центрирование — по ФАКТИЧЕСКОЙ клетке шрифта (cellHeight), а не по
     *  «7» из референса: наш drawString рисует полную клетку глифа от y
     *  (у rockstar/rich «7» — визуальная высота), иначе текст уползает. */
    private static void drawKeyRow(GameContext ctx, float x, float rowY, float w,
                                   String name, String bind, int a) {
        float cell = CustomFont.cellHeight(FONT_SIZE, CustomFont.HUD_FONT);

        // имя: x+8, вертикальный центр ряда
        CustomFont.drawString(name, x + 8f, rowY + (ROW_HEIGHT - cell) / 2f,
            mixA(COL_NAME, a), false, FONT_SIZE, CustomFont.HUD_FONT);

        // бинд: бокс 12px высотой, ширина по тексту (+6), справа с отступом 5
        float bindWidth = CustomFont.getWidth(bind, FONT_SIZE, CustomFont.HUD_FONT);
        float boxWidth = Math.max(12f, bindWidth + 6f);
        float boxHeight = 12f;
        float boxX = x + w - boxWidth - 5f;
        float boxY = rowY + (ROW_HEIGHT - boxHeight) / 2f;
        CustomFont.drawString(bind, boxX + (boxWidth - bindWidth) / 2f,
            boxY + (boxHeight - cell) / 2f, mixA(COL_BIND, a), false,
            FONT_SIZE, CustomFont.HUD_FONT);
    }

    /** alpha-подмешивание: цвет (0xAARRGGBB) × a/255. */
    private static int mixA(int argb, int a) {
        int srcA = (argb >>> 24) & 0xFF;
        int outA = srcA * a / 255;
        return (outA << 24) | (argb & 0xFFFFFF);
    }

    private static float lerp(float current, float target, float deltaTime) {
        float factor = 1f - (float) Math.pow(0.001, deltaTime * ANIMATION_SPEED);
        return current + (target - current) * factor;
    }

    private static int scaledHeightSafe(GameContext ctx) {
        return ctx.scaledHeight > 0 ? ctx.scaledHeight : 320;
    }

    /** GLFW-клавиша → короткое имя (маппинг CheatMenuScreen.keyName). */
    private static String keyName(int key) {
        if (key < 0) return "-";
        if (key == 0) return "MOUSE1";
        if (key == 1) return "MOUSE2";
        if (key == 2) return "MOUSE3";
        if (key == 3) return "MOUSE4";
        if (key == 4) return "MOUSE5";
        if (key == 5) return "MOUSE6";
        if (key == 6) return "MOUSE7";
        if (key == 7) return "MOUSE8";
        if (key == 32) return "SPACE";
        if (key == 256) return "ESC";
        if (key == 258) return "TAB";
        if (key == 259) return "BACKSPACE";
        if (key == 260) return "INSERT";
        if (key == 261) return "DELETE";
        if (key == 262) return "RIGHT";
        if (key == 263) return "LEFT";
        if (key == 264) return "DOWN";
        if (key == 265) return "UP";
        if (key == 266) return "PAGE_UP";
        if (key == 267) return "PAGE_DOWN";
        if (key == 268) return "HOME";
        if (key == 269) return "END";
        if (key == 280) return "CAPS_LOCK";
        if (key == 340) return "L_SHIFT";
        if (key == 341) return "L_CTRL";
        if (key == 342) return "L_ALT";
        if (key == 344) return "R_SHIFT";
        if (key == 345) return "R_CTRL";
        if (key == 346) return "R_ALT";
        if (key >= 290 && key <= 301) return "F" + (key - 289);
        if (key >= 48 && key <= 57) return String.valueOf((char) key);
        if (key >= 65 && key <= 90) return String.valueOf((char) key);
        return "K" + key;
    }
}
