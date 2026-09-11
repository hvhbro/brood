package utils.render;

import utils.etc.Log;
import utils.etc.GameContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Calendar;

/**
 * Ватермарка rockstar-стиль (статичный «дефолтный айленд», без анимаций и
 * статусов): имя сервера слева (заголовок таба, fallback mapId/часы) + тёмный
 * остров (гладкая капсула radius 7) с акцентным градиентным слоем 69% ширины
 * (затухание вправо) + текст medium-шрифтом 7px.
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
    private static final float LABEL_GAP = 4f;         // зазор лейбл → остров (как у часов)

    // пинг-бары (их I_method_58a034b9)
    private static final int[] PING_THRESHOLDS = {450, 300, 150, 75}; // I_field_b4e
    private static final float BARS_GAP = 3f;          // отступ баров от острова
    private static final float BAR_W = 2f;             // их 2.0F
    private static final float BAR_STEP = 2.7f;        // их шаг 2.7F
    private static final float BAR_R = 0.8f;           // скругление бара (их SDF-шейдер)

    // ===== позиция (перетаскивание при открытом меню) =====
    private static float posX = -1f;  // -1 = по центру экрана
    private static float posY = 26f;

    /** Ширина ВСЕЙ группы (лейбл + остров + бары) при данной ширине экрана. */
    public static float getTotalW(int scaledWidth) {
        try {
            MsdfFont f = CustomFont.WM_FONT;
            float textW = CustomFont.getWidth("Brood", TEXT_SIZE, f);
            float leftW = CustomFont.getWidth(leftLabel(), TEXT_SIZE, f);
            float flowW = PAD_LEFT + textW + PAD_RIGHT;
            float barsW = (PING_THRESHOLDS.length - 1) * BAR_STEP + BAR_W;
            return leftW + LABEL_GAP + flowW + BARS_GAP + barsW;
        } catch (Throwable t) {
            return 100f;
        }
    }

    /** Левый край ВСЕЙ группы (лейбла). Авто: остров+бары по центру,
     *  лейбл слева с LABEL_GAP; драг задаёт posX = этот же левый край. */
    public static float getX(int scaledWidth) {
        if (posX >= 0f) return posX;
        try {
            float leftW = CustomFont.getWidth(leftLabel(), TEXT_SIZE, CustomFont.WM_FONT);
            float coreGroup = coreGroupW();
            return scaledWidth / 2f - coreGroup / 2f - LABEL_GAP - leftW;
        } catch (Throwable t) {
            return scaledWidth / 2f;
        }
    }

    /** Остров + пинг-бары (без лейбла) — центрируемая часть. */
    private static float coreGroupW() {
        MsdfFont f = CustomFont.WM_FONT;
        float textW = CustomFont.getWidth("Brood", TEXT_SIZE, f);
        float flowW = PAD_LEFT + textW + PAD_RIGHT;
        float barsW = (PING_THRESHOLDS.length - 1) * BAR_STEP + BAR_W;
        return flowW + BARS_GAP + barsW;
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
    }

    /** Текущий пинг игрока в ms, -1 если недоступен. */
    public static int resolvePing(GameContext ctx) {
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

    // ===== Имя сервера: ЗАГОЛОВОК ТАБА (юзер 09-08: 'RustMe - "имя сервера"').
    // Сервер шлёт S47-аналог rustme/iIllilIIiI; netHandler.lIiiillliI кладёт хедер
    // в Tab GUI: GuiIngame.liIiiiIliI() -> поле iIilliil (liIlliliiI), хедер =
    // поле iIIiIIil (компонент ilIIlilIiI), plain-text = lllIlliIIl() (дизасм 09-08).
    // GuiIngame у нас в руках (GameContext.ingameField/Owner) — читаем каждый кадр
    // без открытия таба. Fallback: mapId (технический wipe-id), затем часы.
    private static boolean hdrResolved;
    private static Field tabGuiField;   // liIIliliiI.iIilliil (Tab GUI)
    private static Field headerField;   // liIlliliiI.iIIiIIil (хедер)
    private static Method headerTextM;  // ilIIlilIiI.lllIlliIIl() — plain text

    /** Текст заголовка таба (как пришёл от сервера), null если не приходил. */
    private static String tabHeaderText() {
        try {
            GameContext ctx = GameContext.get();
            if (ctx.ingameField == null || ctx.ingameOwner == null) return null;
            if (!hdrResolved) {
                hdrResolved = true;
                ClassLoader cl = ctx.gameLoader;
                Class ingameC = ctx.ingameClass != null
                    ? ctx.ingameClass : cl.loadClass("rustme.liIIliliiI");
                tabGuiField = ingameC.getDeclaredField("iIilliil");
                tabGuiField.setAccessible(true);
                Class tabC = cl.loadClass("rustme.liIlliliiI");
                headerField = tabC.getDeclaredField("iIIiIIil");
                headerField.setAccessible(true);
                Class compC = cl.loadClass("rustme.ilIIlilIiI");
                headerTextM = compC.getMethod("lllIlliIIl");
            }
            Object ingame = ctx.ingameField.get(ctx.ingameOwner);
            if (ingame == null) return null;
            Object tab = tabGuiField.get(ingame);
            if (tab == null) return null;
            Object comp = headerField.get(tab);
            if (comp == null) return null;
            return (String) headerTextM.invoke(comp);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Имя сервера из заголовка таба. Хедер многострочный (живой дамп 09-08):
     *  строка 1: '§cRust§fMe §7» §aПесочница-1' (режим — нужен), строка 3:
     *  '§7Онлайн » §a90'. Парс: срезать коды §x → первая непустая строка →
     *  текст после '»' (U+00BB). null если хедер не приходил. */
    private static String serverName() {
        String hdr = tabHeaderText();
        if (hdr == null) return null;
        String s = stripColorCodes(hdr);
        String[] lines = s.split("\n");
        String first = null;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (!t.isEmpty()) { first = t; break; }
        }
        if (first == null) return null;
        String name = first;
        int gt = name.indexOf('»');
        if (gt >= 0) name = name.substring(gt + 1);
        name = name.replace("\"", "").trim();
        return name.isEmpty() ? first : name;
    }

    /** Срезает пары §+код (цвета/форматы ванили). */
    private static String stripColorCodes(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            out.append(c);
        }
        return out.toString();
    }

    /** Левый лейбл watermark: имя сервера → сырой mapId → часы (единый для render/getTotalW). */
    private static String leftLabel() {
        String name = serverName();
        if (name != null) return name;
        String id = currentMapId();
        return (id != null) ? id : clockText();
    }

    // ===== mapId (fallback для лейбла, пока не пришёл таб-хедер): статика
    // llIllIiliI.IilIiiIIl → lIlIIlIliI, mapId = llillIlll:String (геттер
    // liiIiiiIIl()). Заполняется сервером пакетом rust:misc:itmap.
    private static boolean mapIdResolved;
    private static Field mapDataStatic;   // llIllIiliI.IilIiiIIl
    private static Method mapIdGetter;    // lIlIIlIliI.liiIiiiIIl()
    private static Field mapIdField;      // lIlIIlIliI.llillIlll (fallback)

    /** Ленивая резолвка mapId-цепочки. */
    private static void resolveMapId(GameContext ctx) throws Exception {
        if (mapIdResolved) return;
        mapIdResolved = true;
        ClassLoader cl = ctx.gameLoader;
        Class mapDataC = cl.loadClass("rustme.llIllIiliI");
        mapDataStatic = mapDataC.getDeclaredField("IilIiiIIl");
        mapDataStatic.setAccessible(true);
        Class mapInputC = cl.loadClass("rustme.lIlIIlIliI");
        try {
            mapIdGetter = mapInputC.getMethod("liiIiiiIIl");
        } catch (Throwable ignore) {}
        mapIdField = mapInputC.getDeclaredField("llillIlll");
        mapIdField.setAccessible(true);
    }

    /** mapId из статики llIllIiliI.IilIiiIIl; null если пакет ещё не приходил. */
    private static String currentMapId() {
        try {
            Object mapData = mapDataStatic.get(null);
            if (mapData == null) return null;
            if (mapIdGetter != null) {
                try {
                    return (String) mapIdGetter.invoke(mapData);
                } catch (Throwable ignore) {}
            }
            return (String) mapIdField.get(mapData);
        } catch (Throwable t) {
            return null;
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
            String text = "Brood";
            float textW = CustomFont.getWidth(text, TEXT_SIZE, f);
            if (textW <= 0f) return 0f;

            // текст слева от острова: имя сервера (таб-хедер), fallback — сырой
            // mapId, если хедер ещё не приходил — часы (единый leftLabel())
            String left = leftLabel();
            float leftW = CustomFont.getWidth(left, TEXT_SIZE, f);

            float flowW = PAD_LEFT + textW + PAD_RIGHT;
            float barsW = (PING_THRESHOLDS.length - 1) * BAR_STEP + BAR_W;
            float coreGroup = flowW + BARS_GAP + barsW;   // остров + бары
            float totalW = leftW + LABEL_GAP + coreGroup;
            // posX>=0 (драг) = левый край лейбла; авто: остров+бары СТРОГО по
            // центру экрана, лейбл пристаёт слева с тем же зазором, что был у
            // часов (4px) — имя не расталкивает остров
            float islandX = posX >= 0f ? posX + leftW + LABEL_GAP
                                       : scaledWidth / 2f - coreGroup / 2f;
            float islandY = posY;
            float labelX = islandX - LABEL_GAP - leftW;

            // 0) имя сервера/часы слева от острова (белым, по центру высоты)
            float clockY = islandY + (ISLAND_H - CustomFont.cellHeight(TEXT_SIZE, f)) / 2f;
            CustomFont.drawString(left, labelX, clockY, TEXT_WHITE, false, TEXT_SIZE, f);

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
            return totalW;
        } catch (Throwable t) {
            Log.error("Watermark", "render failed", t);
            return 0f;
        }
    }
}
