package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.UUID;

/**
 * NoSlow v4: компенсация замедлений movementSpeed ПО МОДИФИКАТОРАМ.
 *
 * РЕШЕНИЕ ПО ДИАГУ 09-09 (вечер): сервер сам вешает op2-модификатор -0.45
 * (×0.55) на movementSpeed при прицеле. Флаги (бит 7 / vanilla usingItem)
 * при этом НЕ взводятся — поэтому flag-based версии молчали (v1-v3).
 *
 * МЕХАНИКА: каждый тик сканируем модификаторы speed-атрибута. Все
 * ОТРИЦАТЕЛЬНЫЕ op2 (замедляющие) нейтрализуем СВОИМ op2-модификатором:
 *   eff = base × Π(1+a_i) × (1+ours); хотим eff = base без них
 *   → (1+ours) = 1/Π(1+a_neg) → ours = 1/Π(1+a_neg) − 1.
 * Для -0.45: ours = +0.8182 → eff = base. Спринт (+0.3/2) сохраняется —
 * он положительный и не входит в произведение.
 *
 * Серверные пакеты атрибутов наши модификаторы не трогают (в хендлере S20
 * re-apply только модов из пакета), base не трогаем вовсе. Ушёл замедляющий
 * мод — убрали свой (повторный apply того же UUID = IllegalArgumentException,
 * поэтому remove+apply при смене amount).
 *
 * ПЛЮС (v5, как у конкурента NoSlowJaggerTick): каждый тик снимаем
 * vanilla-эффект Slowness — сервер вешает его на напитки/эффекты, и он
 * живёт в отдельной системе (не в модификаторах атрибута). Цепочка:
 *   slowness = iillIlIIiI.IIlIIIIIlI("slowness")  (static, по имени)
 *   player.lIIiliilII(potion)                      (removePotionEffect)
 *   player.IiIiiIilII(potion)                      (isPotionActive — диаг)
 * Диаг раз в 1с: hasNegative/need/applied/base/eff/slowness/mods.
 */
public final class NoSlow extends Module {

    private static final UUID OUR_UUID = UUID.fromString("7a1c3d92-55e4-4b8a-9c60-1f2d3e4b5a6b");
    private static final String OUR_NAME = "rv_noslow_comp";
    private static final int OP_MULTIPLY_TOTAL = 2;

    private Object speedAttr;
    private Object lastPlayer;

    private Method modGetUID;        // iIliiliIiI.IlllilIIII() → UUID
    private Method getModifierByUID; // illiiliIiI.IliiIlIIII(UUID) → mod|null
    private Method removeByUUID;     // illiiliIiI.IIIiIlIIII(UUID)V
    private Method modApplyMethod;   // illiiliIiI.liIiIlIIII(mod)V
    private Constructor modCtor;

    private boolean applied;
    private double appliedAmount;
    private boolean compLogged;
    private Object slownessPotion;

