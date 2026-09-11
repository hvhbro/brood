package modules.impl;

import modules.api.Module;
import org.lwjglx.opengl.GL11;
import rustme.IIlIIliIiI;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.GuiScale;

import java.util.ArrayList;
import java.util.List;

/**
 * Hitmarker — ✕-маркер на месте попадания в чужого игрока (референс:
 * gamesense-стиль hitmarker из ТЗ юзера — 4 диагональных отрезка 3..6px
 * вокруг точки, анимация «сближения» moved, фейд альфой).
 *
 * ДЕТЕКТ (клиент-предикшн, без реверса сервера): клик атаки (ЛКМ edge) →
 * луч из глаз локального игрока по направлению взгляда (yaw/pitch геттеры
 * IIiIillIII/iilIIIlIII — XOR-самодекод) → ray-AABB тест по всем чужим
 * игрокам (AABB = iiIlIIlIII(), поля 14.1: minX/maxX=lIiIilIlI/iiiIilIlI,
 * minY/maxY=IiiIilIlI/IIiIilIlI, minZ/maxZ=liiIilIlI/iIiIilIlI). Ближайшее
 * пересечение в радиусе = точка попадания (мировые координаты).
 *
 * ПРИВЯЗКА К МИРУ: маркер хранит мировую точку и каждый кадр проецируется
 * через Esp.projectToScreen (камера — Esp.updateCamera: глаза локального,
 * интерполяция partialTicks). Пока смотришь в сторону — маркер «висит» в
 * мире ровно там, где попал; развернулся — уходит с экраном корректно.
 *
 * АНИМАЦИЯ (референс): moved 8→0 (сближение, ~20/сек), затем маркер держится
 * Длительность (слайдер, дефолт 5с) и фейдится 1с (alpha 255/сек, как
 * step=255/1.0*frametime). Референс-цвет — белый, толщина 1px (GL_LINES).
 */
public final class Hitmarker extends Module {

    private static Hitmarker INSTANCE;

    public final Module.FloatSetting stDuration = addSetting("Длительность", 1f, 10f, 0.5f, 5f);

    private static final float FADE_SEC = 1f;        // alpha 255 → 0 за 1с
    private static final double MAX_RANGE = 150.0;   // дальность луча (Rust-огнестрел)

    private static class Hit {
        final double x, y, z;   // мировая точка попадания
        final long bornMs;
        final boolean confirmed; // true = серверное подтверждение (hit-звук)
        float alpha = 255f;

        Hit(double x, double y, double z, long now) {
            this(x, y, z, now, false);
        }

