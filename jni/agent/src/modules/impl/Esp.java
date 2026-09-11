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
import utils.render.RenderUtil;

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
 *
 * v4 (NameTags): ник + пинг (зелёный) НАД боксом, дистанция ПОД боксом.
 *  Пинг: player → iliiililiI() → iiIilIliiI → illIillliI() (живой, без Tab;
 *  iliiililiI лениво берёт снапшот из общей карты сетевого хендлера по UUID —
 *  работает и на ЧУЖИХ игроках; ZNANIA 14.3). -1 → без пинга.
 *
 * v5 (GearESP): иконки снаряжения НАД боксом — рука + броня чужого игрока.
 *  Данные уже на клиенте: пакет IiililIIiI (entityId+slot+stack) пишет в любую
 *  сущность через wrapper.iIiIliiilI(slot, stack) — MainHand падает в
 *  inventory main[current], Armor1..7 в armor-список (7 слотов). Чтение:
 *  wrapper.IiliIiiilI(slot) (getItemStackFromSlot). Рендер: RenderItem мода
 *  (gs.iIiIiilliI()) → iIIilIliII(stack,x,y) = 16x16 GUI-иконка (TransformType
 *  GUI — как в хотбаре), glScalef вокруг левого-верхнего угла ряда.
 *  Размер динамиеский: от ширины/высоты бокса, кламп [6, 14] scaled px.
 */
public final class Esp extends Module {

    private static final float MAX_DIST = 128f;      // дальность отрисовки (блоки)
    private static final float BOX_T = 1.25f;        // толщина линии (scaled)
    private static final int BOX_COLOR = 0xFF906BFF; // акцент темы
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int PING_COLOR = 0xFF00FF5A; // зелёный пинг
    private static final int FRIEND_COLOR = 0xFF4DFF6E; // ESP друзей (бокс+ник+дист)
    private static final float TEXT_SIZE = 7f;
    private static final double TELEPORT_DELTA = 8.0; // скачок = телепорт, не интерполируем
    private static final double THIRD_PERSON_DIST = 4.0; // дистанция камеры 3-го лица (ваниль)
    private static final int CORNERS = 8;
    private static final double NEAR = 0.05;
    private static final double W_CLAMP = 0.05;      // псевдо-клип w за плоскостью камеры
    private static final double DEFAULT_FOV = 90.0;  // реальный fovY мода (по захваченной PROJ)

    private static final long DIAG_WINDOW_MS = 20000L;
    private static final long DIAG_PERIOD_MS = 3000L;

    // --- GearESP (v5) ---
    private static final int GEAR_SLOTS = 8;          // MainHand + Armor1..7
    private static final float GEAR_MIN_SIZE = 6f;    // мин. иконка (scaled)
    private static final float GEAR_MAX_SIZE = 14f;   // макс. иконка (scaled)
    private static final float GEAR_GAP = 1.5f;       // зазор между иконками

    /** Сколько слотов собрано на игрока (0 = пусто, иконки не рисуем). */
    private static int[] gearCountBuf;

    /** Синглтон для CheatHud (render — статик, state живёт в инстансе). */
    public static Esp INSTANCE;

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
    private static boolean[] friendBuf; // бокс друга (зелёный ESP)
    private static String[] labelBuf; // подпись «имя Nm»
    private static int[] pingBuf;     // пинг игрока бокса, -1 = неизвестен
    private static int[] distBuf;     // дистанция бокса (м)
    private static Object[][] gearBuf; // [player][GEAR_SLOTS] стаки (null = пусто)
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

    // --- reflection-кэш пинга (то же, что Watermark, но на ЛЮБОГО игрока) ---
    // iiIililiiI.iliiililiI() лениво достаёт снапшот из ОБЩЕЙ карты сетевого
    // хендлера по UUID сущности (IIIiillliI(UUID) = Map.get), illIillliI() =
    // живой пинг (поле lIlllIIl, обновляется UPDATE_LATENCY, не Tab-ловушка).
    private static Class absPlayerClass;    // rustme.iiIililiiI (AbstractClientPlayer)
    private static Method playerInfoM;      // iiIililiiI.iliiililiI() → iiIilIliiI
    private static Method latencyM;         // iiIilIliiI.illIillliI() → int
    private static boolean netResolved;
    private static boolean netFailedLogged;

