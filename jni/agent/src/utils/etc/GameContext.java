package utils.etc;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Кэш игровых объектов: mc, gs, world, player.
 * Инициализируется один раз, обновляется каждый тик (world/player могут меняться).
 *
 * Все поля public — модули читают напрямую.
 */
public final class GameContext {
    private static GameContext instance;

    // --- cached (не меняются) ---
    public Object mc;                 // Minecraft instance
    public Object gs;                 // GameSettings instance
    public ClassLoader gameLoader;    // лоадер, который видит rustme.*

    // --- resolved (reflection objects) ---
    public Field worldField;          // mc -> World
    public Field playersField;        // World -> List<wrapper>
    public Field motionYField;        // Entity.IiiIIiliI:D
    public Field motionXField;        // Entity.iIIlIiliI:D
    public Field motionZField;        // Entity.IIiIIiliI:D
    public Field sprintField;         // Entity.IilIiIliI:Z
    public Field moveForwardField;    // Input.IiIiIIiII:F
    public Field moveStrafeField;     // Input.iIIiIIiII:F
    public Field movementInputField;  // wrapper.iiliiIiII -> input holder

    public Method setSprint;          // EntityPlayer.lIllilllII(Z)V
    public Method getAttrInstanceMethod; // EntityPlayer.IiIlIiilII(lIliiliIiI)
    public Method isSneaking;         // EntityPlayer.iiiilIlIII()Z
    public Method getSpeed;           // EntityPlayer.iIIIIiiilI()F
    public Object movementSpeedAttr;  // SharedMonsterAttributes.movementSpeed instance
    public Method attrGetValue;       // illiiliIiI.iIIiIlIIII()D
    public Method attrSetBase;        // illiiliIiI.iIliIlIIII(D)V

    // --- мод-настройки (holder/Settings, для FullBright и т.д.) ---
    public Object settingsHolder;      // rustme.llIIiIiIiI (gs.IiIIllil)
    public Object settingsObj;         // ru.rustme.settings.Settings

    // --- sprint key (модовая система клавиш, см. resolveSprintKey) ---
    public Object keyBindingsCategory; // ru.rustme.settings.data.KeyBindingsCategory
    public Method getSprintNode;       // KeyBindingsCategory.getKeySprint() → KeyBindNode
    public Method nodeGetValue;        // OptionNode.getValue() → KeyBinding
    public Class keyBindingClass;      // rustme.IilIiIiIiI (KeyBinding)
    public Field kbPressedField;       // KeyBinding.iilIIiIiI:Z — клавиша зажата

    // --- HUD / главный поток (см. resolveRenderHooks) ---
    public Class eventDispatcherClass; // rustme.lIliliiIiI (диспетчер событий мода)
    public Class eventBaseClass;       // rustme.lilliiiliI (база событий)
    public Class renderEventClass;     // rustme.liIiIiiliI (рендер-событие, Consumer<Object>)
    public Class hudListenerClass;     // наш класс-слушатель (implements java.util.function.Consumer)
    public Class scaledResolutionClass;// rustme.liIIiIliiI (ScaledResolution)
    public Class fontRendererClass;    // rustme.lilililiiI (FontRenderer)
    public Class guiClass;             // rustme.iIlililiiI (Gui: drawRect)
    public Class glfwHelperClass;      // rustme.lllIiilliI (статические клавиши: iiililIIIl(I)Z)
    public Method hudSubscribe;        // lIllIilliI.iIlIIIlIIl(Class, Consumer)V
    public Method guiDrawRect;         // iIlililiiI.lIilIliliI(IIIII)V — static drawRect(left,top,right,bottom,color)
    public Method keyDown;             // lllIiilliI.iiililIIIl(I)Z
    public Method fontDrawString;      // lilililiiI.lIlIIliliI(String,FFIZ)I
    public Method fontGetStringWidth;  // lilililiiI.IIIIIliliI(String)I
    public Field hudFontField;         // GuiScreen.IIIlllil (FontRenderer) — берём как instance
    public Object fontRenderer;        // FontRenderer instance (снимаем с экрана/GuiIngame)
    private long lastHudScanLog;
    public Class ingameClass;          // rustme.liIIliliiI (GuiIngame)
    public Field ingameField;          // поле, где живёт инстанс GuiIngame
    public Object ingameOwner;         // объект-владелец поля (gs или mc)
    public boolean hudInstalled;
    public float guiScale = -1f;
    public int fbHeight = -1;
    public int scaledWidth = 480;      // из ScaledResolution события (обновляется)
    public int scaledHeight = 320;

