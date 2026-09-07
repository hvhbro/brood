package utils.render;

import modules.api.Module;
import modules.api.Modules;
import org.lwjglx.opengl.GL11;
import utils.etc.GameContext;
import utils.etc.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * ArrayList в стиле vanquish (ArrayListWidget 1:1):
 *  - отдельная тёмная плашка на каждый модуль: rgba(12,12,18,240), radius 0
 *  - сортировка включённых по ширине имени (убывание, самое широкое сверху)
 *  - акцентная полоска 1.5px слева с вертикальным градиентом primary→secondary
 *  - градиентный текст primary→secondary, medium-шрифт
 *  - позиция: левый край x=2, y=30 (их конструктор super(2f, 30f), isRightSide=false)
 * Каждая плашка рисуется квадом +1px запаса: SDF-фейд съедает ~1px края,
 * без запаса между соседними плашками видны тёмные швы.
 */
public final class CheatHud {

    private static final float FONT_SIZE = 8f;    // их fontSize = scaled(6), у нас em чуть мельче
    private static final float PADDING_H = 2.5f;  // их paddingH
    private static final float PADDING_V = 1.0f;  // их paddingV; 1.0 → высота плашки ЦЕЛАЯ
    // (10.0 scaled) — стыки плашек попадают на границу пикселей, без полупрозрачных швов
    private static final float STRIPE_W = 1.5f;   // их stripeWidth
    private static final float LEFT_X = 6f;       // отступ от левого края
    private static final float TOP_Y = 30f;       // их y=30

    // UIColors primary/secondary (тема клиента, меняется двумя константами)
    private static final int PRIMARY = 0xFF906BFF;
    private static final int SECONDARY = 0xFF5A4BFF;

    // их bg = new Color(12, 12, 18, 240)
    private static final int BG_COLOR = 0xF00C0C12;

    private static boolean logged;

    private CheatHud() {}

    /** Рисует ArrayList (vanquish-стиль) + ESP-боксы. Вызывается из CheatIngame каждый кадр. */
    public static void renderFrame(int scaledWidth, int scaledHeight, float partialTicks) {
        try {
            // ватермарка (верх-центр) — рисуется всегда
            Watermark.render(scaledWidth);

            // ESP-боксы (под HUD-текстом; сам решает, включён ли модуль)
            modules.impl.Esp.render(GameContext.get(), partialTicks, scaledWidth, scaledHeight);

            MsdfFont f = CustomFont.WM_FONT; // их getMediumFont()

            // включённые модули
            Module[] all = Modules.all().toArray(new Module[0]);
            List<Module> enabled = new ArrayList<Module>();
            for (Module m : all) {
                if (m.isState()) enabled.add(m);
            }
            if (enabled.isEmpty()) return;

            // сортировка по ширине имени (убывание) — вставками, без лямбд
            for (int i = 1; i < enabled.size(); i++) {
                Module key = enabled.get(i);
                float kw = CustomFont.getWidth(key.name, FONT_SIZE, f);
                int j = i - 1;
                while (j >= 0 && CustomFont.getWidth(enabled.get(j).name, FONT_SIZE, f) < kw) {
                    enabled.set(j + 1, enabled.get(j));
                    j--;
                }
                enabled.set(j + 1, key);
            }

            float currentY = TOP_Y;

            for (int idx = 0; idx < enabled.size(); idx++) {
                Module module = enabled.get(idx);
                String moduleName = module.name;
                float textW = CustomFont.getWidth(moduleName, FONT_SIZE, f);

                float rectW = textW + PADDING_H * 2f + STRIPE_W;
                float rectH = FONT_SIZE + PADDING_V * 2f;
                // перекрытие стыка: граничный пиксель шва иначе снаружи ОБОИХ квадов
                // (alpha=0 у каждого) и сквозь него светит мир → «отступ».
                // +0.5 scaled = +1 физ. пиксель: шовный пиксель всегда внутри квада.
                float rectHDraw = rectH + 0.5f;
                float moduleX = LEFT_X; // левая сторона: плашки прижаты к левому краю

                // фон модуля (жёсткий край 0.25, низ перекрывает стык с соседним)
                RenderUtil.drawRoundedRectShader(GameContext.get(), moduleX, currentY,
                    rectW, rectHDraw, 0f, BG_COLOR, BG_COLOR, BG_COLOR, BG_COLOR, 0.25f);

                // градиентная полоска на левом крае (primary→secondary вниз)
                RenderUtil.drawRoundedRectShader(GameContext.get(), moduleX, currentY,
                    STRIPE_W, rectHDraw, 0f, PRIMARY, PRIMARY, SECONDARY, SECONDARY, 0.25f);

                // градиентный текст primary→secondary по ширине строки
                CustomFont.drawGradientString(moduleName, moduleX + STRIPE_W + PADDING_H,
                    currentY + PADDING_V, PRIMARY, SECONDARY, FONT_SIZE, f);

                currentY += rectH;
            }

            // сброс цвета после кадра (полоски/квады оставляют свой цвет в GL)
            GL11.glColor4f(1f, 1f, 1f, 1f);

            if (!logged) {
                logged = true;
                Log.info("HUD", "vanquish arraylist drawn: modules=" + enabled.size());
            }
        } catch (Throwable t) {
            Log.error("HUD", "render exception", t);
        }
    }
}
