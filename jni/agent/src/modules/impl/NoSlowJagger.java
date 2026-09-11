package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import rustme.IIlIIliIiI;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * NoSlowJagger — порт модуля чит-клиента (chtdump, config-блоб .rdata):
 *   noSlowDjagerEnabled=1, Debug=1, AlwaysSprint=0, OnlyJugg=0,
 *   MaxHunger=1, MotionBoost=0, RemoveSlowness=1,
 *   Speed=0.2, WalkSpeed=0.15.
 *
 * СЕМАНТИКА (по набору настроек; нативный код конкурента в дампе не
 * дизасмился — восстановлено по именам параметров):
 *  - RemoveSlowness: снимать PotionEffect Slowness каждый тик
 *    (тот же приём, что наш NoSlow v5; сервер может вешать заново пакетом);
 *  - WalkSpeed (0.15): capabilities walkSpeed (liIIIiIIiI.iIIiIIiII:F — у нас
 *    это ctx.moveStrafeField) — клиентское входное замедление, vanilla 0.1;
 *  - Speed (0.2): атрибут movementSpeed, ADD-модификатор с фиксированным UUID;
 *  - MaxHunger: client-side foodStats.foodLevel = 20 (голодное замедление
 *    форка считается на клиенте из foodStats — держим «сытым»);
 *  - AlwaysSprint: зажим KeyBinding sprint (как AutoSprint);
 *  - OnlyJugg: активно только с jug (фляга) в руке.
 * Клавиши: только бинд из меню.
 */
public final class NoSlowJagger extends Module {

    private static final UUID SPEED_UUID = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final String SPEED_NAME = "NoSlowJaggerSpeed";
    private static final int OP_ADD = 0;           // vanilla ADD_AMOUNT
    private static final float WALK_DEFAULT = 0.1f;

    private static NoSlowJagger INSTANCE;

    private final Module.BoolSetting stRemoveSlowness = addBool("Remove Slowness", true);
    private final Module.BoolSetting stMaxHunger = addBool("Max Hunger", true);
    private final Module.BoolSetting stAlwaysSprint = addBool("Always Sprint", false);
    private final Module.BoolSetting stOnlyJugg = addBool("Only Jugg", false);
    private final Module.FloatSetting stSpeed = addSetting("Speed", 0f, 0.4f, 0.01f, 0.2f);
    private final Module.FloatSetting stWalkSpeed = addSetting("Walk Speed", 0.1f, 0.3f, 0.01f, 0.15f);

    // reflection (резолвится в самом модуле — урок NPE после удаления Strafe)
    private boolean resolved;
    private Object slownessPotion;
    private Method attrGetByUID, attrRemoveByUID, attrApply;
    private Constructor<?> modCtor;
    private Field invPlayerF;      // wrapper.iIliiIiII → InventoryPlayer (свой резолв)
    private Field foodStatsF;      // wrapper.iliIiIiII → FoodStats (IIlIIilIiI)
    private Field foodLevelF;      // FoodStats.iIlllIIlI = foodLevel (getter lIilIIillI)
    private boolean sprintPressed;
    private Float walkOriginal;
    private boolean speedApplied;
    private long lastLog;