    private static boolean gearResolvedNow; // результат ctx.resolveGear() за этот кадр

    public Esp() {
        super("ESP", "Visuals");
        INSTANCE = this;
        Log.info("ESP", "registered (toggle: menu bind)");
    }

    @Override
    protected void onEnable() {
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
            // --- третье лицо (Thirdperson): смещение камеры + разворот front ---
            float effYaw = camYaw, effPitch = camPitch;
            int tpv = thirdPersonView();
            if (tpv == 2) {
                effYaw = camYaw + 180f;
                effPitch = -camPitch;
            }
            if (tpv != 0) {
                double[] tp = thirdPersonCam(ctx, camX, camY, camZ, camYaw, camPitch, tpv);
                camX = tp[0];
                camY = tp[1];
                camZ = tp[2];
            }
            double yr = Math.toRadians((double) effYaw + 180.0);
            double pr = Math.toRadians((double) effPitch);
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
                }
            } catch (Throwable ignore) {}

            camReady = true;

            // --- сбор боксов (без GL) ---
            statTotal = 0;
            statDrawn = 0;
            statBehind = 0;
            drawnCount = 0;

            List<?> players = (List<?>) ctx.playersField.get(ctx.world);
            if (players != null && !players.isEmpty()) {
                Object[] arr = players.toArray(new Object[0]); // снапшот: игра мутирует список
                if (boxBuf == null || boxBuf.length < arr.length * 4
                    || friendBuf == null || friendBuf.length < arr.length + 8) {
                    boxBuf = new float[arr.length * 4 + 16];
                    friendBuf = new boolean[arr.length + 8];
                    labelBuf = new String[arr.length + 8];
                    pingBuf = new int[arr.length + 8];
                    distBuf = new int[arr.length + 8];
                    gearCountBuf = new int[arr.length + 8];
                    gearBuf = new Object[arr.length + 8][];
                    for (int g = 0; g < gearBuf.length; g++) {
                        gearBuf[g] = new Object[GEAR_SLOTS];
                    }
                }
                // ленивый резолв снаряжения (RenderItem/слоты/геттер)
                gearResolvedNow = ctx.resolveGear();
                for (int i = 0; i < arr.length; i++) {
                    Object el = arr[i];
                    if (el == null || el == ctx.player) continue;
                    if (ctx.localSpClass.isInstance(el)) continue;
                    if (!(el instanceof IIlIIliIiI)) continue;
                    statTotal++;
                    IIlIIliIiI e = (IIlIIliIiI) el;
                    collectBox(e, partialTicks, scaledW, scaledH);
                }
            }
            statDrawn = drawnCount;

            // --- Dormant: призраки игроков, пропавших из списка <4.48с назад ---
            if (DORMANT_ENABLED) dormRender(ctx, partialTicks, scaledW, scaledH);

            // --- PASS 1: все боксы ОДНИМ begin/end (цвет на бокс: друзья зелёные) ---
            if (drawnCount > 0) {
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glBegin(GL11.GL_QUADS);
                for (int i = 0; i < drawnCount; i++) {
                    int bc = friendBuf[i] ? FRIEND_COLOR : BOX_COLOR;
                    GL11.glColor4ub(
                        (byte) ((bc >> 16) & 0xFF),
                        (byte) ((bc >> 8) & 0xFF),
                        (byte) (bc & 0xFF),
                        (byte) ((bc >> 24) & 0xFF));
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

            // --- PASS 1.5: иконки снаряжения над боксом (GearESP) ---
            // Фон убран (юзер 09-09: «оставь только иконки»): без подложки,
            // иконки — ряд над боксом, размер как раньше.
            if (drawnCount > 0 && gearResolvedNow) {
                GameContext gctx = GameContext.get();
                for (int i = 0; i < drawnCount; i++) {
                    int n = gearCountBuf[i];
                    if (n <= 0) continue;
                    float bx0 = boxBuf[i * 4], bx1 = boxBuf[i * 4 + 2];
                    float by0 = boxBuf[i * 4 + 1];

                    float boxW = bx1 - bx0;
                    float boxH = boxBuf[i * 4 + 3] - by0;
                    float size = boxH * 0.14f;                    // доля высоты бокса
                    if (boxH < 20f) size = boxW / 9f;             // дальний игрок: от ширины
                    if (size < GEAR_MIN_SIZE) size = GEAR_MIN_SIZE;
                    if (size > GEAR_MAX_SIZE) size = GEAR_MAX_SIZE;

                    float totalW = n * size + (n - 1) * GEAR_GAP;
                    float pad = 2f;
                    float bgX = (bx0 + bx1) / 2f - totalW / 2f - pad;
                    float bgY = by0 - size - pad * 2f;
                    if (bgY < 2f) bgY = 2f;

                    // иконки: ряд над боксом
                    float ix = bgX + pad;
                    Object[] stacks = gearBuf[i];
                    for (int s = 0; s < GEAR_SLOTS; s++) {
                        Object stack = stacks[s];
                        if (stack == null) continue;
                        drawItemIcon(gctx, stack, ix, bgY + pad, size);
                        ix += size + GEAR_GAP;
                    }
                    // цвет в нейтраль после рендера иконок (13.4.5)
                    GL11.glColor4f(1f, 1f, 1f, 1f);
                }
            }

            // --- PASS 2: подписи (доказанный MSDF-путь) ---
            // Раскладка (юзер 09-09): ник + пинг СВЕРХУ игрока, дистанция СНИЗУ.
            // Над боксом может висеть ряд иконок GearESP — ник рисуем выше иконок.
            if (drawnCount > 0) {
                MsdfFont f = CustomFont.HUD_FONT;
                float cellH = CustomFont.cellHeight(TEXT_SIZE, f);
                float gap = 2.5f; // зазор между ником и пингом
                for (int i = 0; i < drawnCount; i++) {
                    String name = labelBuf[i];
                    if (name == null || name.isEmpty()) continue;
                    int ping = pingBuf[i];
                    String dist = String.valueOf(distBuf[i]) + "m";

                    float nameW = CustomFont.getWidth(name, TEXT_SIZE, f);
                    String pingStr = ping >= 0 ? String.valueOf(ping) + "ms" : null;
                    float pingW = pingStr != null ? CustomFont.getWidth(pingStr, TEXT_SIZE, f) : 0f;
                    float line1W = nameW + (pingStr != null ? gap + pingW : 0f);
                    float line2W = CustomFont.getWidth(dist, TEXT_SIZE, f);

                    float boxCx = (boxBuf[i * 4] + boxBuf[i * 4 + 2]) / 2f;

                    // ник + пинг: по центру бокса (друзья — зелёным)
                    float tx = boxCx - line1W / 2f;
                    if (tx < 2f) tx = 2f;
                    if (tx + line1W > scaledW - 2f) tx = scaledW - line1W - 2f;

                    float x0 = boxBuf[i * 4], y0 = boxBuf[i * 4 + 1];
                    float y1 = boxBuf[i * 4 + 3];

                    // верх: ник + пинг над иконками GearESP (или сразу над боксом)
                    float topY = y0 - cellH - 2f;
                    if (gearResolvedNow && gearCountBuf[i] > 0) {
                        int n = gearCountBuf[i];
                        float boxW = boxBuf[i * 4 + 2] - x0;
                        float boxH = y1 - y0;
                        float size = boxH * 0.14f;
                        if (boxH < 20f) size = boxW / 9f;
                        if (size < GEAR_MIN_SIZE) size = GEAR_MIN_SIZE;
                        if (size > GEAR_MAX_SIZE) size = GEAR_MAX_SIZE;
                        topY = y0 - size - 6f - cellH;
                    }
                    if (topY < 2f) topY = 2f;

                    // низ: дистанция СТРОГО по центру бокса (отдельная от ника строка)
                    float bottomY = y1 + 2f;
                    if (bottomY + cellH > scaledH) bottomY = scaledH - cellH - 1f;
                    float distX = boxCx - line2W / 2f;
                    if (distX < 2f) distX = 2f;
                    if (distX + line2W > scaledW - 2f) distX = scaledW - line2W - 2f;

                    CustomFont.drawString(name, tx, topY,
                        friendBuf[i] ? FRIEND_COLOR : TEXT_COLOR, false, TEXT_SIZE, f);
                    if (pingStr != null) {
                        CustomFont.drawString(pingStr, tx + nameW + gap, topY, PING_COLOR, false, TEXT_SIZE, f);
                    }
                    CustomFont.drawString(dist, distX, bottomY,
                        friendBuf[i] ? FRIEND_COLOR : TEXT_COLOR, false, TEXT_SIZE, f);
                }
            }

            statTotalPrev = statTotal;
            statDrawnPrev = statDrawn;
            statBehindPrev = statBehind;
        } catch (Throwable t) {
            Log.error("ESP", "render exception", t);
        }
    }

    /** Друг ли (по нику). Пустой ник — не друг. */
    private static boolean isFriendName(String name) {
        try {
            return name != null && !name.isEmpty() && utils.etc.Friends.isFriend(name);
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** Считает бокс сущности, кладёт в буферы. Скип только если сущность целиком за камерой. */
    private static void collectBox(IIlIIliIiI e, float pt, int scaledW, int scaledH) {
        double px = e.IlIiillIII();
        double py = e.liiiIllIII();
        double pz = e.lIilillIII();
        // У ЧУЖИХ интерполяция по LASTTICK (ванильная схема рендера): сетевые
        // пакеты двигают pos ВНЕ тика, prev после тика == pos → рендер по prev
        // ступенчато «телепортируется». lastTick + (pos-lastTick)*pt = плавно.
        double prevX = e.IlilillIII();
        double prevY = e.lIiiIllIII();
        double prevZ = e.IliIlIlIII();
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
        labelBuf[drawnCount] = name; // дистанция теперь под боксом (distBuf)
        pingBuf[drawnCount] = entityPing(e);
        distBuf[drawnCount] = (int) Math.round(Math.sqrt(distSq));
        friendBuf[drawnCount] = isFriendName(name);
        gearCountBuf[drawnCount] = gearResolvedNow ? collectGear(e, drawnCount) : 0;
        // Dormant: живые данные призрака (ник/пинг/позиция — рисуется после ухода из списка)
        dormUpdate(e.iiIIIIlIII(), px, py, pz, name, pingBuf[drawnCount]);
        drawnCount++;
    }

    /** Собирает иконки снаряжения игрока в gearBuf[row]. Возвращает число непустых. */
    private static int collectGear(IIlIIliIiI e, int row) {
        GameContext ctx = GameContext.get();
        Object[] slots = ctx.equipSlots;
        Object[] rowBuf = gearBuf[row];
        int n = 0;
        for (int s = 0; s < slots.length && n < GEAR_SLOTS; s++) {
            try {
                Object stack = ctx.getItemStackFromSlot.invoke(e, slots[s]);
                if (stack == null) continue;
                if ((Boolean) ctx.stackIsEmpty.invoke(stack)) continue;
                rowBuf[n++] = stack;
            } catch (Throwable ignore) {
                // слот недоступен (странный энтити/enum) — пропускаем тихо
            }
        }
        for (int s = n; s < GEAR_SLOTS; s++) rowBuf[s] = null; // хвост прошлого кадра убрать
        return n;
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

    // ===== API для Tracers (камера/проекция) =====

    /** Готова ли камера/проекция (выставляются в render). */
    public static boolean cameraReady() {
        return camReady;
    }

    /** Выставить камеру/проекцию без рендера боксов (для Tracers при выключенном ESP). */
    public static void updateCamera(GameContext ctx, float partialTicks) {
        try {
            if (!ctx.inWorld || ctx.player == null) return;
            if (!(ctx.player instanceof IIlIIliIiI)) return;
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
                + lp.iliilIiilI();
            camYaw = lp.IIiIillIII();
            camPitch = lp.iilIIIlIII();
            float effYaw2 = camYaw, effPitch2 = camPitch;
            int tpv2 = thirdPersonView();
            if (tpv2 == 2) {
                effYaw2 = camYaw + 180f;
                effPitch2 = -camPitch;
            }
            if (tpv2 != 0) {
                double[] tp2 = thirdPersonCam(ctx, camX, camY, camZ, camYaw, camPitch, tpv2);
                camX = tp2[0];
                camY = tp2[1];
                camZ = tp2[2];
            }
            double yr = Math.toRadians((double) effYaw2 + 180.0);
            double pr = Math.toRadians((double) effPitch2);
            vCosY = Math.cos(yr);
            vSinY = Math.sin(yr);
            vCosP = Math.cos(pr);
            vSinP = Math.sin(pr);

            double fov = DEFAULT_FOV;
            try {
                double f = ((iilliIliiI) ctx.gs).iIiiIilliI();
                if (f >= 30.0 && f <= 110.0) fov = f;
            } catch (Throwable ignore) {}
            aspect = ctx.scaledHeight > 0 ? (double) ctx.scaledWidth / (double) ctx.scaledHeight : 16.0 / 9.0;
            pF = 1.0 / Math.tan(Math.toRadians(fov) / 2.0);
            pFOverAspect = pF / aspect;

            capturedProjPerspective = false;
            capturedProj = null;
            try {
                FloatBuffer p = lliIilliiI.lIIlIlIl;
                if (p != null && p.capacity() >= 16) {
                    capturedProj = p;
                    float m10 = p.get(10), m11 = p.get(11), m15 = p.get(15);
                    capturedProjPerspective = Math.abs(m11) > 0.5f && Math.abs(m15) < 0.5f && m10 < 0f;
                }
            } catch (Throwable ignore) {}
            camReady = true;
        } catch (Throwable t) {
            Log.error("ESP", "updateCamera failed", t);
        }
    }

    /** Режим вида 0/1/2 из Thirdperson (0 = первое лицо). */
    public static int thirdPersonView() {
        try {
            int v = Thirdperson.currentView();
            if (v >= 0 && v <= 2) return v;
        } catch (Throwable ignore) {}
        return 0;
    }

    /**
     * Позиция камеры третьего лица (ванильный orientCamera): view 1 (сзади) =
     * глаза минус взгляд*dist, view 2 (спереди) = глаза плюс взгляд*dist,
     * с клипом о блоки лучом глаза→камера. Взгляд — из СЫРЫХ yaw/pitch
     * (MC: pitch+ = вниз), разворот front применяется к НАПРАВЛЕНИЮ view,
     * а не к смещению. Возвращает {x,y,z} (новый массив).
     */
    public static double[] thirdPersonCam(GameContext ctx, double ex, double ey, double ez,
                                          float yaw, float pitch, int view) {
        double[] out = new double[3];
        double yr = Math.toRadians((double) yaw);
        double pr = Math.toRadians((double) pitch);
        double lx = -Math.sin(yr) * Math.cos(pr);
        double ly = -Math.sin(pr);
        double lz = Math.cos(yr) * Math.cos(pr);
        double s = view == 2 ? THIRD_PERSON_DIST : -THIRD_PERSON_DIST;
        double dx = ex + lx * s, dy = ey + ly * s, dz = ez + lz * s;
        try {
            double[] clip = clipCamera(ctx, ex, ey, ez, dx, dy, dz);
            if (clip != null) {
                dx = clip[0];
                dy = clip[1];
                dz = clip[2];
            }
        } catch (Throwable ignore) {}
        out[0] = dx;
        out[1] = dy;
        out[2] = dz;
        return out;
    }

    // ===== клип камеры о блоки (луч World.IlIilllllI, fail-open без клипа) =====
    private static java.lang.reflect.Constructor<?> tpVecCtor;
    private static java.lang.reflect.Method tpRayM;
    private static java.lang.reflect.Field tpHitVecF;  // HitResult.iIIiIlIlI (Vec3)
    private static java.lang.reflect.Field tpVXF, tpVYF, tpVZF; // Vec3 xyz (double)
    private static boolean tpReconTried;
    private static boolean tpClipFailedLogged;

    /** Точка клипа камеры о блок или null (чисто / нет recon). */
    private static double[] clipCamera(GameContext ctx, double x0, double y0, double z0,
                                       double x1, double y1, double z1) {
        try {
            if (!tpReconTried) {
                tpReconTried = true;
                Class vecC = ctx.gameLoader.loadClass("rustme.lliililIiI");
                tpVecCtor = vecC.getConstructor(Double.TYPE, Double.TYPE, Double.TYPE);
                tpRayM = ctx.worldClass.getMethod("IlIilllllI", vecC, vecC);
                Class hitC = ctx.gameLoader.loadClass("rustme.lIiililIiI");
                tpHitVecF = getFieldR(hitC, "iIIiIlIlI");
                tpVXF = getFieldR(vecC, "liiIIlIlI");
                tpVYF = getFieldR(vecC, "IiiIIlIlI");
                tpVZF = getFieldR(vecC, "IIiIIlIlI");
            }
            if (tpVecCtor == null || tpRayM == null || ctx.world == null) return null;
            Object a = tpVecCtor.newInstance(Double.valueOf(x0), Double.valueOf(y0), Double.valueOf(z0));
            Object b = tpVecCtor.newInstance(Double.valueOf(x1), Double.valueOf(y1), Double.valueOf(z1));
            Object hit = tpRayM.invoke(ctx.world, a, b);
            if (hit == null || tpHitVecF == null) return null;
            Object hv = tpHitVecF.get(hit);
            if (hv == null || tpVXF == null) return null;
            return new double[]{
                ((Double) tpVXF.get(hv)).doubleValue(),
                ((Double) tpVYF.get(hv)).doubleValue(),
                ((Double) tpVZF.get(hv)).doubleValue()
            };
        } catch (Throwable t) {
            if (!tpClipFailedLogged) {
                tpClipFailedLogged = true;
                Log.error("ESP", "camera clip failed (fail-open)", t);
            }
            return null;
        }
    }

    private static java.lang.reflect.Field getFieldR(Class c, String name) throws Throwable {
        try {
            java.lang.reflect.Field f = c.getField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            java.lang.reflect.Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
    }

    /** Проекция точки мира -> scaled-экран. Возвращает RAW cw (<=0 — за камерой). */
    // ===== Dormant ESP (порт конкурента: memoryEspDormant, 4.48с) =====
    private static final boolean DORMANT_ENABLED = true;
    private static final long DORMANT_TIME_MS = 4480L;

    /** Последняя известная позиция игрока: entityId → {x,y,z,time}. */
    private static final java.util.HashMap<Long, double[]> dormPos =
        new java.util.HashMap<Long, double[]>();
    private static final java.util.HashMap<Long, String> dormName =
        new java.util.HashMap<Long, String>();
    private static final java.util.HashMap<Long, Integer> dormPing =
        new java.util.HashMap<Long, Integer>();

    /** Обновление живых данных призрака (из collectBox — ник/пинг уже посчитаны). */
    private static void dormUpdate(long id, double x, double y, double z, String name, int ping) {
        Long key = Long.valueOf(id);
        // на друзей dormant не работает: не храним + чистим старый призрак
        if (isFriendName(name)) {
            if (dormPos.remove(key) != null) {
                dormName.remove(key);
                dormPing.remove(key);
            }
            return;
        }
        double[] p = dormPos.get(key);
        if (p == null) { p = new double[4]; dormPos.put(key, p); }
        p[0] = x; p[1] = y; p[2] = z; p[3] = System.currentTimeMillis();
        if (name != null && !name.isEmpty()) dormName.put(key, name);
        dormPing.put(key, Integer.valueOf(ping));
    }

    /** Призрак: рисуем бокс по последней позиции, если игрок пропал <4.48с. */
    private static void dormRender(GameContext ctx, float partialTicks, int scaledW, int scaledH) {
        try {
            if (!cameraReady()) return;
            long now = System.currentTimeMillis();
            java.util.Iterator<java.util.Map.Entry<Long, double[]>> it =
                dormPos.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<Long, double[]> en = it.next();
                double[] p = en.getValue();
                long age = now - (long) p[3];
                if (age > DORMANT_TIME_MS) {
                    it.remove();
                    dormName.remove(en.getKey());
                    dormPing.remove(en.getKey());
                    continue;
                }
                // друзья не рисуются (даже если добавили уже после пропажи)
                String dn = (String) dormName.get(en.getKey());
                if (isFriendName(dn)) {
                    it.remove();
                    dormName.remove(en.getKey());
                    dormPing.remove(en.getKey());
                    continue;
                }
                // рисуем только если игрок УШЁЛ из живого списка (иначе он в основных боксах)
                if (age > 100L) {
                    renderDormantBox(ctx, en.getKey().longValue(), p, age, scaledW, scaledH);
                }
            }
        } catch (Throwable t) {
            Log.error("ESP", "dormant render failed", t);
        }
    }

    /** Полупрозрачный жёлтый бокс призрака + подписи как в обычном ESP. */
    private static void renderDormantBox(GameContext ctx, long id, double[] p, long age,
                                         int scaledW, int scaledH) {
        float[] out = TMP;
        // 8 углов призрачного бокса (стандартный рост 1.8)
        double x0 = p[0] - 0.3, x1 = p[0] + 0.3;
        double y0 = p[1],       y1 = p[1] + 1.8;
        double z0 = p[2] - 0.3, z1 = p[2] + 0.3;
        int[][] corners = {
            {0,0,0},{1,0,0},{1,0,1},{0,0,1},
            {0,1,0},{1,1,0},{1,1,1},{0,1,1}
        };
        double[][] pts = new double[8][3];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int behind = 0;
        for (int c = 0; c < 8; c++) {
            double cx = corners[c][0] == 0 ? x0 : x1;
            double cy = corners[c][1] == 0 ? y0 : y1;
            double cz = corners[c][2] == 0 ? z0 : z1;
            double cw = projectToScreen(cx, cy, cz, scaledW, scaledH, out);
            if (cw <= 0.0) { behind++; continue; }
            pts[c][0] = out[0]; pts[c][1] = out[1];
            if (out[0] < minX) minX = out[0];
            if (out[0] > maxX) maxX = out[0];
            if (out[1] < minY) minY = out[1];
            if (out[1] > maxY) maxY = out[1];
        }
        if (behind >= 8) return;
        if (maxX <= minX || maxY <= minY) return;

        // жёлтый @ ~(35 + 25*(1-ageFrac))% альфы (гаснет со временем)
        float frac = 1.0f - (float) age / (float) DORMANT_TIME_MS;
        int alpha = (int) (255f * (0.20f + 0.20f * frac));
        int col = (alpha << 24) | 0xFFD700;
        drawBoxEdges(pts, corners, col);

        // подписи как в обычном ESP: ник + пинг сверху, дистанция снизу
        // (от последней известной позиции до камеры)
        String name = dormName.get(Long.valueOf(id));
        if (name != null && !name.isEmpty() && CustomFont.HUD_FONT != null) {
            MsdfFont f = CustomFont.HUD_FONT;
            float cellH = CustomFont.cellHeight(TEXT_SIZE, f);
            float gap = 2.5f;
            Integer pingO = (Integer) dormPing.get(Long.valueOf(id));
            int ping = pingO != null ? pingO.intValue() : -1;
            double ddx = p[0] - camX, ddy = p[1] - camY, ddz = p[2] - camZ;
            String dist = String.valueOf((int) Math.round(
                Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz))) + "m";

            float nameW = CustomFont.getWidth(name, TEXT_SIZE, f);
            String pingStr = ping >= 0 ? String.valueOf(ping) + "ms" : null;
            float pingW = pingStr != null ? CustomFont.getWidth(pingStr, TEXT_SIZE, f) : 0f;
            float line1W = nameW + (pingStr != null ? gap + pingW : 0f);
            float line2W = CustomFont.getWidth(dist, TEXT_SIZE, f);

            float boxCx = (minX + maxX) / 2f;
            float tx = boxCx - line1W / 2f;
            if (tx < 2f) tx = 2f;
            if (tx + line1W > scaledW - 2f) tx = scaledW - line1W - 2f;

            float topY = minY - cellH - 2f;
            if (topY < 2f) topY = 2f;

            float bottomY = maxY + 2f;
            if (bottomY + cellH > scaledH) bottomY = scaledH - cellH - 1f;
            float distX = boxCx - line2W / 2f;
            if (distX < 2f) distX = 2f;
            if (distX + line2W > scaledW - 2f) distX = scaledW - line2W - 2f;

            CustomFont.drawString(name, tx, topY, TEXT_COLOR, false, TEXT_SIZE, f);
            if (pingStr != null) {
                CustomFont.drawString(pingStr, tx + nameW + gap, topY, PING_COLOR, false, TEXT_SIZE, f);
            }
            CustomFont.drawString(dist, distX, bottomY, TEXT_COLOR, false, TEXT_SIZE, f);
        }
    }

    /** 12 рёбер бокса одним begin/end (жёлтые). */
    private static void drawBoxEdges(double[][] pts, int[][] corners, int col) {
        int[][] edges = {
            {0,1},{1,2},{2,3},{3,0},   // низ
            {4,5},{5,6},{6,7},{7,4},   // верх
            {0,4},{1,5},{2,6},{3,7}    // вертикали
        };
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4ub(
            (byte) ((col >> 16) & 0xFF), (byte) ((col >> 8) & 0xFF),
            (byte) (col & 0xFF), (byte) ((col >> 24) & 0xFF));
        GL11.glBegin(GL11.GL_LINES);
        for (int[] e : edges) {
            double[] a = pts[e[0]], b = pts[e[1]];
            if (a[0] == 0 && a[1] == 0) continue;
            if (b[0] == 0 && b[1] == 0) continue;
            GL11.glVertex2f((float) a[0], (float) a[1]);
            GL11.glVertex2f((float) b[0], (float) b[1]);
        }
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    public static double projectToScreen(double wx, double wy, double wz, int scaledW, int scaledH, float[] out) {
        double w = projectCorner(wx, wy, wz, scaledW, scaledH, out);
        lastProjectedW = w;
        return w;
    }

    /** RAW cw последней проекции (для near-clip проверок вызывающих). */
    public static double lastW() { return lastProjectedW; }

    private static double lastProjectedW;

    public static double camX() { return camX; }
    public static double camY() { return camY; }
    public static double camZ() { return camZ; }

    // ===== утилиты =====

    private static final float[] TMP = new float[2];
    private static boolean camReady;

    /**
     * Иконка предмета: ItemIcons (наш шейдер + их блок-атлас, uniform-UV).
     * Их RenderItem из overlay-фазы не рисует (батчер-секции + негатив-кэш
     * моделей в RenderItemController) — реверс 09-08.
     */
    private static void drawItemIcon(GameContext ctx, Object stack, float x, float y, float size) {
        utils.render.ItemIcons.drawItemIcon(ctx, stack, x, y, size);
    }


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

    /**
     * Живой пинг сущности: player → iliiililiI() → iiIilIliiI (снапшот из ОБЩЕЙ
     * карты сетевого хендлера по UUID) → illIillliI() (поле lIlllIIl, обновляется
     * пакетом UPDATE_LATENCY — Tab открывать не нужно). -1 = недоступен.
     */
    private static int entityPing(IIlIIliIiI e) {
        try {
            if (!netResolved) {
                netResolved = true;
                GameContext ctx = GameContext.get();
                absPlayerClass = ctx.gameLoader.loadClass("rustme.iiIililiiI");
                playerInfoM = absPlayerClass.getMethod("iliiililiI");
                latencyM = ctx.gameLoader.loadClass("rustme.iiIilIliiI").getMethod("illIillliI");
            }
            if (playerInfoM == null || latencyM == null) return -1;
            if (!absPlayerClass.isInstance(e)) return -1;
            Object info = playerInfoM.invoke(e);
            if (info == null) return -1;
            int v = (Integer) latencyM.invoke(info);
            return (v >= 0 && v <= 5000) ? v : -1;
        } catch (Throwable t) {
            if (!netFailedLogged) {
                netFailedLogged = true;
                Log.error("ESP", "ping chain failed", t);
            }
            return -1;
        }
    }

    private static String round3(double v) {
        long r = Math.round(v * 1000.0);
        return String.valueOf(r / 1000.0);
    }
}
