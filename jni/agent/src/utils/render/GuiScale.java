package utils.render;

import utils.etc.GameContext;

/**
 * Текущий GUI scale (например 2 при FullHD).
 * Вычисляется один раз с главного потока: framebufferWidth / scaledWidth.
 */
public final class GuiScale {
    private static float cached = -1f;

    private GuiScale() {}

    public static float get(GameContext ctx) {
        if (cached > 0f) return cached;
        try {
            // ctx.scaledWidthMutable установлен CheatIngame (lIllIlIliI), а framebufferWidth — из Window
            // fallback: используем известное соотношение через Window.getFramebufferWidth, если доступно.
            // Пока: берём из GameContext.framebufferScale (заполняется CheatIngame).
            if (ctx.guiScale > 0f) {
                cached = ctx.guiScale;
                return cached;
            }
        } catch (Throwable ignore) {}
        return 2f;
    }

    public static void set(float s) {
        if (s > 0f) cached = s;
    }
}
