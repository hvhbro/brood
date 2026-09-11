package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import org.lwjglx.opengl.GL11;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Field;

/**
 * View model — позиция/поворот/масштаб предмета в руке от первого лица.
 *
 * МЕХАНИКА (паттерн CheatIngame): ванильные константы трансформа руки
 * захардкожены в ItemRenderer (llIIliliII → GlStateManager.translate(±0.56,
 * -0.52…, -0.72), декомпиляция lliliiiiii_1), входное поле у него одно —
 * менять нечего. Поэтому подменяем САМ ОБЪЕКТ рендерера: Minecraft.llIlllll
 * (public, тип rustme.llilIiiIiI, единственное поле этого типа в MC) получает
 * rustme.ViewModelRenderer — подкласс, который перед super-рендером руки
 * применяет наши glTranslatef/glRotatef/glScalef (push/pop — без утечек).
 * Настройки — офсеты относительно штатного положения: X/Y ±1 юнит
 * (базовый трансформ ~0.56/-0.72, т.е. ±1 — это сильно), Z ±1, повороты
 * в градусах, масштаб 0.1..2.
 *
 * Подмена ленивая (первый тик в мире, агентный поток — запись ссылки,
 * как ensureHud); onDisable возвращает оригинал. Статики трансформа —
 * volatile, обновляются из настроек каждый тик.
 */
public final class ViewModel extends Module {

    private static ViewModel INSTANCE;

    public final Module.FloatSetting stX = addSetting("Позиция X", -1f, 1f, 0.01f, 0f);
    public final Module.FloatSetting stY = addSetting("Позиция Y", -1f, 1f, 0.01f, 0f);
    public final Module.FloatSetting stZ = addSetting("Позиция Z", -1f, 1f, 0.01f, 0f);
    public final Module.FloatSetting stRotX = addSetting("Поворот X", -90f, 90f, 1f, 0f);
    public final Module.FloatSetting stRotY = addSetting("Поворот Y", -90f, 90f, 1f, 0f);
    public final Module.FloatSetting stRotZ = addSetting("Поворот Z", -180f, 180f, 1f, 0f);
    public final Module.FloatSetting stScale = addSetting("Масштаб", 0.1f, 2f, 0.05f, 1f);

    // статики, читаемые ViewModelRenderer на рендер-потоке
    public static volatile float posX, posY, posZ;
    public static volatile float rotX, rotY, rotZ;
    public static volatile float scale = 1f;
    public static volatile boolean transformActive;

    private boolean swapped;
    private Object originalRenderer;        // rustme.llilIiiIiI
    private Field rendererField;            // Minecraft.llIlllll
    private boolean fieldResolved;

    public ViewModel() {
        super("View model", "Visuals");
        INSTANCE = this;
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (!isState() || !event.isInWorld()) return;
                    posX = stX.value;
                    posY = stY.value;
                    posZ = stZ.value;
                    rotX = stRotX.value;
                    rotY = stRotY.value;
                    rotZ = stRotZ.value;
                    scale = Math.max(0.05f, stScale.value);
                    transformActive = isState();
                    ensureSwapped();
                } catch (Throwable t) {
                    Log.error("ViewModel", "tick exception", t);
                }
            }
        });
        Log.info("ViewModel", "registered");
    }

    /** Вызывается из ViewModelRenderer (рендер-поток). */
    public static boolean isTransformActive() {
        return transformActive;
    }

    /** Применяет наши трансформы (внутри pushMatrix рендерера). */
    public static void applyTransform() {
        GL11.glTranslatef(posX, posY, posZ);
        GL11.glRotatef(rotZ, 0f, 0f, 1f);
        GL11.glRotatef(rotY, 0f, 1f, 0f);
        GL11.glRotatef(rotX, 1f, 0f, 0f);
        GL11.glScalef(scale, scale, scale);
    }

    private boolean ensureSwapped() {
        if (swapped && originalRenderer != null) return true;
        GameContext ctx = GameContext.get();
        if (ctx.mc == null || ctx.gs == null) return false;
        try {
            if (!fieldResolved) {
                Class c = ctx.mc.getClass();
                while (c != null && rendererField == null) {
                    try {
                        rendererField = c.getDeclaredField("llIlllll");
                        rendererField.setAccessible(true);
                    } catch (Throwable ig) {
                        c = c.getSuperclass();
                    }
                }
                if (rendererField == null) {
                    Log.error("ViewModel", "itemRenderer field NOT found", null);
                    fieldResolved = true; // не спамим
                    return false;
                }
                fieldResolved = true;
            }
            Object cur = rendererField.get(ctx.mc);
            if (cur instanceof rustme.ViewModelRenderer) {
                swapped = true;
                return true;
            }
            if (cur == null) return false;
            originalRenderer = cur;
            Object ours = new rustme.ViewModelRenderer((rustme.iilliIliiI) ctx.gs);
            rendererField.set(ctx.mc, ours);
            swapped = true;
            Log.info("ViewModel", "item renderer swapped (original " + cur.getClass().getName() + ")");
            return true;
        } catch (Throwable t) {
            Log.error("ViewModel", "swap failed", t);
            return false;
        }
    }

    @Override
    protected void onDisable() {
        transformActive = false;
        // хук держим, пока его ждёт хоть один из трёх модулей
        if (!CustomSky.wants() && !Ambience.wants()) {
            if (swapped && originalRenderer != null) {
                try {
                    GameContext ctx = GameContext.get();
                    if (ctx.mc != null && rendererField != null) {
                        rendererField.set(ctx.mc, originalRenderer);
                        Log.info("ViewModel", "item renderer restored");
                    }
                } catch (Throwable t) {
                    Log.error("ViewModel", "restore failed", t);
                }
            }
            swapped = false;
        }
    }
}
