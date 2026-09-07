package noslow;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * NoSlow: компенсация sneak-замедления.
 *
 * Механика форка (реверс 2026-09-05):
 *   1) wrapper.liIIIiiilI (onLivingUpdate) каждый тик:
 *      liiliIliI:F (speed) = attr.movementSpeed.getValue()
 *      (на клиенте setBaseValue из input НЕ вызывается - isRemote)
 *   2) атрибут movementSpeed синкается с сервера (EntityProperties) -
 *      сервер занижает при шифте
 *   3) travel (IililiiilI) наземная ветка: f7 = iIIIIiiilI()*friction =
 *      liiliIliI:F * friction - БЕЗ sneak-множителя
 * => noslow = держать атрибут+поле на базовом значении тиком 1мс.
 *
 * Путь к игроку: GS.IllilIIiIl() -> mc -> world -> playerEntities -> liIililiiI.
 */
public class NoSlowAgent implements Runnable {
    private static NoSlowAgent instance;
    private volatile boolean initialized;
    private Object gsInstance;
    private Object mcInstance;
    private Field worldField;
    private Field playersField;
    private Class localSpClass;
    private ClassLoader foundLoader;

    private Method getSpeedField;      // iIIIIiiilI()F (для diag)
    private Method isSneaking;         // iiiilIlIII()Z
    private Field movementInputField;  // wrapper.iiliiIiII -> input holder
    private Field moveForwardField;    // input.IiIiIIiII:F (moveForward)
    private Field moveStrafeField;     // input.iIIiIIiII:F (moveStrafe)
    private float lastFwd;             // последний несниковый fwd
    private float lastStr;             // последний несниковый str
    private long lastDiag;

    private static void llog(String s) {
        try {
            java.io.PrintWriter log = new java.io.PrintWriter(
                new java.io.FileWriter("C:" + java.io.File.separator + "Logs"
                    + java.io.File.separator + "noslow_agent.log", true), true);
            log.println(s);
            log.close();
        } catch (Throwable t) { /* no-op */ }
    }

    public static void install() {
        try {
            instance = new NoSlowAgent();
            Thread t = new Thread(instance, "NoSlowAgent");
            t.setDaemon(true);
            t.start();
            llog("[NoSlow] installed, thread started");
        } catch (Throwable e) {
            llog("[NoSlow] install failed: " + e);
        }
    }

    public void run() {
        try { Thread.sleep(2000L); } catch (InterruptedException e) { return; }
        while (true) {
            try {
                tick();
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                llog("[NoSlow] tick exc: " + t);
                try { Thread.sleep(500L); } catch (InterruptedException e2) { return; }
            }
        }
    }

