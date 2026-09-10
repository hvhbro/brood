package utils.render;

import modules.api.Module;
import modules.api.Modules;
import utils.etc.GameContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keybinds HUD-виджет — порт sweetie KeybindsWidget 1:1.
 *
 * ОРИГИНАЛ (sweetie.evaware.client.ui.widget.overlay.KeybindsWidget):
 *  - позиция (3, 120), драг;
 *  - ХЕДЕР (если есть бинды), две BLUR-плашки:
 *      1) иконка: (x-5, y-5, h1+10, w1+10), радиус-углы (5,1,1,1);
 *         глиф "L" (ICON_NE1Z 14.6f) градиентом primary→secondary;
 *         h1 = iconFont.getHeight(13), w1 = iconFont.getWidth("L", 13);
 *      2) текст: (x + w1 + 10 + отступ - 4, y - 5, w2 + 8, h1 + 10),
 *         углы (1,5,1,1); отступ = 3;
 *         "KeyBinds" (SF_SEMIBOLD 10.5, белый), drawCenteredText на
 *         x + 4 + w1 + 10 + отступ + 20;
 *      headerWidth = (w1 + 10) + отступ + (w2 + 8);
 *  - СПИСОК: offsetY = y + 21; maxWidth = headerWidth - 9.3;
 *    rowHeight = height(10) + 2; targetHeight = rows + 6; lerp 0.15/0.12;
 *    BLUR (x - 5, offsetY, maxWidth + 10, listHeight), углы (1,1,5,5),
 *    альфа = 255 * alphaAnimation; listY = offsetY + 3;
 *    animationOffset = 6 * (1 - alphaAnimation);
 *  - СТРОКИ: включённый модуль с биндом; bind слева (x + 2), имя справа
 *    (x + maxWidth - width(name)); обрезка до maxWidth - 25 + "...";
 *    per-module lerp 0.12, alpha = 255 * anim;
 *  - габариты: width = max(headerWidth, maxWidth + 10), height = 21 + listHeight.
 *
 * НАША АДАПТАЦИЯ (только то, чего нет в клиенте):
 *  - глиф "L" (NE1Z icon font) → IconRender.drawIcon("setting") — атлас-иконка,
 *    тон primary (градиент на 13px глифе неразличим);
 *  - SF_SEMIBOLD → наш HUD_FONT (sf_semibold);
 *  - BLUR_RECT → drawRoundedRectShader (единый радиус; per-corner радиусы
 *    их Vector4f наш SDF-шейдер не поддерживает — взят больший из углов);
 *  - KeyStorage.getBind → bindKey → keyName().
 */
public final class KeybindsWidget {

    private static final float FONT_SIZE = 10f;
    private static final float HEADER_FONT_SIZE = 10.5f;
    private static final float ICON_FONT_SIZE = 13f;
    private static final float ICON_DRAW_SIZE = 14.6f;
    private static final float HEADER_H = 21f;
    private static final float OTSTUP = 3f;

    // UIColors темы
    private static final int PRIMARY = 0xFF906BFF;
    private static final int SECONDARY = 0xFF5A4BFF;
    private static final int WIDGET_BLUR = 0xDC0C0C12;
    private static final int TEXT_WHITE = 0xFFFFFFFF;

    // позиция (их super(3f, 120f)); x<0 = авто-прилипание справа (ArrayList слева)
    private static float posX = -1f;
    private static float posY = 120f;

    // анимации (их поля 1:1)
    private static final Map<String, Float> animations = new HashMap<String, Float>();
    private static float heightAnimation;
    private static float alphaAnimation;

    // последний рендер — для драга
    private static float lastW = 60f;
    private static float lastH = 21f;
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

    /** Активные бинды: [имя модуля, имя клавиши] — их map<String,String>. */
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

            MsdfFont font = CustomFont.HUD_FONT;

            List<String[]> binds = collectBinds();

            // --- ХЕДЕР (1:1, только если есть бинды) ---
            // их метрики икон-шрифта: h1 = height(13), w1 = width("L", 13);
            // у нас иконка квадратная size×size → w1 = h1 = 13
            float h1 = ICON_FONT_SIZE;
            float w1 = ICON_FONT_SIZE;

            float w2 = CustomFont.getWidth("KeyBinds", HEADER_FONT_SIZE, font);

