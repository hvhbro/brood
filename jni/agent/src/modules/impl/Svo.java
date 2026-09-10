package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import utils.etc.GameContext;
import utils.etc.Log;

/**
 * SVO: подмена скинов ВСЕХ игроков на встроенный putin.png.
 *
 * МЕХАНИКА (дизасм-проверено):
 *  - скин игрока читается рендером через playerInfo-снапшот rustme.iiIilIliiI:
 *    AbstractClientPlayer (iiIililiiI) -> iliiililiI() (снапшот по UUID из карты
 *    сетевого хендлера iliilIliiI.IiIllIIl) -> IlIIillliI() =
 *    illllIIl:Map<ProfileTexture.Type, lllililIiI>.get(Type.SKIN)
 *    (фолбэк на ванильные steve/alex по хешу UUID — IiiiiIiIiI.IlIliiiIII).
 *  - СВОЮ текстуру (rustme.SvoTexture, PNG из payload) регистрируем в их
 *    TextureManager (gs.IIiIiilliI() -> lIliliiIiI.lliIilliII(rl, textureObj);
 *    register сам зовёт texture.loadTexture(manager) — GL-загрузка на главном
 *    потоке) под ResourceLocation "svo:putin".
 *  - ПОДМЕНА: всем снапшотам в карте хендлера ставим SKIN -> svo:putin.
 *    НЕТ SKIN-ключа (свежий профиль) — добавляем ключ сами.
 *  - НОВЫЕ игроки / обновлённые снапшоты подхватываются каждый тик (applyAll),
 *    пока наш RL не перетёрли настоящим (проигрываем: настоящий скин вернётся).
 *  - ОТКАТ (выключение): SKIN <- исходное значение, но только если в карте до сих
 *    пор стоит НАШ RL (identity) — если игра уже сама перезаписала скином, ничего
 *    не трогаем. Если исходно SKIN не было и наш RL ещё там — удаляем ключ.
 *  - ПОТОКИ: и подмена, и откат — только через main-thread очередь
 *    (GameContext.runOnMainThread -> CheatHud.renderFrame), там же живут сетевые
 *    чтения карт рендером: мутация и чтение в одном потоке.
 *
 * Включение — бинд из меню (Module.tickBind), дефолтных клавиш нет.
 * Скин третьего лица + головы Tab; рука локального игрока
 * не завязана на эту карту — не меняется.
 */
public final class Svo extends Module {

    private static final String RL_DOMAIN = "svo";
    private static final String RL_PATH = "putin";

    private boolean swapInstalled;    // текстура зарегистрирована в TextureManager
    private boolean swapLogged;
    private long lastLog;

    // --- резолвнутые reflection-объекты ---
    private Object putinRl;           // rustme.lllililIiI("svo","putin")
    private Object svoTexture;        // rustme.SvoTexture instance
    private Object texManager;        // gs.IIiIiilliI() (lIliliiIiI)
    private Object skinTypeKey;       // com.mojang.authlib...MinecraftProfileTexture$Type.SKIN
    private Class snapshotClass;      // rustme.iiIilIliiI
    private java.lang.reflect.Method texRegister;     // lliIilliII(RL, texObj)Z
    private java.lang.reflect.Field snapshotMapField; // illllIIl:Ljava/util/Map;
    private java.lang.reflect.Field handlerMapField;  // IiIllIIl:Object (Map<UUID, snapshot>)
    private java.lang.reflect.Field spHandlerField;   // SP.lIlilIIl:Object

    // снапшоты с подменой: snapshot identity -> исходный SKIN value (null = не было)
    private final java.util.IdentityHashMap<Object, Object> swapped =
        new java.util.IdentityHashMap<Object, Object>();