    public NoSlow() {
        super("NoSlow", "Movement");
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (isState()) tick();
                } catch (Throwable t) {
                    Log.error("NoSlow", "tick exception", t);
                }
            }
        });
        Log.info("NoSlow", "registered (toggle: menu bind)");
    }

    private void tick() {
        GameContext ctx = GameContext.get();
        if (!ctx.inWorld || ctx.player == null || !ctx.localSpClass.isInstance(ctx.player)) {
            resetState(ctx);
            return;
        }
        if (!ctx.noSlowResolved && !ctx.resolveNoSlow()) return;

        if (speedAttr == null || ctx.player != lastPlayer) {
            try {
                speedAttr = ctx.getAttrInstanceMethod.invoke(ctx.player, ctx.movementSpeedAttr);
                lastPlayer = ctx.player;
            } catch (Throwable t) {
                Log.error("NoSlow", "attr instance failed", t);
                return;
            }
            if (speedAttr == null) return;
            resolveExtra(ctx);
            if (modGetUID == null) return; // resolveExtra упал — молча повторим в след. тике
        }

        try {
            Collection mods = (Collection) ctx.attrGetModifiers.invoke(speedAttr);
            double negProd = 1.0;
            boolean hasNegative = false;
            if (mods != null) {
                Iterator it = mods.iterator();
                while (it.hasNext()) {
                    Object mod = it.next();
                    double amount = (Double) ctx.modGetAmount.invoke(mod);
                    int op = (Integer) ctx.modGetOp.invoke(mod);
                    if (op == OP_MULTIPLY_TOTAL && amount < 0.0) {
                        negProd *= (1.0 + amount);
                        hasNegative = true;
                    }
                }
            }

            double need = 0.0;
            if (hasNegative && negProd > 0.01) {
                need = 1.0 / negProd - 1.0;
            }

            boolean ours = oursPresent(ctx);
            if (need != 0.0) {
                if (!ours || Math.abs(need - appliedAmount) > 0.0005) {
                    if (ours) removeOurs(ctx);
                    applyOurs(ctx, need);
                    applied = true;
                    appliedAmount = need;
                    if (!compLogged) {
                        compLogged = true;
                        Log.info("NoSlow", "compensating: +" + f(need));
                    }
                }
            } else if (ours) {
                removeOurs(ctx);
                applied = false;
                compLogged = false;
            }

            // Slowness-эффект (пиво/зелья сервера) — снимать каждый тик,
            // иначе вешается заново тем же пакетом.
            if (slownessPotion != null) {
                boolean was = (Boolean) ctx.isPotionActive.invoke(ctx.player, slownessPotion);
                if (was) {
                    ctx.removePotionEffect.invoke(ctx.player, slownessPotion);
                }
            }

        } catch (Throwable t) {
            Log.error("NoSlow", "tick failed", t);
        }
    }

    private boolean oursPresent(GameContext ctx) throws Exception {
        Object m = getModifierByUID.invoke(speedAttr, OUR_UUID);
        return m != null;
    }

    private void applyOurs(GameContext ctx, double amount) throws Exception {
        Object mod = modCtor.newInstance(OUR_UUID, OUR_NAME,
            Double.valueOf(amount), Integer.valueOf(OP_MULTIPLY_TOTAL));
        modApplyMethod.invoke(speedAttr, mod);
    }

    private void removeOurs(GameContext ctx) throws Exception {
        removeByUUID.invoke(speedAttr, OUR_UUID);
    }

    private static String f(double v) {
        long r = Math.round(v * 10000.0);
        return String.valueOf(r / 10000.0);
    }

    private void resolveExtra(GameContext ctx) {
        if (modGetUID != null) return;
        try {
            // ctx.modClass резолвился в resolveMovementAttributes (вызывал Strafe,
            // удалён) — поэтому класс модификатора резолвим САМИ, с ретраем.
            Class modC = ctx.gameLoader.loadClass("rustme.iIliiliIiI");
            ctx.modClass = modC;
            modGetUID = modC.getMethod("IlllilIIII");
            // методы по ИНТЕРФЕЙСУ (не по impl-классу): безопасно при прокси
            Class attrInstC = ctx.gameLoader.loadClass("rustme.illiiliIiI");
            getModifierByUID = attrInstC.getMethod("IliiIlIIII", UUID.class);
            removeByUUID = attrInstC.getMethod("IIIiIlIIII", UUID.class);
            // ctx.modApplyMethod резолвил удалённый Strafe — держим свой
            modApplyMethod = attrInstC.getMethod("liIiIlIIII", modC);
            modCtor = modC.getConstructor(UUID.class, String.class, Double.TYPE, Integer.TYPE);
            slownessPotion = ctx.potionByName.invoke(null, "slowness");
            Log.info("NoSlow", "extra resolved (byUID + potion=" + (slownessPotion != null) + ")");
        } catch (Throwable t) {
            Log.error("NoSlow", "extra resolve failed", t);
        }
    }

    private void resetState(GameContext ctx) {
        try {
            if (applied && speedAttr != null) removeOurs(ctx);
        } catch (Throwable ignore) {}
        applied = false;
        appliedAmount = 0.0;
        speedAttr = null;
        lastPlayer = null;
    }

    @Override
    protected void onDisable() {
        resetState(GameContext.get());
    }
}