        Hit(double x, double y, double z, long now, boolean confirmed) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.bornMs = now;
            this.confirmed = confirmed;
        }
    }

    private final ArrayList hits = new ArrayList();
    private boolean lastMouseDown;
    private long lastFrame;
    private long lastAttackMs;      // последний клик атаки (окно для сервер-подтверждения)
    private long lastConfirmLog;

    private static final long ATTACK_WINDOW_MS = 1200L;
    private static final double VICTIM_SNAP = 3.0; // жертва = игрок в 3м от точки звука

    public Hitmarker() {
        super("Hitmarker", "Visuals");
        INSTANCE = this;
        // слой 2: серверное подтверждение через hurt/hit-звуки (поллинг SoundEsp)
        SoundEsp.addHitSoundListener(new SoundEsp.HitSoundListener() {
            @Override
            public void onHitSound(String name, float x, float y, float z) {
                try {
                    onServerConfirm(name, x, y, z);
                } catch (Throwable ignore) {}
            }
        });
        Log.info("Hitmarker", "registered");
    }

    /** Рендер + детект. Вызывается из CheatHud.renderFrame (главный поток). */
    public static void render(GameContext ctx, float partialTicks, int scaledW, int scaledH) {
        Hitmarker inst = INSTANCE;
        if (inst == null) return;
        try {
            inst.tick(ctx, partialTicks, scaledW, scaledH);
        } catch (Throwable t) {
            Log.error("Hitmarker", "render failed", t);
        }
    }

    private void tick(GameContext ctx, float partialTicks, int scaledW, int scaledH) {
        if (!isState()) return;
        if (!ctx.inWorld || ctx.world == null || ctx.player == null) {
            hits.clear();
            return;
        }

        long now = System.currentTimeMillis();
        float dt = lastFrame == 0L ? 16f : Math.min(100f, now - lastFrame);
        lastFrame = now;
        float dtSec = dt / 1000f;
        float holdMs = stDuration.value * 1000f;

        // камера (глаза локального) — обязаны обновлять сами (Esp может быть выключен)
        Esp.updateCamera(ctx, partialTicks);

        // --- детект: ЛКМ edge + луч по игрокам (в меню не детектим — клики GUI) ---
        boolean down = ctx.isMouseButtonDown(0);
        if (down && !lastMouseDown && !modules.impl.MenuModule.isOpen() && Esp.cameraReady()) {
            lastAttackMs = now;
            detectHit(ctx);
        }
        lastMouseDown = down;

        // --- анимация + чистка (listener'ы шлют из агентного потока — синхронизация) ---
        ArrayList draw;
        synchronized (hits) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = (Hit) hits.get(i);
                if (now - h.bornMs > holdMs) {
                    h.alpha -= 255f * dtSec / FADE_SEC;
                }
                if (h.alpha <= 0f || now - h.bornMs > holdMs + FADE_SEC * 1000f + 500L) {
                    hits.remove(i);
                }
            }
            draw = new ArrayList(hits); // снапшот для рендера без блокировки
        }
        if (draw.isEmpty()) return;

        // --- рендер: проецируем мировую точку, рисуем ✕ (форма 1:1 референс:
        // 4 диагонали от ±3 до ±6, статичные; толщина = guiScale — иначе при
        // guiScale 2 линии выглядят вдвое тоньше референсных) ---
        float[] out = new float[2];
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glLineWidth(Math.max(1f, GuiScale.get(ctx)));
        GL11.glBegin(GL11.GL_LINES);
        for (int i = 0; i < draw.size(); i++) {
            Hit h = (Hit) draw.get(i);
            Esp.projectToScreen(h.x, h.y, h.z, scaledW, scaledH, out);
            if (Esp.lastW() <= 0.05) continue; // позади камеры
            float sx = out[0], sy = out[1];
            if (sx < -20f || sy < -20f || sx > scaledW + 20f || sy > scaledH + 20f) continue;
            int a = (int) Math.max(0f, Math.min(255f, h.alpha));
            if (a <= 0) continue;
            // цвет референса: белый с текущей альфой
            GL11.glColor4f(1f, 1f, 1f, a / 255f);
            GL11.glVertex2f(sx + 3f, sy + 3f); GL11.glVertex2f(sx + 6f, sy + 6f); // SE
            GL11.glVertex2f(sx - 3f, sy - 3f); GL11.glVertex2f(sx - 6f, sy - 6f); // NW
            GL11.glVertex2f(sx + 3f, sy - 3f); GL11.glVertex2f(sx + 6f, sy - 6f); // NE
            GL11.glVertex2f(sx - 3f, sy + 3f); GL11.glVertex2f(sx - 6f, sy + 6f); // SW
        }
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** ЛКМ-edge: луч из глаз → ближайший чужой игрок по AABB. */
    private void detectHit(GameContext ctx) {
        if (!(ctx.player instanceof IIlIIliIiI)) return;
        IIlIIliIiI lp = (IIlIIliIiI) ctx.player;
        double ox = Esp.camX(), oy = Esp.camY(), oz = Esp.camZ();
        double yawRad = Math.toRadians(lp.IIiIillIII());
        double pitchRad = Math.toRadians(lp.iilIIIlIII());
        double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * Math.cos(pitchRad);

        List<?> players;
        try {
            players = (List<?>) ctx.playersField.get(ctx.world);
        } catch (Throwable t) {
            return;
        }
        if (players == null || players.isEmpty()) return;
        Object[] arr = players.toArray(new Object[0]); // снапшот: игра мутирует список

        double bestT = Double.MAX_VALUE;
        double bx = 0, by = 0, bz = 0;
        boolean found = false;
        for (int i = 0; i < arr.length; i++) {
            Object el = arr[i];
            if (el == null || el == ctx.player) continue;
            if (ctx.localSpClass.isInstance(el)) continue;
            if (!(el instanceof IIlIIliIiI)) continue;
            IIlIIliIiI e = (IIlIIliIiI) el;
            rustme.ilIlIilIiI bb = e.iiIlIIlIII();
            if (bb == null) continue;
            double t = rayAABB(ox, oy, oz, dx, dy, dz,
                bb.lIiIilIlI, bb.IiiIilIlI, bb.liiIilIlI,
                bb.iiiIilIlI, bb.IIiIilIlI, bb.iIiIilIlI);
            if (t >= 0 && t < bestT && t <= MAX_RANGE) {
                bestT = t;
                found = true;
            }
        }
        if (found) {
            synchronized (hits) {
                hits.add(new Hit(ox + dx * bestT, oy + dy * bestT, oz + dz * bestT,
                    System.currentTimeMillis()));
            }
        }
    }

    /**
     * Слой 2 — серверное подтверждение: сервер сыграл hurt/hit-звук на жертве.
     * Триггерим только если наш клик атаки был недавно (окно 1.2с — чужие
     * перестрелки не дают маркеров). Жертва = ближайший чужой игрок к точке
     * звука (≤3м); недавний предикшн-маркер заменяем точным (без дублей).
     */
    private void onServerConfirm(String name, float x, float y, float z) {
        if (!isState()) return;
        long now = System.currentTimeMillis();
        if (now - lastAttackMs > ATTACK_WINDOW_MS) return;
        GameContext ctx = GameContext.get();
        if (ctx.world == null || ctx.playersField == null) return;

        double bx = x, by = y, bz = z;
        boolean found = false;
        try {
            List<?> players = (List<?>) ctx.playersField.get(ctx.world);
            if (players != null && !players.isEmpty()) {
                Object[] arr = players.toArray(new Object[0]);
                double best = VICTIM_SNAP * VICTIM_SNAP;
                for (int i = 0; i < arr.length; i++) {
                    Object el = arr[i];
                    if (el == null || el == ctx.player) continue;
                    if (ctx.localSpClass.isInstance(el)) continue;
                    if (!(el instanceof IIlIIliIiI)) continue;
                    rustme.ilIlIilIiI bb = ((IIlIIliIiI) el).iiIlIIlIII();
                    if (bb == null) continue;
                    double cx = (bb.lIiIilIlI + bb.iiiIilIlI) * 0.5;
                    double cy = (bb.IiiIilIlI + bb.IIiIilIlI) * 0.5;
                    double cz = (bb.liiIilIlI + bb.iIiIilIlI) * 0.5;
                    double d2 = sq(cx - x) + sq(cy - y) + sq(cz - z);
                    if (d2 < best) {
                        best = d2;
                        bx = cx;
                        by = cy;
                        bz = cz;
                        found = true;
                    }
                }
            }
        } catch (Throwable ignore) {}
        if (!found) return; // звук есть, но жертва-игрок не найдена (моб/мы) — мимо

        synchronized (hits) {
            // заменить недавний предикшн-маркер подтверждённым (не дублировать)
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = (Hit) hits.get(i);
                if (!h.confirmed && now - h.bornMs <= 500L) hits.remove(i);
            }
            hits.add(new Hit(bx, by, bz, now, true));
        }
        if (now - lastConfirmLog > 5000L) {
            lastConfirmLog = now;
            Log.info("Hitmarker", "server confirm: " + name);
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Сляб-тест луча против AABB; возвращает t входа (>=0) или -1. */
    private static double rayAABB(double ox, double oy, double oz,
                                  double dx, double dy, double dz,
                                  double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        double tMin = 0.0, tMax = Double.MAX_VALUE;
        // X
        if (Math.abs(dx) < 1e-9) {
            if (ox < minX || ox > maxX) return -1;
        } else {
            double t1 = (minX - ox) / dx, t2 = (maxX - ox) / dx;
            if (t1 > t2) { double tt = t1; t1 = t2; t2 = tt; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        // Y
        if (Math.abs(dy) < 1e-9) {
            if (oy < minY || oy > maxY) return -1;
        } else {
            double t1 = (minY - oy) / dy, t2 = (maxY - oy) / dy;
            if (t1 > t2) { double tt = t1; t1 = t2; t2 = tt; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        // Z
        if (Math.abs(dz) < 1e-9) {
            if (oz < minZ || oz > maxZ) return -1;
        } else {
            double t1 = (minZ - oz) / dz, t2 = (maxZ - oz) / dz;
            if (t1 > t2) { double tt = t1; t1 = t2; t2 = tt; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        return tMin;
    }
}
