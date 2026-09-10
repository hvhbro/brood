package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import rustme.IIlIIliIiI;
import utils.etc.GameContext;
import utils.etc.Log;

import java.util.List;

import org.lwjglx.opengl.GL11;

/**
 * Tracers (порт классической схемы): линия от низа экрана (снизу-центр, как
 * в референсе — из позиции камеры) к ногам каждого игрока.
 *
 * РЕНДЕР: overlay-фаза (CheatHud.renderFrame -> render), 2D-линии в
 * scaled-координатах через проекцию Esp (та же камера/PROJ — рендерим
 * в том же проходе после Esp, до HUD-плашек).
 *  - глухие к depth (GL_DEPTH_TEST off на время линий): видно сквозь стены;
 *  - GL_LINE_SMOOTH + width 1..1.5;
 *  - цвет по дистанции: зелёный (далеко) -> красный (ближе 50 блоков), как
 *    в референсе (50-dist)/50;
 *  - ignoreNaked УДАЛЁН: llIlliilII() = ванильный getTotalArmorValue (атрибут
 *    generic.armor), а на этом сервере броня КАСТОМНАЯ (armor-список инвентаря
 *    iiIIIiIIiI создаётся размером 0) → атрибут = 0 у ВСЕХ, фильтр отбраковывал
 *    каждого игрока (лог 08.09: candidates=N drawn=0 naked=N). Честного
 *    детекта «голого» по кастомной брони пока нет.
 *
 * Позиции: геттеры Entity (XOR-самодекод, ZNANIA 14.1) + интерполяция по
 * prev (телепорт-порог 8 блоков). Камера/проекция — переиспользуем Esp:
 * Esp.render выставляет статику ДО вызова Tracers.render (см. CheatHud).
 */
public final class Tracers extends Module {
    private static final double MAX_DIST = 128.0;
    private static final double COLOR_RANGE = 50.0; // референс: красный ближе 50 блоков
    private static final float LINE_W = 1.0f;
    private static final double TELEPORT_DELTA = 8.0;

    /** Синглтон для CheatHud. */
    public static Tracers INSTANCE;

    public Tracers() {
        super("Tracers", "Visuals");
        INSTANCE = this;
        Log.info("Tracers", "registered (toggle: menu bind)");
    }

    /** Линии до игроков. Вызывается из CheatHud.renderFrame после Esp.render. */
    public static void render(GameContext ctx, float partialTicks, int scaledW, int scaledH) {
        try {
            if (INSTANCE == null || !INSTANCE.isState()) return;
            if (!ctx.inWorld || ctx.world == null || ctx.player == null) return;

            // Камеру/проекцию ОБНОВЛЯЕМ каждый кадр сами: camReady — защёлка
            // (после первого кадра ESP навсегда true), при выключенном ESP она
            // замораживала камеру/PROJ-сигнатуру на устаревших значениях.
            Esp.updateCamera(ctx, partialTicks);
            if (!Esp.cameraReady()) return;

            List<?> players = (List<?>) ctx.playersField.get(ctx.world);
            if (players == null || players.isEmpty()) return;
            Object[] arr = players.toArray(new Object[0]);

            boolean blendWas = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depthWas = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean texWas = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            boolean lineSmoothWas = GL11.glIsEnabled(GL11.GL_LINE_SMOOTH);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(LINE_W);

            GL11.glBegin(GL11.GL_LINES);
            for (int i = 0; i < arr.length; i++) {
                Object el = arr[i];
                if (el == null || el == ctx.player) continue;
                if (ctx.localSpClass.isInstance(el)) continue;
                if (!(el instanceof IIlIIliIiI)) continue;
                IIlIIliIiI e = (IIlIIliIiI) el;

                // интерполированные ноги (lastTick-схема, как Esp.collectBox:
                // у чужих prev==pos после тика → ступеньки; lastTick даёт плавность)
                double px = e.IlIiillIII();
                double py = e.liiiIllIII();
                double pz = e.lIilillIII();
                double prevX = e.IlilillIII();
                double prevY = e.lIiiIllIII();
                double prevZ = e.IliIlIlIII();
                double rx = Math.abs(px - prevX) > TELEPORT_DELTA ? px : prevX + (px - prevX) * partialTicks;
                double ry = Math.abs(py - prevY) > TELEPORT_DELTA ? py : prevY + (py - prevY) * partialTicks;
                double rz = Math.abs(pz - prevZ) > TELEPORT_DELTA ? pz : prevZ + (pz - prevZ) * partialTicks;

                double ddx = rx - Esp.camX(), ddy = ry - Esp.camY(), ddz = rz - Esp.camZ();
                double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                if (dist > MAX_DIST) continue;

                // проекция ног (за камерой — скип: линию не рисуем)
                float[] out = TMP;
                double cw = Esp.projectToScreen(rx, ry, rz, scaledW, scaledH, out);
                if (cw <= 0.0) continue;
                float sx = out[0], sy = out[1];

                // цвет по дистанции (референс): красный ближе 50, зелёный дальше
                float red = (float) Math.min(1.0, Math.max(0.0, (COLOR_RANGE - dist) / COLOR_RANGE));
                float green = 1.0f - red;

                GL11.glColor4f(red, green, 0f, 1f);
                // старт: низ-центр экрана (визуально «из меня»)
                GL11.glVertex2f(scaledW * 0.5f, scaledH);
                GL11.glVertex2f(sx, sy);
            }
            GL11.glEnd();

            GL11.glColor4f(1f, 1f, 1f, 1f);
            if (!lineSmoothWas) GL11.glDisable(GL11.GL_LINE_SMOOTH);

            if (texWas) GL11.glEnable(GL11.GL_TEXTURE_2D);
            if (depthWas) GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (!blendWas) GL11.glDisable(GL11.GL_BLEND);
        } catch (Throwable t) {
            Log.error("Tracers", "render exception", t);
        }
    }

    private static final float[] TMP = new float[2];
}
