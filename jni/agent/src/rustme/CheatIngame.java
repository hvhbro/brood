package rustme;

import utils.render.CheatHud;
import utils.render.GlassGrab;
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

    // ===== Хотбар форка: подмена ОБЪЕКТА-рендерера =====
    // Хотбар рисует НЕ GuiIngame: iliIiiIliI @401 зовёт
    // this.iillliil.iillIIIliI(res), где iillliil — final-поле
    // типа rustme.IIiiiIliiI (дизасм liIIliliiI). Прежний оверрайд
    // iillIIIliI на CheatIngame этим путём не вызывался никогда —
    // потому форковый хотбар оставался видимым.
    // Фикс: при включённом Hotbar пишем в поле заглушку-прокси
    // (рендер слотов подавлен, остальные методы форвардятся
    // оригиналу — выбор слота и состояние живут); при выключении
    // возвращаем оригинал.
    private static volatile boolean forkHotbarSwapped;
    private static volatile Object forkHotbarOriginal; // IIiiiIliiI
    private static HotbarStub forkHotbarStub;

    /** Оригинальный держатель хотбара форка (для чтения выбора в Hotbar). */
    public static Object forkHotbarDelegate() {
        return forkHotbarOriginal;
    }

    private final iilliIliiI gsIn;

    public CheatIngame(iilliIliiI gsIn) {
        super(gsIn);
        this.gsIn = gsIn;
    }

    /** Раз в кадр до super.iliIiiIliI: подмена/возврат объекта хотбара. */
    private void syncForkHotbar() {
        boolean want = modules.impl.Hotbar.isActive();
        if (want == forkHotbarSwapped) return;
        try {
            java.lang.reflect.Field f = liIIliliiI.class.getField("iillliil");
            f.setAccessible(true);
            if (want) {
                Object orig = f.get(this);
                if (orig == null) return;
                if (forkHotbarOriginal == null) {
                    forkHotbarOriginal = orig;
                    forkHotbarStub = new HotbarStub(gsIn, (IIiiiIliiI) orig);
                }
                f.set(this, forkHotbarStub);
            } else if (forkHotbarOriginal != null) {
                f.set(this, forkHotbarOriginal);
            }
            forkHotbarSwapped = want;
            Log.info("Hotbar", want ? "fork hotbar suppressed (field swap)"
                : "fork hotbar restored");
        } catch (Throwable t) {
            forkHotbarSwapped = false; // повтор на следующем кадре
            Log.error("Hotbar", "fork hotbar swap failed", t);
        }
    }

    /** Прокси хотбара форка: рендер слотов пуст, всё остальное — оригиналу. */
    private static final class HotbarStub extends IIiiiIliiI {
        private final IIiiiIliiI delegate;

        HotbarStub(iilliIliiI gs, IIiiiIliiI delegate) {
            super(gs);
            this.delegate = delegate;
        }

        @Override
        public void iillIIIliI(liIIiIliiI res) {
            // хотбар форка глушим — рисует modules.impl.Hotbar
        }

        @Override
        public boolean IillIIIliI() { return delegate.IillIIIliI(); }

        @Override
        public void lillIIIliI(int v) { delegate.lillIIIliI(v); }

        @Override
        public void iIllIIIliI(rustme.lIiliIliiI v) { delegate.iIllIIIliI(v); }

        @Override
        public float IIllIIIliI() { return delegate.IIllIIIliI(); }

        @Override
        public void lIllIIIliI(int a, int b, float c, float d, rustme.illIiIliiI e) {
            delegate.lIllIIIliI(a, b, c, d, e);
        }

        @Override
        public void illlIIIliI(liIIiIliiI a, float b, int c, float d, rustme.IilIiIliiI e) {
            delegate.illlIIIliI(a, b, c, d, e);
        }

        @Override
        public void IlllIIIliI(int a) { delegate.IlllIIIliI(a); }

        @Override
        public void llllIIIliI(liIIiIliiI a, float b) { delegate.llllIIIliI(a, b); }

        @Override
        public void IiiilIIliI() { delegate.IiiilIIliI(); }
    }

    // ===== Хотбар форка: глушим наш =====
    // liIIliliiI.iillIIIliI(res) — метод, читающий кэш предмета в руке
    // (ililliil) и счётчик апдейта (llIlliil) = отрисовка хотбара форка.
    // CheatIngame его переопределяет: при включённом Hotbar рисуем только
    // свою панель, форковый хотбар пропускаем.

    @Override
    public void iillIIIliI(liIIiIliiI res) {
        if (modules.impl.Hotbar.isActive()) {
            return;
        }
        super.iillIIIliI(res);
    }

    @Override
    public void iliIiiIliI(float partialTicks) {
        callCount++;
        if (!firstCallLogged) {
            firstCallLogged = true;
        }

        // хотбар форка: подмена/возврат объекта-рендерера (до super — чтобы
        // уже этот кадр рисовал наш Hotbar вместо их слотов)
        try {
            syncForkHotbar();
        } catch (Throwable ignore) {}

        // JumpDistort: захват МИРА + волна искажения ПЕРЕД ванильным HUD —
        // порядок kimiko: мир → post-волна → HUD → кольцо. В бэкбуфере на этот
        // момент ещё чистый мир, поэтому волна не искажает HUD.
        try {
            modules.impl.JumpCircle.preOverlay(GameContext.get(), partialTicks);
        } catch (Throwable ignore) {}

        // Захват кадра (мир+небо, до HUD) — источник для JumpDistort-волны.
        try {
            GlassGrab.grab(GameContext.get());
        } catch (Throwable ignore) {}
        // Saturation: фулскрин-грейд готового мира ДО всего HUD
        // (бэкбуфер = мир+рука; HUD рисуется после и не тонируется).
        try {
            modules.impl.Saturation.grade(GameContext.get());
        } catch (Throwable ignore) {}
        // MotionBlur: направленные тапы + фидбэк поверх грейда, тоже до HUD.
        try {
            modules.impl.MotionBlur.blur(GameContext.get());
        } catch (Throwable ignore) {}
        // RenderGlobal-прокси (CustomSky/Ambience): своп объекта мирового
        // рендера — небо заменяется и тинт блоков применяются ВНУТРИ мирового
        // прохода (следующий кадр), без пиксельных эвристик.
        try {
            CheatRenderGlobal.sync(GameContext.get());
        } catch (Throwable ignore) {}

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
                }
            }
            CheatHud.renderFrame(scaledW, scaledH, partialTicks);
        } catch (Throwable t) {
            Log.error("HUD", "our overlay failed", t);
        }
    }
}
