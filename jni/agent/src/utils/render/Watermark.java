package utils.render;

import utils.etc.Log;
import utils.etc.GameContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Calendar;

/**
 * Ватермарка rockstar-стиль (статичный «дефолтный айленд», без анимаций и
 * статусов): часы слева + тёмный остров (гладкая капсула radius 7) с
 * акцентным градиентным слоем 69% ширины (затухание вправо) + текст
 * medium-шрифтом 7px.
 *
 * Константы 1:1 из rockstar-client-src:
 *  - позиция: x = центр экрана (у них y=7, у нас 26 — «пониже»)
 *  - айленд: h=15, radius=7 (IiIiiIiii_Class184.size(48,15,7); 2+5*anim —
 *    переходное состояние, устоявшееся = 7)
 *  - фон: palette[1] = rgba(24,21,29,216.75)
 *  - внешняя подсветка: +1px наружу, белый alpha 25.5
 *  - паддинги потока: left=4, right=5 (IIII.12c9a0cb(0,5,0,4))
 *  - градиент-слой: w*0.69, акцент #906BFF alpha 71.4 → 0, горизонтальный
 *    fade вправо (порядок вершин I_method_43fbeb57)
 *  - часы: 12-часовой формат без ведущего нуля, h:mm (как на их скрине «1:17»)
 */
public final class Watermark {

    private static final float TEXT_SIZE = 7f;

    // палитра темы (IiiiiIIIi_Class242)
    private static final int ACCENT = 0xFF906BFF;      // 144,107,255 — акцент темы
    private static final int ISLAND_BG = 0xDA18151D;   // 24,21,29 @ 216.75/255
    private static final int OUTER_GLOW = 0x1AFFFFFF;  // белый @ 25.5/255
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int CLOCK_GRAY = 0xFFB7B3BD;  // приглушённый серый часов

    private static final float ISLAND_H = 15f;
    private static final float ISLAND_R = 7f;          // гладкая капсула
    private static final float PAD_LEFT = 4f;
    private static final float PAD_RIGHT = 5f;
    private static final float TOP_Y = 26f;            // «чуть пониже»
    private static final float CLOCK_GAP = 12f;        // зазор часы → остров

    // пинг-бары (их I_method_58a034b9)
    private static final int[] PING_THRESHOLDS = {450, 300, 150, 75}; // I_field_b4e
    private static final float BARS_GAP = 3f;          // отступ баров от острова
    private static final float BAR_W = 2f;             // их 2.0F
    private static final float BAR_STEP = 2.7f;        // их шаг 2.7F
    private static final float BAR_R = 0.8f;           // скругление бара (их SDF-шейдер)

    private static boolean logged;

    // ===== позиция (перетаскивание при открытом меню) =====
    private static float posX = -1f;  // -1 = по центру экрана
    private static float posY = 26f;

    /** Ширина всего элемента (часы + остров + бары) при данной ширине экрана. */
    public static float getTotalW(int scaledWidth) {
        try {
            MsdfFont f = CustomFont.WM_FONT;
            float textW = CustomFont.getWidth("ваня стирается", TEXT_SIZE, f);
            float clockW = CustomFont.getWidth(clockText(), TEXT_SIZE, f);
            float flowW = PAD_LEFT + textW + PAD_RIGHT;
            float barsW = (PING_THRESHOLDS.length - 1) * BAR_STEP + BAR_W;
            return clockW + CLOCK_GAP + flowW + BARS_GAP + barsW;
        } catch (Throwable t) {
            return 100f;
        }
    }

    /** Левый верх всего элемента (x часового блока). */
    public static float getX(int scaledWidth) {
        if (posX >= 0f) return posX;
        return scaledWidth / 2f - getTotalW(scaledWidth) / 2f;
    }

    public static float getY() { return posY; }

    public static void setPos(float x, float y) {
        posX = Math.max(0f, x);
        posY = Math.max(0f, y);
    }

    /** Hit-рект для драга: out = {x,y,w,h}. */
    public static void getRect(int scaledWidth, float[] out) {
        out[0] = getX(scaledWidth);
        out[1] = posY;
        out[2] = getTotalW(scaledWidth);
        out[3] = ISLAND_H + 2f;
    }

    /** Лог пинга раз в 5 секунд (диагностика цепочки). */
    private static long lastPingLog;

    private static void logPingOnce5s(int ping) {
        long now = System.currentTimeMillis();
        if (now - lastPingLog >= 5000L) {
            lastPingLog = now;
            Log.info("Watermark", "ping=" + (ping < 0 ? "unavailable" : ping + " ms"));
        }
    }

    // ===== пинг через КЛАССЫ ФОРКА (ванильных классов нет). Путь 1:1 как у
    // ванильного GuiPlayerTabOverlay:
    //   player (liIililiiI → iiIililiiI = AbstractClientPlayer) хранит playerInfo:
    //   iiIililiiI.IliIiiil : iiIilIliiI (NetworkPlayerInfo), геттер iliiililiI()
    //   iiIilIliiI.liIIillliI() → int пинг (поле IIlllIIl, обновляется TAB'ом)
    // =====
    private static boolean netResolved;
    private static Method playerInfoM;   // iiIililiiI.iliiililiI() → iiIilIliiI
    private static Method latencyM;      // iiIilIliiI.liIIillliI() → int

    private Watermark() {}

