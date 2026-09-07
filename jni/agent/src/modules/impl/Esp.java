package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import org.lwjglx.opengl.GL11;
import rustme.IIlIIliIiI;   // Entity root (координаты/повороты/бокс, геттеры сами декодируют XOR)
import rustme.iilliIliiI;   // GameSettings (fov-попытка; значение не fov — см. ZNANIA 14.12)
import rustme.lliIilliiI;   // камера мода: статик PROJ-буфер (захвачен в world-проходе)
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.CustomFont;
import utils.render.MsdfFont;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * ESP: 2D-боксы игроков (world.playerEntities) поверх мира. Клавиша G (GLFW 71).
 *
 * ПРОЕКЦИЯ: view строим сами (камера = интерполированные глаза локального игрока
 * prev/pos*pt + eyeHeight, yaw/pitch игрока); PROJ = статик lliIilliiI.lIIlIlIl,
 * если у неё перспктивная сигнатура (|m11|>0.5, |m15|<0.5, m10<0), иначе ручная
 * перспектива (fov дефолт 90 — захваченная PROJ мода показывает fovY=90).
 *
 * v3 (после теста 2: работает, но мало FPS и пропадает при зуме):
 *  - FPS: getName через reflection кэшируется (был getMethod() на каждого игрока
 *    каждый кадр — главный пожиратель); все боксы рисуются ОДНИМ glBegin/glEnd
 *    (было 4 begin/end на сущность с полным перенастраиванием состояния);
 *    тригонометрия view считается раз в кадр, не на каждый угол;
 *    строки diag собираются только в diag-окне.
 *  - Зум: угол AABB за плоскостью камеры больше НЕ скипает сущность —
 *    псевдо-клип (cw клампится в W_CLAMP, точка уезжает далеко в верную сторону,
 *    2D min/max остаётся накрывающим видимую часть). Скип только если ВСЕ углы
 *    за камерой (сущность целиком позади).
 */
public final class Esp extends Module {

    public static final int TOGGLE_KEY = 71; // GLFW_KEY_G

    private static final float MAX_DIST = 128f;      // дальность отрисовки (блоки)
    private static final float BOX_T = 1.25f;        // толщина линии (scaled)
    private static final int BOX_COLOR = 0xFF906BFF; // акцент темы
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final float TEXT_SIZE = 7f;
    private static final double TELEPORT_DELTA = 8.0; // скачок = телепорт, не интерполируем
    private static final int CORNERS = 8;
    private static final double NEAR = 0.05;
    private static final double W_CLAMP = 0.05;      // псевдо-клип w за плоскостью камеры
    private static final double DEFAULT_FOV = 90.0;  // реальный fovY мода (по захваченной PROJ)

    private static final long DIAG_WINDOW_MS = 20000L;
    private static final long DIAG_PERIOD_MS = 3000L;

    /** Синглтон для CheatHud (render — статик, state живёт в инстансе). */
    public static Esp INSTANCE;

    private boolean keyWasDown;
    private long enabledAt;

    // --- камера (пересчитывается раз в кадр) ---
    private static double camX, camY, camZ;
    private static float camYaw, camPitch;
    private static double vCosY, vSinY, vCosP, vSinP; // повороты view (раз в кадр)
    private static double aspect = 16.0 / 9.0;
    private static double pF = 1.0, pFOverAspect = 0.53; // константы ручной перспективы
    private static FloatBuffer capturedProj;
    private static boolean capturedProjPerspective;

    // --- результаты кадра (переиспользуемые буферы, рендер однопоточный) ---
    private static float[] boxBuf;    // 4 float на бокс: x0,y0,x1,y1 (scaled)
    private static String[] labelBuf; // подпись «имя Nm»
    private static int drawnCount;

    // --- диагностика ---
    private static boolean projDumped;
    private static long lastDiag;
    private static int statTotal, statDrawn, statBehind;
    private static int statTotalPrev, statDrawnPrev, statBehindPrev;

    // --- reflection-кэш имени ---
    private static Method profileGetter;    // IIiIIiIIiI.IlIiIiiilI() → GameProfile
    private static Method profileNameGetter; // GameProfile.getName()
    private static boolean nameResolved;