    private void tick() throws Exception {
        if (!initialized) {
            init();
            return;
        }
        Object world = worldField.get(mcInstance);
        if (world == null) {
            diag("world null");
            return;
        }
        Object player = null;
        for (Object o : (java.util.List) playersField.get(world)) {
            if (o == null) continue;
            if (localSpClass.isInstance(o)) { player = o; break; }
            if (player == null) player = o;
        }
        if (player == null) {
            diag("player not in list");
            return;
        }

        boolean sneaking = (Boolean) isSneaking.invoke(player);
        Object input = movementInputField.get(player);
        if (input == null) { diag("input null"); return; }

        float fwd = moveForwardField.getFloat(input);
        float str = moveStrafeField.getFloat(input);

        // Снимаем ИСХОДНИК (игровой тик: reset до ±1, потом *0.3 при шифте).
        // Наш тик 1мс видит те же значения между тиками. Храним последнее
        // НЕ-шифтовое направление как "желаемое движение".
        if (!sneaking) {
            lastFwd = fwd;
            lastStr = str;
        } else {
            // при шифте: игра *0.3, мы восстанавливаем желаемое направление
            // с сохранением знака и силы (0..1), которую задаёт клавиатура
            if (Math.abs(fwd) > 0.001f && Math.abs(lastFwd) > 0.001f) {
                moveForwardField.setFloat(input, Math.signum(fwd) * Math.abs(lastFwd));
            }
            if (Math.abs(str) > 0.001f && Math.abs(lastStr) > 0.001f) {
                moveStrafeField.setFloat(input, Math.signum(str) * Math.abs(lastStr));
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastDiag > 2000L) {
            lastDiag = now;
            float cur = (Float) getSpeedField.invoke(player);
            llog("[NoSlow] sneak=" + sneaking + " fwd=" + moveForwardField.getFloat(input)
                    + " str=" + moveStrafeField.getFloat(input) + " cur=" + cur);
        }
    }

    private void diag(String s) {
        long now = System.currentTimeMillis();
        if (now - lastDiag > 5000L) {
            lastDiag = now;
            llog("[NoSlow] " + s);
        }
    }

    private void init() throws Exception {
        Class gsClass = null;
        Class mcClass = null;
        Class worldClass = null;
        try {
            gsClass = Class.forName("rustme.iilliIliiI");
            mcClass = Class.forName("rustme.lIliIiiIiI");
            worldClass = Class.forName("rustme.IIlllIlIiI");
            localSpClass = Class.forName("rustme.liIililiiI");
            foundLoader = NoSlowAgent.class.getClassLoader();
        } catch (Throwable t) {
            llog("[NoSlow] forName fail, scanning loaders: " + t);
            for (Thread th : Thread.getAllStackTraces().keySet()) {
                ClassLoader cl = th.getContextClassLoader();
                if (cl == null) continue;
                try {
                    if (cl.loadClass("rustme.iilliIliiI") != null) { foundLoader = cl; break; }
                } catch (Throwable ignore) {}
            }
            if (foundLoader == null) { llog("[NoSlow] no loader sees rustme.*"); return; }
            gsClass = foundLoader.loadClass("rustme.iilliIliiI");
            mcClass = foundLoader.loadClass("rustme.lIliIiiIiI");
            worldClass = foundLoader.loadClass("rustme.IIlllIlIiI");
            localSpClass = foundLoader.loadClass("rustme.liIililiiI");
        }

        java.lang.reflect.Method gsGet = gsClass.getMethod("IllilIIiIl");
        gsInstance = gsGet.invoke(null);
        if (gsInstance == null) { diag("gs null (меню?)"); return; }

        // mc: единственное не-static поле gs со значением instanceof Minecraft
        for (Field f : gsClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(gsInstance);
                if (v != null && mcClass.isInstance(v)) { mcInstance = v; break; }
            } catch (Throwable ignore) {}
        }
        if (mcInstance == null) { diag("mc not found"); return; }

        // world: поле mc типа World
        for (Field f : mcClass.getDeclaredFields()) {
            if (f.getType() == worldClass) { f.setAccessible(true); worldField = f; break; }
        }
        if (worldField == null) { diag("world field not found"); return; }

        // playerEntities: единственное List-поле World с wrapper-элементами
        Class wrapper = foundLoader.loadClass("rustme.IIiIIiIIiI");
        for (Field f : worldClass.getDeclaredFields()) {
            if (f.getType() != java.util.List.class) continue;
            try {
                f.setAccessible(true);
                Object w = worldField.get(mcInstance);
                if (w == null) { diag("world null (не в мире)"); return; }
                java.util.List lst = (java.util.List) f.get(w);
                if (lst == null || lst.isEmpty()) continue;
                Object el = null;
                for (Object o : lst) { if (o != null) { el = o; break; } }
                if (el != null && wrapper.isInstance(el)) { playersField = f; break; }
            } catch (Throwable ignore) {}
        }
        if (playersField == null) { diag("playersField not found"); return; }

        // sneaking + input-путь: moveForward/moveStrafe в liIIIiIIiI (public)
        Class playerClass = foundLoader.loadClass("rustme.liIlIliIiI");
        getSpeedField = playerClass.getMethod("iIIIIiiilI");
        isSneaking = playerClass.getMethod("iiiilIlIII");
        Class inputClass = foundLoader.loadClass("rustme.liIIIiIIiI");
        moveForwardField = inputClass.getField("IiIiIIiII");
        moveStrafeField = inputClass.getField("iIIiIIiII");

        // movementInput: поле wrapper'а типа inputClass (единственное)
        for (Field f : wrapper.getDeclaredFields()) {
            if (f.getType() == inputClass) { f.setAccessible(true); movementInputField = f; break; }
        }

        initialized = true;
        llog("[NoSlow] init OK: input-compensation готова (fwd/str/ sneak/speed)");
    }
}