    /** Один раз привязывает player → playerInfo → latency. */
    private static void discoverNet(GameContext ctx) throws Exception {
        ClassLoader cl = ctx.gameLoader;
        Class absClass = cl.loadClass("rustme.iiIililiiI");   // AbstractClientPlayer
        Class entryClass = cl.loadClass("rustme.iiIilIliiI"); // NetworkPlayerInfo
        playerInfoM = absClass.getMethod("iliiililiI");
        // illIillliI() читает поле lIlllIIl — туда ctor снапшота копирует ЖИВОЙ
        // пинг из lIllilIIiI.iIliIliIlI(). liIIillliI() (IIlllIIl) обновляется
        // только при открытом Tab — даёт вечные нули.
        latencyM = entryClass.getMethod("illIillliI");
        Log.info("Watermark", "net chain bound: iliiililiI/liIIillliI");
    }

    /** Текущий пинг игрока в ms, -1 если недоступен. */
    private static int resolvePing(GameContext ctx) {
        try {
            if (!ctx.inWorld || ctx.player == null) return -1;
            if (!netResolved) {
                netResolved = true;
                discoverNet(ctx);
            }
            if (playerInfoM == null || latencyM == null) return -1;
            Object info = playerInfoM.invoke(ctx.player);
            if (info == null) return -1;
            int v = (Integer) latencyM.invoke(info);
            return (v >= 0 && v <= 5000) ? v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** HH:mm, 24-часовой формат с ведущим нулём (их SimpleDateFormat("HH:mm")). */
    private static String clockText() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        int m = c.get(Calendar.MINUTE);
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    /** Рисует ватермарку сверху по центру. Возвращает ширину (scaled). */
    public static float render(int scaledWidth) {
        try {
            MsdfFont f = CustomFont.WM_FONT;
            String text = "ваня стирается";
            float textW = CustomFont.getWidth(text, TEXT_SIZE, f);
            if (textW <= 0f) return 0f;

            String clock = clockText();
            float clockW = CustomFont.getWidth(clock, TEXT_SIZE, f);

            float flowW = PAD_LEFT + textW + PAD_RIGHT;
            float barsW = (PING_THRESHOLDS.length - 1) * BAR_STEP + BAR_W;
            float totalW = clockW + CLOCK_GAP + flowW + BARS_GAP + barsW;
            float baseX = posX >= 0f ? posX : scaledWidth / 2f - totalW / 2f;
            float islandX = baseX + clockW + CLOCK_GAP;
            float islandY = posY;

            // 0) часы слева от острова (белым, вертикально по центру высоты)
            float clockY = islandY + (ISLAND_H - CustomFont.cellHeight(TEXT_SIZE, f)) / 2f;
            CustomFont.drawString(clock, islandX - clockW - 4f, clockY, TEXT_WHITE, false, TEXT_SIZE, f);

            // 1) внешняя подсветка (+1px наружу, белый @10%)
            RenderUtil.drawRoundedRectShader(GameContext.get(), islandX - 1f, islandY - 1f,
                flowW + 2f, ISLAND_H + 2f, ISLAND_R, OUTER_GLOW, OUTER_GLOW, OUTER_GLOW, OUTER_GLOW);

            // 2) остров: тёмная гладкая капсула
            RenderUtil.drawRoundedRectShader(GameContext.get(), islandX, islandY,
                flowW, ISLAND_H, ISLAND_R, ISLAND_BG, ISLAND_BG, ISLAND_BG, ISLAND_BG);

            // 3) акцентный градиент-слой: 69% ширины, radius капсулы,
            // горизонтальное затухание вправо (TL=BL=accent@28%, TR=BR=0)
            int a = 71; // 71.4/255
            int accentTop = (a << 24) | (ACCENT & 0xFFFFFF);
            RenderUtil.drawRoundedRectShader(GameContext.get(), islandX, islandY,
                flowW * 0.69f, ISLAND_H, ISLAND_R, accentTop, 0, accentTop, 0);

            // 4) текст (вертикально по центру острова)
            float textY = islandY + (ISLAND_H - CustomFont.cellHeight(TEXT_SIZE, f)) / 2f;
            CustomFont.drawString(text, islandX + PAD_LEFT, textY, TEXT_WHITE, false, TEXT_SIZE, f);

            // 5) пинг-бары справа от острова (их I_method_58a034b9: скруглённые
            //    квада w=2, шаг 2.7, высоты 3..6, низ на y+12; зажжён = ping <
            //    порога — белый, незажжён — тот же белый @20%)
            GameContext ctx = GameContext.get();
            if (ctx.inWorld) {
                int ping = resolvePing(ctx);
                logPingOnce5s(ping);
                float barsBottom = islandY + 12f;
                for (int i = 0; i < PING_THRESHOLDS.length; i++) {
                    float bh = 3f + i;
                    float by = barsBottom - bh;
                    // их логика alpha: var13 = ping < порога ? полная : 20%-я (var9=0.2*var5)
                    boolean lit = ping >= 0 && ping < PING_THRESHOLDS[i];
                    int alpha = (int) ((lit ? 1f : 0.2f) * 255f);
                    int color = (alpha << 24) | 0xFFFFFF;
                    RenderUtil.drawRoundedRectShader(ctx,
                        islandX + flowW + BARS_GAP + i * BAR_STEP, by,
                        BAR_W, bh, BAR_R, color, color, color, color);
                }
            }

            if (!logged) {
                logged = true;
                Log.info("Watermark", "drawn: island(" + Math.round(islandX) + "," + islandY
                    + ")+[" + Math.round(flowW) + "x" + (int) ISLAND_H + "] textW=" + textW
                    + " clock=" + clock);
            }
            return totalW;
        } catch (Throwable t) {
            Log.error("Watermark", "render failed", t);
            return 0f;
        }
    }
}