    // --- live (обновляются каждый тик) ---
    public Object world;              // текущий World instance
    public Object player;             // текущий EntityPlayerSP instance
    public Object movementInput;      // текущий input holder instance
    public boolean sneaking;
    public boolean inWorld;

    // --- классы (для загрузки новых) ---
    public Class mcClass;
    public Class worldClass;
    public Class playerClass;         // liIlIliIiI (EntityPlayer base)
    public Class localSpClass;        // liIililiiI (EntityPlayerSP)
    public Class inputClass;          // liIIIiIIiI (movement input)
    public Class wrapperClass;        // IIiIIiIIiI (wrapper base)

    private GameContext() {}

    public static GameContext get() {
        if (instance == null) instance = new GameContext();
        return instance;
    }

    /**
     * Полная инициализация: дляName с fallback на скан лоадеров.
     * Возвращает false если не нашёл базовые объекты (меню).
     */
    public boolean init() {
        if (initialized()) return true;

        Class mcC = null, worldC = null, spC = null, gsC = null;
        try {
            mcC = Class.forName("rustme.lIliIiiIiI");
            worldC = Class.forName("rustme.IIlllIlIiI");
            spC = Class.forName("rustme.liIililiiI");
            gsC = Class.forName("rustme.iilliIliiI");
            gameLoader = GameContext.class.getClassLoader();
        } catch (Throwable t) {
            Log.info("Ctx", "forName fail, scanning loaders: " + t);
            for (Thread th : Thread.getAllStackTraces().keySet()) {
                ClassLoader cl = th.getContextClassLoader();
                if (cl == null) continue;
                try {
                    if (cl.loadClass("rustme.lIliIiiIiI") != null) {
                        gameLoader = cl;
                        mcC = cl.loadClass("rustme.lIliIiiIiI");
                        worldC = cl.loadClass("rustme.IIlllIlIiI");
                        spC = cl.loadClass("rustme.liIililiiI");
                        gsC = cl.loadClass("rustme.iilliIliiI");
                        break;
                    }
                } catch (Throwable ignore) {}
            }
            if (mcC == null) {
                Log.info("Ctx", "no loader sees rustme.*");
                return false;
            }
        }

        mcClass = mcC;
        worldClass = worldC;
        localSpClass = spC;
        // playerClass = EntityPlayer base (liIlIliIiI) — резолвим ниже, после loader'а

        try {
            // GameSettings синглтон
            Class gsC_final = gsC;
            Method gsGet = gsC_final.getMethod("IllilIIiIl");
            gs = gsGet.invoke(null);

            // mc: не-static поле gs со значением instanceof Minecraft
            for (Field f : gsC_final.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(gs);
                    if (v != null && mcC.isInstance(v)) { mc = v; break; }
                } catch (Throwable ignore) {}
            }
            if (mc == null) {
                Log.info("Ctx", "mc not found in gs fields");
                return false;
            }

            // world: поле mc типа World
            for (Field f : mcC.getDeclaredFields()) {
                if (f.getType() == worldC) { f.setAccessible(true); worldField = f; break; }
            }
            if (worldField == null) {
                Log.info("Ctx", "world field not found");
                return false;
            }

            // wrapper
            wrapperClass = gameLoader.loadClass("rustme.IIiIIiIIiI");

            // playerEntities: List-поле World с wrapper-элементами
            for (Field f : worldC.getDeclaredFields()) {
                if (f.getType() != java.util.List.class) continue;
                try {
                    f.setAccessible(true);
                    Object w = worldField.get(mc);
                    if (w == null) continue;
                    java.util.List<?> lst = (java.util.List<?>) f.get(w);
                    if (lst == null || lst.isEmpty()) continue;
                    Object el = null;
                    for (Object o : lst) { if (o != null) { el = o; break; } }
                    if (el != null && wrapperClass.isInstance(el)) { playersField = f; break; }
                } catch (Throwable ignore) {}
            }
            if (playersField == null) {
                Log.info("Ctx", "playersField not found");
                return false;
            }

            // movement input holder: поле wrapper'а типа InputClass
            inputClass = gameLoader.loadClass("rustme.liIIIiIIiI");
            for (Field f : wrapperClass.getDeclaredFields()) {
                if (f.getType() == inputClass) { f.setAccessible(true); movementInputField = f; break; }
            }

            // EntityPlayer base class (liIlIliIiI) — родитель wrapper'а
            playerClass = gameLoader.loadClass("rustme.liIlIliIiI");

            // Entity методы (объявлены в EntityPlayer base)
            setSprint = playerClass.getMethod("lIllilllII", Boolean.TYPE);
            isSneaking = playerClass.getMethod("iiiilIlIII");
            getSpeed = playerClass.getMethod("iIIIIiiilI");
            getAttrInstanceMethod = playerClass.getMethod("IiIlIiilII", gameLoader.loadClass("rustme.lIliiliIiI"));

            // sprint flag: поле IilIiIliI:Z по иерархии от EntityPlayer вверх
            Class c = playerClass;
            while (c != null) {
                try {
                    sprintField = c.getDeclaredField("IilIiIliI");
                    sprintField.setAccessible(true);
                    break;
                } catch (Throwable ignore) {}
                c = c.getSuperclass();
            }

            // motion поля: Entity root IIlIIliIiI
            Class entityRoot = gameLoader.loadClass("rustme.IIlIIliIiI");
            motionXField = findField(entityRoot, "iIIlIiliI");
            motionYField = findField(entityRoot, "IiiIIiliI");
            motionZField = findField(entityRoot, "IIiIIiliI");

            // moveForward/moveStrafe в inputClass
            moveForwardField = inputClass.getField("IiIiIIiII");
            moveStrafeField = inputClass.getField("iIIiIIiII");

            // movementSpeed attribute instance
            Class smaClass = gameLoader.loadClass("rustme.IlilIiIIiI");
            Object smaSpeed = null;
            // lllilIiII — movementSpeed из дампа
            for (Field f : smaClass.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().getName().equals("rustme.lIliiliIiI")) continue;
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) { smaSpeed = v; break; }
            }
            if (smaSpeed != null) {
                movementSpeedAttr = smaSpeed;
                // IiIlIiilII уже зарезолвен выше через playerClass
                // здесь только attrGetValue/attrSetBase
                Log.info("Ctx", "movementSpeed attr set: " + (smaSpeed != null));
            }

            // спринт-клавиша мода (не критично, при неудаче AutoSprint повторит)
            resolveSprintKey();

            // рендер-хуки: диспетчер событий мода + Consumer-подписка + клавиши (не критично)
            resolveRenderHooks();

            Log.info("Ctx", "init OK: mc/world/players/motion/sprint/input готовы");
            return true;

        } catch (Throwable t) {
            Log.error("Ctx", "init exception", t);
            return false;
        }
    }

    /**
     * Обновляет live объекты (world, player, movementInput).
     * Вызывать каждый тик. Возвращает false если не в мире.
     */
    public boolean update() {
        try {
            world = worldField.get(mc);
            if (world == null) { inWorld = false; return false; }

            player = null;
            java.util.List<?> lst = (java.util.List<?>) playersField.get(world);
            if (lst != null) {
                for (Object o : lst) {
                    if (o == null) continue;
                    if (localSpClass.isInstance(o)) { player = o; break; }
                    if (player == null) player = o;
                }
            }
            if (player == null) { inWorld = false; return false; }

            if (movementInputField != null) {
                movementInput = movementInputField.get(player);
            }
            sneaking = (Boolean) isSneaking.invoke(player);
            inWorld = true;
            return true;
        } catch (Throwable t) {
            inWorld = false;
            return false;
        }
    }

    public boolean initialized() {
        return mc != null && worldField != null && playersField != null;
    }

    private long lastSprintResolveAttempt;

    /** Спринт-клавиша мода.
     * Цепочка из дампа (onLivingUpdate liIililiiI):
     *   gs.IiIIllil (Object → llIIiIiIiI) → .lliliiIiI (Settings, публичное поле)
     *   → Settings.getData() → MainSettingsCategory
     *   → getKeybindingSettings() → KeyBindingsCategory
     *   → getKeySprint() → KeyBindNode
     *   → OptionNode.getValue() → KeyBinding (rustme.IilIiIiIiI)
     *   → pressed = поле iilIIiIiI:Z (isKeyDown = lIIIiIiIII()Z читает его)
     *   → setSprinting вызывается игрой в тике как llllIiiilI(Z).
     * При неудаче повторять не чаще раза в 2с (вызывается и из тика).
     */
    public boolean resolveSprintKey() {
        if (kbPressedField != null) return true;
        long now = System.currentTimeMillis();
        if (now - lastSprintResolveAttempt < 2000L) return false;
        lastSprintResolveAttempt = now;
        try {
            Class gsC = gs.getClass();
            Field holderF = gsC.getField("IiIIllil");           // public Object IiIIllil
            holderF.setAccessible(true);
            Object holder = holderF.get(gs);                    // rustme.llIIiIiIiI
            if (holder == null) { Log.info("Ctx", "sprintKey: holder null"); return false; }
            settingsHolder = holder;

            Class holderC = holder.getClass();
            Field settingsF = holderC.getField("lliliiIiI");    // public Settings lliliiIiI
            settingsF.setAccessible(true);
            Object settings = settingsF.get(holder);            // ru.rustme.settings.Settings
            if (settings == null) { Log.info("Ctx", "sprintKey: settings null"); return false; }
            settingsObj = settings;

            Method getData = settings.getClass().getMethod("getData");
            Object mainCat = getData.invoke(settings);          // MainSettingsCategory
            if (mainCat == null) { Log.info("Ctx", "sprintKey: mainCat null"); return false; }

            Method getKb = mainCat.getClass().getMethod("getKeybindingSettings");
            keyBindingsCategory = getKb.invoke(mainCat);        // KeyBindingsCategory
            if (keyBindingsCategory == null) { Log.info("Ctx", "sprintKey: kbCat null"); return false; }

            getSprintNode = keyBindingsCategory.getClass().getMethod("getKeySprint");

            // KeyBindNode.getValue() (OptionNode) → KeyBinding
            Class nodeC = gameLoader.loadClass("ru.rustme.settings.OptionNode");
            nodeGetValue = nodeC.getMethod("getValue");

            keyBindingClass = gameLoader.loadClass("rustme.IilIiIiIiI");
            // поле pressed по иерархии от KeyBinding вверх
            Class c = keyBindingClass;
            while (c != null) {
                try {
                    kbPressedField = c.getDeclaredField("iilIIiIiI");
                    kbPressedField.setAccessible(true);
                    break;
                } catch (Throwable ignore) {}
                c = c.getSuperclass();
            }
            if (kbPressedField == null) { Log.info("Ctx", "sprintKey: pressed field not found"); return false; }

            Log.info("Ctx", "sprintKey resolved: node=" + (getSprintNode != null)
                + ", getValue=" + (nodeGetValue != null));
            return true;
        } catch (Throwable t) {
            Log.error("Ctx", "sprintKey resolve failed", t);
            return false;
        }
    }


    /** Клавиша зажата через glfwGetKey (хелпер мода lllIiilliI.iiililIIIl(I)Z). */
    public boolean isKeyDown(int glfwKey) {
        try {
            if (keyDown == null) return false;
            return (Boolean) keyDown.invoke(null, glfwKey);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Резолв рендер-хуков:
     *  - диспетчер событий мода lIliliiIiI + статическая подписка lIllIilliI.iIlIIIlIIl(Class, Consumer)V
     *    (слушатель = java.util.function.Consumer, вызывается на главном потоке в renderGameOverlay);
     *  - рендер-событие liIiIiiliI (аргумент Consumer.accept = event, поле iiIIIilil = ScaledResolution);
     *  - Gui.drawRect (iIlililiiI.lllIIliliI(IIII)V) + FontRenderer (lilililiiI);
     *  - клавиши: lllIiilliI.iiililIIIl(I)Z (glfwGetKey от хендла окна мода).
     */
    public boolean resolveRenderHooks() {
        try {
            eventDispatcherClass = gameLoader.loadClass("rustme.lIliliiIiI");
            eventBaseClass = gameLoader.loadClass("rustme.lilliiiliI");
            renderEventClass = gameLoader.loadClass("rustme.liIiIiiliI");
            scaledResolutionClass = gameLoader.loadClass("rustme.liIIiIliiI");
            fontRendererClass = gameLoader.loadClass("rustme.lilililiiI");
            guiClass = gameLoader.loadClass("rustme.iIlililiiI");
            glfwHelperClass = gameLoader.loadClass("rustme.lllIiilliI");

            // подписка: статический (Class, Consumer) в классе lIllIilliI
            Class busHelper = gameLoader.loadClass("rustme.lIllIilliI");
            hudSubscribe = busHelper.getMethod("iIlIIIlIIl", Class.class, java.util.function.Consumer.class);

            // клавиши
            keyDown = glfwHelperClass.getMethod("iiililIIIl", Integer.TYPE);

            // Gui.drawRect
            guiDrawRect = guiClass.getMethod("lIilIliliI",
                Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE);

            // FontRenderer: drawString(String, x, y, color, shadow) + getStringWidth
            fontDrawString = fontRendererClass.getMethod("lIlIIliliI",
                String.class, Float.TYPE, Float.TYPE, Integer.TYPE, Boolean.TYPE);
            fontGetStringWidth = fontRendererClass.getMethod("IIIIIliliI", String.class);

            Log.info("Ctx", "renderHooks resolved: bus/keys/font/drawRect OK");
            return true;
        } catch (Throwable t) {
            Log.error("Ctx", "renderHooks resolve failed", t);
            return false;
        }
    }

    /**
     * HUD через подмену GuiIngame: находим поле с инстансом rustme.liIIliliiI
     * (GuiIngame), подменяем на CheatIngame (наш subclass в пакете rustme).
     * Игра сама зовёт его iliIiiIliI(partialTicks) на главном потоке каждый кадр —
     * рисуем в правильной фазе БЕЗ шин, событий и подписок.
     * Идемпотентно.
     */
    public boolean ensureHud() {
        try {
            if (ingameClass == null) {
                ingameClass = gameLoader.loadClass("rustme.liIIliliiI");
                Log.info("HUD", "ingameClass loaded");
            }
            if (ingameField == null && !findIngameField()) {
                long now = System.currentTimeMillis();
                if (now - lastHudScanLog >= 10000L) {
                    lastHudScanLog = now;
                    Log.info("HUD", "no GuiIngame field found yet (gs/mc scanned)");
                }
                return false;
            }
            Object cur = ingameField.get(ingameOwner);
            if (cur == null) {
                long now = System.currentTimeMillis();
                if (now - lastHudScanLog >= 10000L) {
                    lastHudScanLog = now;
                    Log.info("HUD", "field " + ingameField.getName() + " is null, waiting");
                }
                return false;
            }
            if ("rustme.CheatIngame".equals(cur.getClass().getName())) {
                if (!hudInstalled) {
                    hudInstalled = true;
                    Log.info("HUD", "swap confirmed in place");
                }
                return true;
            }
            if (gs == null) return false;
            Log.info("HUD", "swapping field " + ingameField.getName()
                + " owner=" + ingameOwner.getClass().getName()
                + " cur=" + cur.getClass().getName());
            Object ours = new rustme.CheatIngame((rustme.iilliIliiI) gs);
            ingameField.set(ingameOwner, ours);
            // проверить, что записалось
            Object check = ingameField.get(ingameOwner);
            hudInstalled = check == ours;
            Log.info("HUD", "swap done, verified=" + hudInstalled
                + " (now=" + (check == null ? "null" : check.getClass().getName()) + ")");
            return hudInstalled;
        } catch (Throwable t) {
            Log.error("HUD", "install failed", t);
            return false;
        }
    }

    /** Ищет поле с GuiIngame-инстансом: gs, mc, holder мода llIIiIiIiI; лог каждого найденного. */
    private boolean findIngameField() {
        try {
            int found = 0;
            found += scanOwnerForIngame("gs", gs);
            found += scanOwnerForIngame("mc", mc);
            // holder мода: gs.IiIIllil (llIIiIiIiI) — если ещё не резолвлен
            if (keyBindingsCategory != null || gs != null) {
                try {
                    Field hf = gs.getClass().getField("IiIIllil");
                    hf.setAccessible(true);
                    found += scanOwnerForIngame("holder(gs.IiIIllil)", hf.get(gs));
                } catch (Throwable ignore) {}
            }
            return found > 0;
        } catch (Throwable t) {
            Log.error("HUD", "findIngameField failed", t);
            return false;
        }
    }

    /** Скан всех нестатических полей owner'а на предмет GuiIngame. Возвращает число найденных полей. */
    private int scanOwnerForIngame(String tag, Object owner) {
        if (owner == null || ingameClass == null) return 0;
        int found = 0;
        try {
            for (Field f : owner.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (v != null && ingameClass.isInstance(v)) {
                        found++;
                        Log.info("HUD", "FOUND GuiIngame: " + tag + "." + f.getName()
                            + " (" + v.getClass().getName() + ")");
                        if (ingameField == null) {
                            ingameField = f;
                            ingameOwner = owner;
                        }
                    }
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        return found;
    }


    // ===== Статические GL-обёртки для FullBright (через lwjglx GL11 reflection) =====
    public static Method gl11BindTexture;
    public static Method gl11TexSubImage2D;
    private static boolean gl11Resolved;

    public static void glBindTexture(int target, int texId) {
        try {
            if (!gl11Resolved) {
                Class gl11 = Class.forName("org.lwjglx.opengl.GL11");
                gl11BindTexture = gl11.getMethod("glBindTexture", Integer.TYPE, Integer.TYPE);
                gl11TexSubImage2D = gl11.getMethod("glTexSubImage2D", Integer.TYPE, Integer.TYPE,
                    Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE,
                    Integer.TYPE, Integer.TYPE, ByteBuffer.class);
                gl11Resolved = true;
            }
            gl11BindTexture.invoke(null, target, texId);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void glTexSubImage2D(int target, int level, int x, int y, int w, int h,
                                       int format, int type, ByteBuffer pixels) {
        try {
            if (!gl11Resolved) {
                Class gl11 = Class.forName("org.lwjglx.opengl.GL11");
                gl11BindTexture = gl11.getMethod("glBindTexture", Integer.TYPE, Integer.TYPE);
                gl11TexSubImage2D = gl11.getMethod("glTexSubImage2D", Integer.TYPE, Integer.TYPE,
                    Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE,
                    Integer.TYPE, Integer.TYPE, ByteBuffer.class);
                gl11Resolved = true;
            }
            gl11TexSubImage2D.invoke(null, target, level, x, y, w, h, format, type, pixels);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Устанавливает FontRenderer (вызывается из CheatIngame наследуемым геттером). */
    public void setFontRenderer(Object font) {
        if (font != null && fontRenderer == null) {
            fontRenderer = font;
            Log.info("HUD", "fontRenderer resolved via GuiIngame getter");
        }
    }

    /** Достаёт FontRenderer instance из gs-полей (мод держит его в Object-поле). */
    public Object resolveFontRendererInstance() {
        if (fontRenderer != null) return fontRenderer;
        if (fontRendererClass == null || gs == null) return null;
        for (Field f : gs.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(gs);
                if (v != null && fontRendererClass.isInstance(v)) {
                    fontRenderer = v;
                    return v;
                }
            } catch (Throwable ignore) {}
        }
        return null;
    }


    /** Для CheatIngame (subclass): доступ к gs без reflection. */
    public Object getGsForRender() { return gs; }

    /** Рисует прямоугольник через Gui.drawRect (только с главного потока!). */
    public void drawRect(int x1, int y1, int x2, int y2, int color) {
        try {
            if (guiDrawRect != null) guiDrawRect.invoke(null, x1, y1, x2, y2, color);
        } catch (Throwable ignore) {}
    }

    /** Рисует строку (только с главного потока!). */
    public void drawString(String s, float x, float y, int color, boolean shadow) {
        try {
            if (fontRenderer != null && fontDrawString != null) {
                fontDrawString.invoke(fontRenderer, s, x, y, color, shadow);
            }
        } catch (Throwable ignore) {}
    }

    public int getStringWidth(String s) {
        try {
            if (fontRenderer != null && fontGetStringWidth != null) {
                return (Integer) fontGetStringWidth.invoke(fontRenderer, s);
            }
        } catch (Throwable ignore) {}
        return s.length() * 6;
    }

    private Field findField(Class start, String name) {
        Class c = start;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignore) {}
            c = c.getSuperclass();
        }
        return null;
    }
}