    public NoSlowJagger() {
        super("NoSlowJagger", "Movement");
        INSTANCE = this;
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (isState()) tick(event);
                } catch (Throwable t) {
                    Log.error("NoSlowJagger", "tick exception", t);
                }
            }
        });
        Log.info("NoSlowJagger", "registered");
    }

    @Override
    protected void onDisable() {
        revertAll();
    }

    private void revertAll() {
        GameContext ctx = GameContext.get();
        try {
            if (speedApplied && ctx.getAttrInstanceMethod != null
                && ctx.movementSpeedAttr != null && attrRemoveByUID != null) {
                Object attr = ctx.getAttrInstanceMethod.invoke(ctx.player, ctx.movementSpeedAttr);
                if (attr != null) attrRemoveByUID.invoke(attr, SPEED_UUID);
            }
            speedApplied = false;
            if (walkOriginal != null && ctx.moveStrafeField != null) {
                Object holder = holder(ctx);
                if (holder != null) ctx.moveStrafeField.setFloat(holder, walkOriginal.floatValue());
            }
            walkOriginal = null;
        } catch (Throwable ignore) {}
        try {
            if (sprintPressed) {
                Object kb = ctx.keyBindingFor("getKeySprint");
                if (kb != null && ctx.kbPressedField != null) ctx.kbPressedField.setBoolean(kb, false);
            }
        } catch (Throwable ignore) {}
        sprintPressed = false;
    }

    private boolean resolve(GameContext ctx) {
        if (resolved) return true;
        // potionByName/isPotionActive/removePotionEffect резолвятся в
        // GameContext.resolveNoSlow — вызываем сами (не полагаемся на NoSlow)
        if (ctx.potionByName == null) ctx.resolveNoSlow();
        if (ctx.potionByName == null || ctx.isPotionActive == null
            || ctx.removePotionEffect == null) {
            return false; // retry на следующем тике
        }
        try {
            slownessPotion = ctx.potionByName.invoke(null, "slowness");
            Class modC = ctx.modClass;
            if (modC == null) {
                modC = ctx.gameLoader.loadClass("rustme.iIliiliIiI");
                ctx.modClass = modC;
            }
            Class attrInstC = ctx.gameLoader.loadClass("rustme.illiiliIiI");
            attrGetByUID = attrInstC.getMethod("IliiIlIIII", UUID.class);
            attrRemoveByUID = attrInstC.getMethod("IIIiIlIIII", UUID.class);
            attrApply = attrInstC.getMethod("liIiIlIIII", modC);
            modCtor = modC.getConstructor(
                UUID.class, String.class, Double.TYPE, Integer.TYPE);
            foodStatsF = ctx.wrapperClass.getField("iliIiIiII");
            Class foodC = ctx.gameLoader.loadClass("rustme.IIlIIilIiI");
            foodLevelF = foodC.getField("iIlllIIlI");
            resolved = true;
            Log.info("NoSlowJagger", "resolved (slowness=" + (slownessPotion != null) + ")");
            return true;
        } catch (Throwable t) {
            Log.error("NoSlowJagger", "resolve failed", t);
            return false;
        }
    }

    private void tick(TickEvent event) {
        GameContext ctx = GameContext.get();
        if (!event.isInWorld() || ctx.player == null) {
            revertAll();
            return;
        }
        if (!resolve(ctx)) return;

        boolean active = true;
        if (stOnlyJugg.get() && !holdingJug(ctx)) active = false;

        // RemoveSlowness
        if (active && stRemoveSlowness.get() && slownessPotion != null) {
            try {
                boolean was = (Boolean) ctx.isPotionActive.invoke(ctx.player, slownessPotion);
                if (was) ctx.removePotionEffect.invoke(ctx.player, slownessPotion);
            } catch (Throwable ignore) {}
        }

        // Speed (атрибут ADD)
        try {
            if (ctx.getAttrInstanceMethod != null && ctx.movementSpeedAttr != null) {
                Object attr = ctx.getAttrInstanceMethod.invoke(ctx.player, ctx.movementSpeedAttr);
                if (attr != null) {
                    boolean ours = ((Object) attrGetByUID.invoke(attr, SPEED_UUID)) != null;
                    if (active && stSpeed.value > 0.001f) {
                        if (!ours) {
                            Object mod = modCtor.newInstance(SPEED_UUID, SPEED_NAME,
                                Double.valueOf(stSpeed.value), Integer.valueOf(OP_ADD));
                            attrApply.invoke(attr, mod);
                            speedApplied = true;
                        } else {
                            speedApplied = true;
                        }
                    } else if (ours) {
                        attrRemoveByUID.invoke(attr, SPEED_UUID);
                        speedApplied = false;
                    }
                }
            }
        } catch (Throwable t) {
            Log.error("NoSlowJagger", "speed failed", t);
        }

        // WalkSpeed (capabilities, клиентское входное замедление)
        try {
            if (ctx.moveStrafeField != null) {
                Object holder = holder(ctx);
                if (holder != null) {
                    if (active) {
                        if (walkOriginal == null) {
                            walkOriginal = Float.valueOf(ctx.moveStrafeField.getFloat(holder));
                        }
                        ctx.moveStrafeField.setFloat(holder, stWalkSpeed.value);
                    } else if (walkOriginal != null) {
                        ctx.moveStrafeField.setFloat(holder, walkOriginal.floatValue());
                        walkOriginal = null;
                    }
                }
            }
        } catch (Throwable t) {
            Log.error("NoSlowJagger", "walk speed failed", t);
        }

        // MaxHunger: держим client-side foodStats.foodLevel = 20
        if (active && stMaxHunger.get() && foodStatsF != null && foodLevelF != null) {
            try {
                Object fs = foodStatsF.get(ctx.player);
                if (fs != null) foodLevelF.setInt(fs, 20);
            } catch (Throwable ignore) {}
        }

        // AlwaysSprint
        try {
            boolean want = active && stAlwaysSprint.get();
            if (want) {
                Object kb = ctx.keyBindingFor("getKeySprint");
                if (kb != null && ctx.kbPressedField != null) {
                    ctx.kbPressedField.setBoolean(kb, true);
                    sprintPressed = true;
                }
            } else if (sprintPressed) {
                Object kb = ctx.keyBindingFor("getKeySprint");
                if (kb != null && ctx.kbPressedField != null) ctx.kbPressedField.setBoolean(kb, false);
                sprintPressed = false;
            }
        } catch (Throwable ignore) {}

        long now = System.currentTimeMillis();
        if (now - lastLog > 5000L) {
            lastLog = now;
            Log.info("NoSlowJagger", "active=" + active + " speed=" + stSpeed.value
                + " walk=" + stWalkSpeed.value + " slowness=" + stRemoveSlowness.get());
        }
    }

    /** capabilities-холдер (wrapper.iiliiIiII → liIIIiIIiI). */
    private Object holder(GameContext ctx) throws Exception {
        if (ctx.movementInputField == null) return null;
        return ctx.movementInputField.get(ctx.player);
    }

    /** В руке фляга (jug)? — по имени предмета. */
    private boolean holdingJug(GameContext ctx) {
        try {
            if (invPlayerF == null) {
                Class c = ctx.localSpClass;
                while (c != null && invPlayerF == null) {
                    try {
                        invPlayerF = c.getDeclaredField("iIliiIiII");
                        invPlayerF.setAccessible(true);
                    } catch (Throwable ig) {
                        c = c.getSuperclass();
                    }
                }
                if (invPlayerF == null) return false;
            }
            Object inv = invPlayerF.get(ctx.player);
            if (inv == null) return false;
            Method cur = inv.getClass().getMethod("lIiIIIiilI");
            Object stack = cur.invoke(inv);
            if (stack == null) return false;
            Method nm = stack.getClass().getMethod("IlIlIililI");
            String s = (String) nm.invoke(stack);
            return s != null && s.toLowerCase().contains("jug");
        } catch (Throwable t) {
            return false;
        }
    }
}
