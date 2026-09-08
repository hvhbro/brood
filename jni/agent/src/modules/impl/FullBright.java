package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * FullBright: выставляет гамму мода (настройки → Экран → Гамма) в экстремальное значение.
 *
 * У мода есть свой слайдер гаммы (ScreenSettingsCategory.getGamma → FloatNode),
 * и GameRenderer.IiIIllIiII(F) (updateLightmap форка) читает его КАЖДЫЙ тик.
 * Мы через reflection ставим gamma.storedValue = 100.0f (setValue пишет storedValue
 * при tempState=false БЕЗ клампа validateValue) — мод сам делает мир светлым.
 * Никакого мигания: значение хранится в настройках, а не перезаписывается.
 *
 * Вызов — с главного потока агента (tick event).
 */
public final class FullBright extends Module {
    public static final int TOGGLE_KEY = 74; // GLFW_KEY_J

    private boolean keyWasDown;

    private static boolean resolved;
    private static Object gammaNode;        // ru.rustme.settings.FloatNode
    private static Method nodeSetValue;     // OptionNode.setValue(Object)V
    private static Object gammaDefault;     // прежнее значение (для восстановления)

    // 100.0f ПЕРЕПОЛНЯЕТ расчёт lightmap (формула гаммы уходит в минус → каналы
    // R/G переполняются → мир и персонаж синеют). Свет становится полным уже
    // на ~10; всё, что выше — только цветовые артефакты.
    private static final float FULLBRIGHT_GAMMA = 10.0f;

    public FullBright() {
        super("FullBright", "Visuals", 74);
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    handleKey();
                } catch (Throwable t) {
                    Log.error("FullBright", "tick exception", t);
                }
            }
        });
        Log.info("FullBright", "registered (toggle: J)");
    }

    private void handleKey() {
        GameContext ctx = GameContext.get();
        boolean jDown = ctx.isKeyDown(TOGGLE_KEY);
        if (MenuModule.isOpen()) {
            keyWasDown = jDown;
            return;
        }
        if (jDown && !keyWasDown) {
            toggle();
            Log.info("FullBright", "toggled by J -> " + (isState() ? "ON" : "OFF"));
        }
        keyWasDown = jDown;
    }

    @Override
    protected void onEnable() {
        applyGamma(FULLBRIGHT_GAMMA);
    }

    @Override
    protected void onDisable() {
        if (gammaDefault != null) {
            applyGamma(((Number) gammaDefault).floatValue());
            Log.info("FullBright", "gamma restored to " + gammaDefault);
        }
    }

    private static void applyGamma(float value) {
        try {
            GameContext ctx = GameContext.get();
            if (!resolve(ctx)) return;
            nodeSetValue.invoke(gammaNode, value);
            Log.info("FullBright", "gamma set to " + value);
        } catch (Throwable t) {
            Log.error("FullBright", "applyGamma failed", t);
        }
    }

    private static boolean resolve(GameContext ctx) {
        if (resolved) return gammaNode != null;
        try {
            if (ctx.settingsObj == null) return false;
            Object settings = ctx.settingsObj;
            Method getData = settings.getClass().getMethod("getData");
            Object mainData = getData.invoke(settings);
            Method getScreen = mainData.getClass().getMethod("getScreenSettings");
            Object screenSettings = getScreen.invoke(mainData);
            Method getGamma = screenSettings.getClass().getMethod("getGamma");
            gammaNode = getGamma.invoke(screenSettings);
            if (gammaNode == null) return false;
            // запомнить дефолт
            Method getValue = gammaNode.getClass().getMethod("getValue");
            gammaDefault = getValue.invoke(gammaNode);
            // setValue: OptionNode.setValue(Object)V
            Class optionNode = ctx.gameLoader.loadClass("ru.rustme.settings.OptionNode");
            nodeSetValue = optionNode.getMethod("setValue", Object.class);
            resolved = true;
            Log.info("FullBright", "resolved: gamma node, default=" + gammaDefault);
            return true;
        } catch (Throwable t) {
            Log.error("FullBright", "resolve failed", t);
            return false;
        }
    }
}