    public Svo() {
        super("SVO");
        setState(false); // выключен по умолчанию
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (isState()) onTick(event);
                } catch (Throwable t) {
                    Log.error("SVO", "onTick exception", t);
                }
            }
        });
        Log.info("SVO", "registered (toggle: menu bind)");
    }

    @Override
    protected void onDisable() {
        // откат — тоже на главном потоке (чтение карт рендером там же)
        GameContext.get().runOnMainThread(new Runnable() {
            public void run() {
                try {
                    revertAll();
                } catch (Throwable t) {
                    Log.error("SVO", "revert exception", t);
                }
            }
        });
    }

    @Override
    public void onTick(TickEvent event) {
        if (!event.isInWorld()) return;
        GameContext ctx = GameContext.get();
        if (texManager == null && !resolveOnce(ctx)) return; // троттлинг внутри

        if (!swapInstalled) {
            // регистрация текстуры — раз, на главном потоке (при неудаче retry раз в 2с)
            long now = System.currentTimeMillis();
            if (now - lastRegisterAttempt < 2000L) return;
            lastRegisterAttempt = now;
            ctx.runOnMainThread(new Runnable() {
                public void run() {
                    if (swapInstalled) return;
                    try {
                        Boolean ok = (Boolean) texRegister.invoke(texManager, putinRl, svoTexture);
                        swapInstalled = ok != null && ok.booleanValue();
                        Log.info("SVO", "texture register -> " + swapInstalled
                            + " (rl=" + RL_DOMAIN + ":" + RL_PATH + ")");
                    } catch (Throwable t) {
                        Log.error("SVO", "register failed", t);
                    }
                }
            });
            return;
        }
        // подмена скинов — каждый тик (главный поток), новые снапшоты подхватываются
        ctx.runOnMainThread(new Runnable() {
            public void run() {
                try {
                    applyAll();
                } catch (Throwable t) {
                    Log.error("SVO", "apply exception", t);
                }
            }
        });
    }

    // ------------------------------------------------------------------ resolve

    private long lastResolveAttempt;
    private long lastRegisterAttempt;

    /** Разовый резолв классов/полей/методов/ключа SKIN. false = попробовать позже. */
    private boolean resolveOnce(GameContext ctx) {
        if (texManager != null) return true;
        long now = System.currentTimeMillis();
        if (now - lastResolveAttempt < 2000L) return false;
        lastResolveAttempt = now;
        try {
            ClassLoader cl = ctx.gameLoader;

            Class rlClass = cl.loadClass("rustme.lllililIiI");
            putinRl = rlClass.getConstructor(String.class, String.class)
                .newInstance(RL_DOMAIN, RL_PATH);

            Class texObjClass = cl.loadClass("rustme.iiIiliiIiI"); // интерфейс TextureObject
            svoTexture = new rustme.SvoTexture(); // НАШ класс (тот же лоадер, пакет rustme)

            java.lang.reflect.Method getTM = ctx.gs.getClass().getMethod("IIiIiilliI");
            texManager = getTM.invoke(ctx.gs);
            if (texManager == null) { Log.info("SVO", "texture manager null"); return false; }
            texRegister = texManager.getClass().getMethod("lliIilliII", rlClass, texObjClass);

            snapshotClass = cl.loadClass("rustme.iiIilIliiI");
            snapshotMapField = snapshotClass.getField("illllIIl"); // public Map

            Class handlerClass = cl.loadClass("rustme.iliilIliiI");
            handlerMapField = handlerClass.getField("IiIllIIl"); // public final Object
            spHandlerField = ctx.localSpClass.getField("lIlilIIl"); // public final Object

            // ключ SKIN: enum MinecraftProfileTexture.Type (authlib в рантайме есть)
            Class typeClass = Class.forName(
                "com.mojang.authlib.minecraft.MinecraftProfileTexture$Type", true, cl);
            skinTypeKey = Enum.valueOf((Class<? extends Enum>) typeClass.asSubclass(Enum.class), "SKIN");

            Log.info("SVO", "resolved: rl/tm/snapshot/handler/skinKey OK");
            return true;
        } catch (Throwable t) {
            Log.error("SVO", "resolve failed (retry in 2s)", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ apply/revert

    /** Ставит SKIN -> svo:putin всем живым снапшотам (главный поток). */
    private void applyAll() throws Exception {
        GameContext ctx = GameContext.get();
        Object handler = spHandlerField.get(ctx.player);
        if (handler == null) return;
        Object mapObj = handlerMapField.get(handler);
        if (!(mapObj instanceof java.util.Map)) return;
        Object[] snapshots = ((java.util.Map<?, ?>) mapObj).values().toArray(new Object[0]);

        int changed = 0, reasserted = 0, dead = 0;
        for (int i = 0; i < snapshots.length; i++) {
            Object snap = snapshots[i];
            if (snap == null || !snapshotClass.isInstance(snap)) continue;

            if (swapped.containsKey(snap)) { // уже подменён (identity)
                Object map = snapshotMapField.get(snap);
                if (!(map instanceof java.util.Map)) { dead++; swapped.remove(snap); continue; }
                java.util.Map<Object, Object> texMap = (java.util.Map<Object, Object>) map;
                // игра могла позже записать настоящий SKIN (профиль доехал) — вернём putin
                if (texMap.get(skinTypeKey) != putinRl) {
                    texMap.put(skinTypeKey, putinRl);
                    reasserted++;
                }
                continue;
            }

            Object map = snapshotMapField.get(snap);
            if (!(map instanceof java.util.Map)) continue;
            java.util.Map<Object, Object> texMap = (java.util.Map<Object, Object>) map;

            Object original = texMap.get(skinTypeKey); // может быть null (нет SKIN)
            texMap.put(skinTypeKey, putinRl);
            swapped.put(snap, original);
            changed++;
        }

        // чистка ушедших игроков: в swapped остаются только живые снапшоты
        java.util.IdentityHashMap<Object, Object> live =
            new java.util.IdentityHashMap<Object, Object>();
        for (int i = 0; i < snapshots.length; i++) {
            if (snapshots[i] != null) live.put(snapshots[i], Boolean.TRUE);
        }
        swapped.keySet().retainAll(live.keySet());

        if (dead > 0 && System.currentTimeMillis() - lastLog >= 1000L) {
            Log.info("SVO", "dropped " + dead + " snapshot(s) with dead texture map");
        }

        if ((changed > 0 || reasserted > 0) && System.currentTimeMillis() - lastLog >= 1000L) {
            lastLog = System.currentTimeMillis();
            swapLogged = true;
            Log.info("SVO", "skins swapped: +" + changed + " reassert:" + reasserted
                + " (total " + swapped.size() + ")");
        }
    }

    /** Откат: только там, где до сих пор стоит НАШ RL (identity) (главный поток). */
    private void revertAll() throws Exception {
        if (swapped.isEmpty()) { Log.info("SVO", "revert: nothing to restore"); return; }
        GameContext ctx = GameContext.get();
        int restored = 0, skipped = 0;
        try {
            Object handler = spHandlerField.get(ctx.player);
            if (handler != null) {
                Object mapObj = handlerMapField.get(handler);
                if (mapObj instanceof java.util.Map) {
                    Object[] snapshots =
                        ((java.util.Map<?, ?>) mapObj).values().toArray(new Object[0]);
                    for (int i = 0; i < snapshots.length; i++) {
                        Object snap = snapshots[i];
                        if (snap == null || !swapped.containsKey(snap)) continue;
                        Object map = snapshotMapField.get(snap);
                        if (!(map instanceof java.util.Map)) continue;
                        java.util.Map<Object, Object> texMap = (java.util.Map<Object, Object>) map;
                        Object original = swapped.get(snap);
                        if (texMap.get(skinTypeKey) == putinRl) {
                            if (original != null) {
                                texMap.put(skinTypeKey, original);
                            } else {
                                texMap.remove(skinTypeKey);
                            }
                            restored++;
                        } else {
                            skipped++; // игра сама уже вернула/заменила скин
                        }
                        swapped.remove(snap);
                    }
                }
            }
        } finally {
            // мёртвые снапшоты (игрок уже вышел) просто забываем
            swapped.clear();
            Log.info("SVO", "revert done: restored " + restored
                + ", skipped " + skipped + " (already overwritten by game)");
        }
    }
}