    public Esp() {
        super("ESP");
        INSTANCE = this;
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    handleKey();
                } catch (Throwable t) {
                    Log.error("ESP", "tick exception", t);
                }
            }
        });
        Log.info("ESP", "registered (toggle: G)");
    }

    @Override
    protected void onEnable() {
        enabledAt = System.currentTimeMillis();
        lastDiag = 0L;
    }

    private void handleKey() {
        GameContext ctx = GameContext.get();
        boolean down = ctx.isKeyDown(TOGGLE_KEY);
        if (down && !keyWasDown) {
            toggle();
            Log.info("ESP", "toggled by G -> " + (isState() ? "ON" : "OFF"));
        }
        keyWasDown = down;
    }

    /** Рендер всех боксов. Вызывается из CheatHud.renderFrame (главный поток). */
    public static void render(GameContext ctx, float partialTicks, int scaledW, int scaledH) {
        try {
            if (INSTANCE == null || !INSTANCE.isState()) return;
            if (!ctx.inWorld || ctx.world == null || ctx.player == null) return;
            if (!(ctx.player instanceof IIlIIliIiI)) return;

            // --- камера: глаза локального игрока (интерполяция + eye height) ---
            IIlIIliIiI lp = (IIlIIliIiI) ctx.player;
            double lx = lp.IlIiillIII();
            double ly = lp.liiiIllIII();
            double lz = lp.lIilillIII();
            double lpx = lp.IiilillIII();
            double lpy = lp.lliilIlIII();
            double lpz = lp.lilllIlIII();
            camX = Math.abs(lx - lpx) > TELEPORT_DELTA ? lx : lpx + (lx - lpx) * partialTicks;
            camZ = Math.abs(lz - lpz) > TELEPORT_DELTA ? lz : lpz + (lz - lpz) * partialTicks;
            camY = (Math.abs(ly - lpy) > TELEPORT_DELTA ? ly : lpy + (ly - lpy) * partialTicks)
                + lp.iliilIiilI(); // getEyeHeight = height*0.85
            camYaw = lp.IIiIillIII();
            camPitch = lp.iilIIIlIII();
            double yr = Math.toRadians((double) camYaw + 180.0);
            double pr = Math.toRadians((double) camPitch);
            vCosY = Math.cos(yr);
            vSinY = Math.sin(yr);
            vCosP = Math.cos(pr);
            vSinP = Math.sin(pr);

            // fov для ручного пути: gs-геттер возвращает НЕ fov (0.16) — валидируем
            double fov = DEFAULT_FOV;
            try {
                double f = ((iilliIliiI) ctx.gs).iIiiIilliI();
                if (f >= 30.0 && f <= 110.0) fov = f;
            } catch (Throwable ignore) {}
            aspect = scaledH > 0 ? (double) scaledW / (double) scaledH : 16.0 / 9.0;
            pF = 1.0 / Math.tan(Math.toRadians(fov) / 2.0);
            pFOverAspect = pF / aspect;

            // --- захваченная PROJ мода + проверка перспктивной сигнатуры ---
            capturedProjPerspective = false;
            capturedProj = null;
            try {
                FloatBuffer p = lliIilliiI.lIIlIlIl;
                if (p != null && p.capacity() >= 16) {
                    capturedProj = p;
                    float m10 = p.get(10), m11 = p.get(11), m15 = p.get(15);
                    capturedProjPerspective = Math.abs(m11) > 0.5f && Math.abs(m15) < 0.5f && m10 < 0f;
                    if (!projDumped) {
                        projDumped = true;
                        StringBuilder sb = new StringBuilder("PROJ captured: [");
                        for (int i = 0; i < 16; i++) {
                            if (i > 0) sb.append(' ');
                            sb.append(p.get(i));
                        }
                        sb.append("] -> ").append(capturedProjPerspective ? "perspective" : "NOT perspective (manual fov)");
                        Log.info("ESP", sb.toString());
                    }
                }
            } catch (Throwable ignore) {}

            // --- сбор боксов (без GL) ---
            statTotal = 0;
            statDrawn = 0;
            statBehind = 0;
            drawnCount = 0;

            List<?> players = (List<?>) ctx.playersField.get(ctx.world);
            if (players != null && !players.isEmpty()) {
                Object[] arr = players.toArray(new Object[0]); // снапшот: игра мутирует список
                if (boxBuf == null || boxBuf.length < arr.length * 4) {
                    boxBuf = new float[arr.length * 4 + 16];
                    labelBuf = new String[arr.length + 8];
                }
                for (int i = 0; i < arr.length; i++) {
                    Object el = arr[i];
                    if (el == null || el == ctx.player) continue;
                    if (ctx.localSpClass.isInstance(el)) continue;
                    if (!(el instanceof IIlIIliIiI)) continue;
                    statTotal++;
                    collectBox((IIlIIliIiI) el, partialTicks, scaledW, scaledH);
                }
            }
            statDrawn = drawnCount;

            // --- PASS 1: все боксы ОДНИМ begin/end ---
            if (drawnCount > 0) {
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4ub(
                    (byte) ((BOX_COLOR >> 16) & 0xFF),
                    (byte) ((BOX_COLOR >> 8) & 0xFF),
                    (byte) (BOX_COLOR & 0xFF),
                    (byte) ((BOX_COLOR >> 24) & 0xFF));
                GL11.glBegin(GL11.GL_QUADS);
                for (int i = 0; i < drawnCount; i++) {
                    float x0 = boxBuf[i * 4], y0 = boxBuf[i * 4 + 1];
                    float x1 = boxBuf[i * 4 + 2], y1 = boxBuf[i * 4 + 3];
                    // верхняя грань
                    GL11.glVertex2f(x0, y0);
                    GL11.glVertex2f(x0, y0 + BOX_T);
                    GL11.glVertex2f(x1, y0 + BOX_T);
                    GL11.glVertex2f(x1, y0);
                    // нижняя
                    GL11.glVertex2f(x0, y1 - BOX_T);
                    GL11.glVertex2f(x0, y1);
                    GL11.glVertex2f(x1, y1);
                    GL11.glVertex2f(x1, y1 - BOX_T);
                    // левая
                    GL11.glVertex2f(x0, y0);
                    GL11.glVertex2f(x0, y1);
                    GL11.glVertex2f(x0 + BOX_T, y1);
                    GL11.glVertex2f(x0 + BOX_T, y0);
                    // правая
                    GL11.glVertex2f(x1 - BOX_T, y0);
                    GL11.glVertex2f(x1 - BOX_T, y1);
                    GL11.glVertex2f(x1, y1);
                    GL11.glVertex2f(x1, y0);
                }
                GL11.glEnd();
                // цвет сбросить (кэш GlStateManager, 13.4.5); blend НЕ выключаем
                GL11.glColor4f(1f, 1f, 1f, 1f);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
            }

            // --- PASS 2: подписи (доказанный MSDF-путь) ---
            if (drawnCount > 0) {
                MsdfFont f = CustomFont.HUD_FONT;
                float cellH = CustomFont.cellHeight(TEXT_SIZE, f);
                for (int i = 0; i < drawnCount; i++) {
                    String label = labelBuf[i];
                    if (label == null || label.isEmpty()) continue;
                    float tw = CustomFont.getWidth(label, TEXT_SIZE, f);
                    float tx = (boxBuf[i * 4] + boxBuf[i * 4 + 2]) / 2f - tw / 2f;
                    if (tx < 2f) tx = 2f;
                    if (tx + tw > scaledW - 2f) tx = scaledW - tw - 2f;
                    float ty = boxBuf[i * 4 + 3] + 2f;
                    if (ty + cellH > scaledH) ty = scaledH - cellH - 1f; // бокс ушёл за низ — лепим к низу
                    CustomFont.drawString(label, tx, ty, TEXT_COLOR, false, TEXT_SIZE, f);
                }
            }

            // --- диагностика (первые 20с после включения, раз в 3с) ---
            long now = System.currentTimeMillis();
            boolean diagActive = now - INSTANCE.enabledAt < DIAG_WINDOW_MS;
            if (diagActive && now - lastDiag >= DIAG_PERIOD_MS) {
                lastDiag = now;
                Log.info("ESP", "diag: candidates=" + statTotalPrev + " drawn=" + statDrawnPrev
                    + " fullyBehind=" + statBehindPrev
                    + " cam=(" + round3(camX) + "," + round3(camY) + "," + round3(camZ) + ")"
                    + " yaw=" + round3(camYaw) + " pitch=" + round3(camPitch)
                    + " projPersp=" + capturedProjPerspective);
                if (drawnCount > 0) {
                    Log.info("ESP", "diag last: " + labelBuf[0]
                        + " box=[" + round3(boxBuf[0]) + ".." + round3(boxBuf[2])
                        + "]x[" + round3(boxBuf[1]) + ".." + round3(boxBuf[3]) + "]");
                } else if (statTotalPrev == 0) {
                    Log.info("ESP", "diag: candidates=0 — рядом нет других игроков");
                }
            }
            statTotalPrev = statTotal;
            statDrawnPrev = statDrawn;
            statBehindPrev = statBehind;
        } catch (Throwable t) {
            Log.error("ESP", "render exception", t);
        }
    }

    /** Считает бокс сущности, кладёт в буферы. Скип только если сущность целиком за камерой. */
    private static void collectBox(IIlIIliIiI e, float pt, int scaledW, int scaledH) {
        double px = e.IlIiillIII();
        double py = e.liiiIllIII();
        double pz = e.lIilillIII();
        double prevX = e.IiilillIII();
        double prevY = e.lliilIlIII();
        double prevZ = e.lilllIlIII();
        double rx = Math.abs(px - prevX) > TELEPORT_DELTA ? px : prevX + (px - prevX) * pt;
        double ry = Math.abs(py - prevY) > TELEPORT_DELTA ? py : prevY + (py - prevY) * pt;
        double rz = Math.abs(pz - prevZ) > TELEPORT_DELTA ? pz : prevZ + (pz - prevZ) * pt;

        double ddx = rx - camX, ddy = ry - camY, ddz = rz - camZ;
        double distSq = ddx * ddx + ddy * ddy + ddz * ddz;
        if (distSq > MAX_DIST * (double) MAX_DIST) return;

        rustme.ilIlIilIiI bb = e.iiIlIIlIII();
        if (bb == null) return;

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int rawBehind = 0;
        float[] out = TMP;
        for (int c = 0; c < CORNERS; c++) {
            double cx = ((c & 1) == 0) ? bb.lIiIilIlI : bb.iiiIilIlI;   // minX / maxX
            double cy = ((c & 2) == 0) ? bb.IiiIilIlI : bb.IIiIilIlI;   // minY / maxY
            double cz = ((c & 4) == 0) ? bb.liiIilIlI : bb.iIiIilIlI;   // minZ / maxZ
            double cwRaw = projectCorner(cx, cy, cz, scaledW, scaledH, out);
            if (cwRaw <= 0.0) {
                rawBehind++;
                continue;
            }
            if (out[0] < minX) minX = out[0];
            if (out[0] > maxX) maxX = out[0];
            if (out[1] < minY) minY = out[1];
            if (out[1] > maxY) maxY = out[1];
        }
        if (rawBehind >= CORNERS) {
            statBehind++;
            return; // сущность целиком позади
        }
        if (maxX <= minX || maxY <= minY) return;
        if (maxX - minX < 1f || maxY - minY < 1f) return;
        if (maxX < 0f || minX > scaledW || maxY < 0f || minY > scaledH) return; // целиком вне экрана

        int idx = drawnCount * 4;
        boxBuf[idx] = minX;
        boxBuf[idx + 1] = minY;
        boxBuf[idx + 2] = maxX;
        boxBuf[idx + 3] = maxY;
        String name = entityName(e);
        labelBuf[drawnCount] = (name.isEmpty() ? "" : name + " ")
            + String.valueOf(Math.round(Math.sqrt(distSq))) + "m";
        drawnCount++;
    }

    /**
     * мир → scaled-экран (top-left). Возвращает RAW cw (<=0 — за камерой);
     * сама проекция угла делается с клампом cw (псевдо-клип), out[0]=x, out[1]=y.
     */
    private static double projectCorner(double wx, double wy, double wz, int scaledW, int scaledH, float[] out) {
        double dx = wx - camX, dy = wy - camY, dz = wz - camZ;
        double x1 = dx * vCosY + dz * vSinY;
        double z1 = -dx * vSinY + dz * vCosY;
        double y2 = vCosP * dy - vSinP * z1;
        double z2 = vSinP * dy + vCosP * z1; // forward = -z
        double cx, cy, cw;
        if (capturedProjPerspective && capturedProj != null) {
            FloatBuffer p = capturedProj;
            cx = p.get(0) * x1 + p.get(4) * y2 + p.get(8) * z2 + p.get(12);
            cy = p.get(1) * x1 + p.get(5) * y2 + p.get(9) * z2 + p.get(13);
            cw = p.get(3) * x1 + p.get(7) * y2 + p.get(11) * z2 + p.get(15);
        } else {
            cx = pFOverAspect * x1;
            cy = pF * y2;
            cw = -z2;
        }
        double cwRaw = cw;
        if (cw < W_CLAMP) cw = W_CLAMP; // псевдо-клип: точка уезжает далеко в верную сторону
        double ndcX = cx / cw;
        double ndcY = cy / cw;
        out[0] = (float) ((ndcX + 1.0) / 2.0 * scaledW);
        out[1] = (float) ((1.0 - ndcY) / 2.0 * scaledH);
        return cwRaw;
    }

    // ===== утилиты =====

    private static final float[] TMP = new float[2];

    /** Имя сущности: GameProfile.getName() (кэш методов), fallback — Entity.getName(). */
    private static String entityName(IIlIIliIiI e) {
        try {
            if (!nameResolved) {
                nameResolved = true;
                GameContext ctx = GameContext.get();
                profileGetter = ctx.wrapperClass.getMethod("IlIiIiiilI");
                profileNameGetter = profileGetter.getReturnType().getMethod("getName");
            }
            if (profileGetter != null && profileNameGetter != null) {
                Object profile = profileGetter.invoke(e);
                if (profile != null) {
                    Object n = profileNameGetter.invoke(profile);
                    if (n instanceof String) {
                        String s = (String) n;
                        if (!s.isEmpty()) return s;
                    }
                }
            }
        } catch (Throwable ignore) {}
        try {
            String s = e.getName();
            if (s != null && !s.isEmpty()) return s;
        } catch (Throwable ignore) {}
        return "";
    }

    private static String round3(double v) {
        long r = Math.round(v * 1000.0);
        return String.valueOf(r / 1000.0);
    }
}