            float headerWidth = 0f;
            if (!binds.isEmpty()) {
                // 1) иконка-плашка: (x-5, y-5, h1+10, w1+10)
                RenderUtil.drawRoundedRectShader(ctx,
                    x - 5f, y - 5f, h1 + 10f, w1 + 10f, 5f,
                    WIDGET_BLUR, WIDGET_BLUR, WIDGET_BLUR, WIDGET_BLUR);
                // глиф по центру плашки (их drawCenteredGradientText, 14.6f):
                // NE1Z-шрифта у нас нет — атлас-иконка с tint primary
                float icX = (x - 5f) + (h1 + 10f - ICON_DRAW_SIZE) / 2f;
                float icY = (y - 5f) + (w1 + 10f - ICON_DRAW_SIZE) / 2f;
                IconRender.drawIcon("setting", icX, icY, ICON_DRAW_SIZE, PRIMARY);

                // 2) текст-плашка: (x + w1 + 10 + отступ - 4, y - 5, w2 + 8, h1 + 10)
                float tpX = x + w1 + 10f + OTSTUP - 4f;
                RenderUtil.drawRoundedRectShader(ctx,
                    tpX, y - 5f, w2 + 8f, h1 + 10f, 5f,
                    WIDGET_BLUR, WIDGET_BLUR, WIDGET_BLUR, WIDGET_BLUR);
                // их drawCenteredText: центр текста на x + 4 + w1 + 10 + отступ + 20
                float textCenter = x + 4f + w1 + 10f + OTSTUP + 20f;
                CustomFont.drawString("KeyBinds",
                    textCenter - w2 / 2f, y + 1f, TEXT_WHITE, false, HEADER_FONT_SIZE, font);

                headerWidth = (w1 + 10f) + OTSTUP + (w2 + 8f);
            }

            // --- СПИСОК (1:1) ---
            float offsetY = y + HEADER_H;
            alphaAnimation = interpolate(alphaAnimation, binds.isEmpty() ? 0f : 1f, 0.12f);

            float rowHeight = CustomFont.cellHeight(FONT_SIZE, font) + 2f;
            float maxWidth = headerWidth - 9.3f;

            float targetHeight = rowHeight * binds.size() + 6f;
            heightAnimation = interpolate(heightAnimation, targetHeight, 0.15f);
            float listHeight = heightAnimation;

            if (alphaAnimation > 0.01f) {
                int blurAlpha = (int) (255f * alphaAnimation);
                int blurCol = (blurAlpha << 24) | 0x0C0C12;
                RenderUtil.drawRoundedRectShader(ctx,
                    x - 5f, offsetY, maxWidth + 10f, listHeight, 5f,
                    blurCol, blurCol, blurCol, blurCol);
            }

            float listY = offsetY + 3f;
            float animationOffset = 6f * (1f - alphaAnimation);

            for (String[] e : binds) {
                String module = e[0];
                String bind = e[1];

                Float af = animations.get(module);
                float anim = af != null ? af.floatValue() : 0f;
                anim = interpolate(anim, 1f, 0.12f);
                animations.put(module, Float.valueOf(anim));

                int alpha = (int) (255f * anim);
                int textCol = (alpha << 24) | 0xFFFFFF;

                // обрезка имени: резерв под бинд = его РЕАЛЬНАЯ ширина (их фикс
                // -25px был под короткие бинды; MOUSE4/BACKSPACE шире → имя
                // наезжало на бинд — тест юзера 09-10)
                float bindW = CustomFont.getWidth(bind, FONT_SIZE, font);
                float maxNameW = maxWidth - 2f - bindW - 6f; // 2 = паддинг бинда, 6 = зазор
                if (maxNameW < 8f) maxNameW = 8f;
                String displayName = module;
                if (CustomFont.getWidth(displayName, FONT_SIZE, font) > maxNameW) {
                    while (displayName.length() > 1
                        && CustomFont.getWidth(displayName + "...", FONT_SIZE, font) > maxNameW) {
                        displayName = displayName.substring(0, displayName.length() - 1);
                    }
                    displayName += "...";
                }

                float rowY = listY + animationOffset * (1f - anim);
                // bind слева, имя справа (их раскладка 1:1)
                CustomFont.drawString(bind, x + 2f, rowY, textCol, false, FONT_SIZE, font);
                float nameX = x + maxWidth - CustomFont.getWidth(displayName, FONT_SIZE, font);
                CustomFont.drawString(displayName, nameX, rowY, textCol, false, FONT_SIZE, font);

                listY += rowHeight;
            }

            // чистка анимаций выключенных модулей (их map растёт бесконечно — фикс)
            if (animations.size() > binds.size() + 16) {
                animations.clear();
            }

            // габариты для драга (их setWidth/setHeight)
            lastW = Math.max(headerWidth, maxWidth + 10f);
            if (lastW <= 0f) lastW = 60f;
            lastH = HEADER_H + listHeight;
            lastX = x;
            lastY = y;
            return lastW;
        } catch (Throwable t) {
            utils.etc.Log.error("Keybinds", "render failed", t);
            return 0f;
        }
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

    private static float interpolate(float current, float target, float factor) {
        return current + (target - current) * factor;
    }
}
