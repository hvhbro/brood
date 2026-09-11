package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Method;

/**
 * Custom Fov — клиентский FOV 0..200 (градусы) через узел настроек мода.
 *
 * ЦЕПОЧКА (декомпиляция 09-11): getFOVModifier (liliiiiiii_4.lilIllIiII(F,Z) —
 * зовётся из Project.gluPerspective при построении проекции) читает
 * Settings.getData().getGeneralSettings().getFov() → FloatNode.getValue()
 * (значение в ГРАДУСАХ, дефолт 70; поверх идут zoom-ключ /4, лук/спринт-моды).
 * Пишем узел как FullBright-гамма: OptionNode.setValue(v, tempState=false)
 * пишет storedValue БЕЗ клампа валидатором — можно 0..200.
 *
 * ВАЖНО: ZNANIA 14.2/14.12 ошибочны — gs.iIiiIilliI() это НЕ FOV, а
 * getPartialTicks (потому там «0.16»). Настоящий FOV — только этот узел.
 *
 * Применяем при изменении слайдера + страховочный реплай раз в 1с (узел
 * могло перезаписать что-то другое); при выключении возвращаем значение,
 * которое было на момент включения. FOV<1 клампится до 1: при 0 проекция
 * вырождается (1/tan(0)=inf, кадр ломается), 1° — уже максимальный зум.
 */
public final class CustomFov extends Module {

    public final Module.FloatSetting stFov = addSetting("FOV", 0f, 200f, 1f, 70f);

    private static final float MIN_FOV = 1.0f;

    private boolean resolved;
    private Object fovNode;                // ru.rustme.settings.FloatNode
    private Method nodeSetValue;           // OptionNode.setValue(Object)V
    private Object fovDefault;             // значение на момент включения
    private float lastApplied = Float.NaN;
    private long lastReapply;

    public CustomFov() {
        super("Custom Fov", "Visuals");
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (!isState() || !event.isInWorld()) return;
                    long now = System.currentTimeMillis();
                    float v = Math.max(MIN_FOV, stFov.value);
                    if (v == lastApplied && now - lastReapply < 1000L) return;
                    applyFov(v);
                    lastApplied = v;
                    lastReapply = now;
                } catch (Throwable t) {
                    Log.error("CustomFov", "tick exception", t);
                }
            }
        });
        Log.info("CustomFov", "registered");
    }

    @Override
    protected void onEnable() {
        GameContext ctx = GameContext.get();
        lastApplied = Float.NaN;
        lastReapply = 0L;
        if (!resolve(ctx)) return;
        try {
            Method getValue = fovNode.getClass().getMethod("getValue");
            fovDefault = getValue.invoke(fovNode); // дефолт на момент включения
            Log.info("CustomFov", "enabled, default=" + fovDefault);
        } catch (Throwable t) {
            Log.error("CustomFov", "capture default failed", t);
        }
    }

    @Override
    protected void onDisable() {
        if (fovDefault != null) {
            try {
                applyFov(((Number) fovDefault).floatValue());
            } catch (Throwable t) {
                Log.error("CustomFov", "restore failed", t);
            }
        }
        lastApplied = Float.NaN;
        lastReapply = 0L;
    }

    private void applyFov(float v) {
        try {
            GameContext ctx = GameContext.get();
            if (!resolve(ctx)) return;
            nodeSetValue.invoke(fovNode, Float.valueOf(v));
        } catch (Throwable t) {
            Log.error("CustomFov", "applyFov failed", t);
        }
    }

    private boolean resolve(GameContext ctx) {
        if (resolved) return fovNode != null;
        try {
            if (ctx.settingsObj == null) return false; // мир/настройки ещё не готовы — ретрай
            Method getData = ctx.settingsObj.getClass().getMethod("getData");
            Object mainData = getData.invoke(ctx.settingsObj);
            Method getGeneral = mainData.getClass().getMethod("getGeneralSettings");
            Object general = getGeneral.invoke(mainData);
            Method getFov = general.getClass().getMethod("getFov");
            fovNode = getFov.invoke(general);
            if (fovNode == null) return false;
            Class optionNode = ctx.gameLoader.loadClass("ru.rustme.settings.OptionNode");
            nodeSetValue = optionNode.getMethod("setValue", Object.class);
            resolved = true;
            Log.info("CustomFov", "resolved (GeneralSettings.getFov FloatNode)");
            return true;
        } catch (Throwable t) {
            return false; // тихий ретрай следующим тиком
        }
    }
}
