package rustme;

import utils.render.CheatHud;
import utils.render.GuiScale;
import utils.etc.GameContext;
import utils.etc.Log;

/**
 * Подмена GuiIngame: игра каждый кадр зовёт iliIiiIliI(partialTicks)
 * (renderGameOverlay) на главном потоке. Сначала рисуем ванильный HUD
 * (super), затем наш ArrayList.
 *
 * Диагностика v27: лог первого вызова + heartbeat каждые 5с —
 * если лог молчит, наш объект никто не зовёт (подменили не то поле).
 */
public class CheatIngame extends liIIliliiI {

    private static boolean firstCallLogged;
    private static long lastBeat;
    private static long callCount;

    public CheatIngame(iilliIliiI gsIn) {
        super(gsIn);
        Log.info("HUD", "CheatIngame constructed");
    }

    @Override
    public void iliIiiIliI(float partialTicks) {
        callCount++;
        if (!firstCallLogged) {
            firstCallLogged = true;
            Log.info("HUD", "FIRST CALL: our iliIiiIliI invoked, partialTicks=" + partialTicks);
        }

        // ванильный + модовый HUD
        try {
            super.iliIiiIliI(partialTicks);
        } catch (Throwable t) {
            Log.error("HUD", "super overlay failed", t);
        }

        // FontRenderer: наследуемый геттер GuiIngame (llliiiIliI) — как сам мод его берёт
        try {
            Object font = llliiiIliI();
            GameContext.get().setFontRenderer(font);
        } catch (Throwable ignore) {}

        // наш HUD после всего, тем же конвейером, тем же кадром
        try {
            GameContext ctx = GameContext.get();
            liIIiIliiI res = new liIIiIliiI((iilliIliiI) ctx.getGsForRender());
            // геттеры не знаем заранее, какой width/height: в landscape ширина больше
            // КАРТА ГЕТТЕРОВ (доказана дизасмом ctor): IIllIlIliI=scaledWidth(fbW/gs),
            // lIllIlIliI=scaledHeight(fbH/gs), illlIlIliI=guiScale
            int scaledW = res.IIllIlIliI();
            int scaledH = res.lIllIlIliI();
            if (ctx.guiScale <= 0f) {
                int gs = res.illlIlIliI();
                if (gs > 0) {
                    GuiScale.set((float) gs);
                    ctx.guiScale = (float) gs;
                    ctx.fbHeight = scaledH * gs;
                    Log.info("HUD", "scaledW=" + scaledW + " scaledH=" + scaledH + " guiScale=" + gs
                        + " fbHeight=" + ctx.fbHeight);
                }
            }
            CheatHud.renderFrame(scaledW, scaledH, partialTicks);
        } catch (Throwable t) {
            Log.error("HUD", "our overlay failed", t);
        }
    }
}
