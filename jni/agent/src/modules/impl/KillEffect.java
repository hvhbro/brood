package modules.impl;

import org.lwjgl.opengl.GL20;
import org.lwjglx.opengl.GL11;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import rustme.IIlIIliIiI;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.KillShaders;
import utils.render.ShaderUtil;
import utils.render.Shaders;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * KillEffect — шейдерный эффект на месте убитого игрока (Rust-стиль).
 *
 * ДЕТЕКТ УБИЙСТВА: сервер не шлёт kill-feed чужих убийств (s2c-каталог:
 * rust:dmg:death = ТОЛЬКО наш death-screen), поэтому — vanilla-звук смерти
 * entity.player.death, он играет НА ПОЗИЦИИ ТРУПА (S2C sound → SoundManager,
 * тот же пайплайн, что SoundEsp: gs.iiliIilliI → SoundEngine.iIliiiil →
 * SoundManager.IiliIliIl playingSounds, дифф по ключу).
 *
 * АТРИБУЦИЯ «наш/не наш» («Только мои», вкл по умолчанию): жертва должна
 * быть нашей недавней целью AimBot (<=12с) и её последний снимок буфера —
 * в 8м от точки смерти (AimBot.recentTargetNear).
 *
 * РЕНДЕР: билборд-квад на проекции места смерти (Esp.projectToScreen — та же
 * камера, что JumpCircle), шейдер KillShaders (порт kill_effect_tornado /
 * kill_effect_portal → GLSL 120). texCoord считается по gl_FragCoord + rect.
 * Режимы: Смерч (столб-вихрь из земли) / Портал (сфера-ядро с кольцами).
 *
 * Настройки: Режим, Цвет, Дистанция (до места смерти), Размер (высота в бл),
 * Длительность (мс, = uProgress 0..1), Интенсивность, Только мои.
 */
public final class KillEffect extends Module {

    private static KillEffect INSTANCE;

    private final Module.ModeSetting stMode = addMode("Режим",
        new String[]{"Смерч", "Портал"}, 0);
    private final Module.ColorSetting stColor = addColor("Цвет", 0xFF906BFF);
    private final Module.FloatSetting stDist = addSetting("Дистанция", 16f, 128f, 1f, 64f);
    private final Module.FloatSetting stSize = addSetting("Размер", 1f, 5f, 0.25f, 3f);
    private final Module.FloatSetting stDuration = addSetting("Длительность", 1000f, 5000f, 100f, 3000f);
    private final Module.FloatSetting stIntensity = addSetting("Интенсивность", 20f, 100f, 5f, 85f);
    private final Module.BoolSetting stOnlyMine = addBool("Только мои", true);

    // ===== активные эффекты =====
    private static final class Fx {
        double x, y, z;
        long start;
    }

    private final java.util.ArrayList<Fx> fx = new java.util.ArrayList<Fx>();
    private long lastFxLog;
    private long lastSndDiag;

    // ===== звуковая цепочка (та же, что SoundEsp — read-only) =====
    private boolean sndResolved, sndTried;
    private Method sndEngineGetter;   // gs.iiliIilliI() → SoundEngine
    private Field sndMgrField;        // SoundEngine.iIliiiil → SoundManager
    private Field playingMapField;    // SoundManager.IiliIliIl → LinkedHashMap
    private Field wrapperSoundField;  // SoundInstance.IliiIliIl → ISound
    private Method resNameGetter;     // lllililIiI.lIilllillI() → path
    private Object soundEngine, soundManager, playingSounds;
    private final java.util.HashSet seenKeys = new java.util.HashSet();
    private final java.util.HashMap sndMethodCache = new java.util.HashMap();

    // ===== шейдеры (лениво, per-режим; fail-fast как JumpDistort) =====
    private boolean shaderTried;
    private ShaderUtil progTornado, progPortal;

    private static final float[] P = new float[2];

    public KillEffect() {
        super("KillEffect", "Visuals");
        INSTANCE = this;
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (isState()) tick(event);
                } catch (Throwable t) {
                    Log.error("KillEffect", "tick exception", t);
                }
            }
        });
        Log.info("KillEffect", "registered");
    }

    @Override
    protected void onDisable() {
        fx.clear();
    }

    private void tick(TickEvent event) {
        GameContext ctx = GameContext.get();
        if (ctx.mc == null || ctx.gs == null) return;
        long now = System.currentTimeMillis();
        long dur = (long) stDuration.value;
        synchronized (fx) {
            while (!fx.isEmpty() && now - ((Fx) fx.get(0)).start > dur) fx.remove(0);
            while (fx.size() > 8) fx.remove(0);
        }
        if (!event.isInWorld()) return;
        if (!sndResolved) resolveSoundChain(ctx);
        if (sndResolved) pollSounds(ctx);
    }

    // ===== звук: резолв + поллинг (копия цепочки SoundEsp) =====

    private void resolveSoundChain(GameContext ctx) {
        if (sndTried) return;
        sndTried = true;
        try {
            sndEngineGetter = ctx.gs.getClass().getMethod("iiliIilliI");
            Class engineC = ctx.gameLoader.loadClass("rustme.IIiililiiI");
            sndMgrField = engineC.getField("iIliiiil");
            Class mgrC = ctx.gameLoader.loadClass("rustme.IililIiliI");
            playingMapField = mgrC.getField("IiliIliIl");
            Class wrapperC = ctx.gameLoader.loadClass("rustme.llIilIiliI");
            wrapperSoundField = wrapperC.getField("IliiIliIl");
            Class resLocC = ctx.gameLoader.loadClass("rustme.lllililIiI");
            resNameGetter = resLocC.getMethod("lIilllillI");
            sndResolved = true;
            Log.info("KillEffect", "sound chain resolved");
        } catch (Throwable t) {
            Log.error("KillEffect", "sound chain resolve failed", t);
        }
    }

    private void pollSounds(GameContext ctx) {
        try {
            if (soundEngine == null) {
                soundEngine = sndEngineGetter.invoke(ctx.gs);
                if (soundEngine == null) return;
            }
            if (soundManager == null) {
                soundManager = sndMgrField.get(soundEngine);
                if (soundManager == null) return;
            }
            if (playingSounds == null) {
                playingSounds = playingMapField.get(soundManager);
                if (playingSounds == null) return;
            }
            java.util.Map map = (java.util.Map) playingSounds;
            Object[] arr;
            try {
                arr = map.entrySet().toArray();
            } catch (Throwable cme) {
                return;
            }
            for (int i = 0; i < arr.length; i++) {
                java.util.Map.Entry entry = (java.util.Map.Entry) arr[i];
                Object key = entry.getKey();
                if (seenKeys.contains(key)) continue;
                seenKeys.add(key);
                if (seenKeys.size() > 4096) {
                    seenKeys.clear();
                    continue;
                }
                Object wrapper = entry.getValue();
                if (wrapper == null) continue;
                Object isound = wrapperSoundField.get(wrapper);
                if (isound == null) continue;
                Method[] sms = methodsFor(isound.getClass());
                if (sms == null) continue;
                Object resLoc = sms[0].invoke(isound);
                if (resLoc == null) continue;
                String name = (String) resNameGetter.invoke(resLoc);
                if (name == null) continue;
                // диагностика: ВСЕ новые звуки (по ним найдём имя звука смерти;
                // троттл 1/с — SoundEsp может быть выключен)
                long nowL = System.currentTimeMillis();
                if (nowL - lastSndDiag > 1000L) {
                    lastSndDiag = nowL;
                    Log.info("KillEffect", "snd: " + name + " @("
                        + (int) sms[1].invoke(isound) + "," + (int) sms[2].invoke(isound)
                        + "," + (int) sms[3].invoke(isound) + ")");
                }
                String low = name.toLowerCase();
                if (!low.contains("death") && !low.contains("kill")
                    && !low.contains("corpse") && !low.contains("dmg:death")
                    && !low.contains("wounded")) {
                    continue;
                }
                float x = (Float) sms[1].invoke(isound);
                float y = (Float) sms[2].invoke(isound);
                float z = (Float) sms[3].invoke(isound);
                onDeathSound(ctx, name, x, y, z);
            }
        } catch (Throwable t) {
            // звуковой поток мутирует мапу — тик пропускаем, не роняем
        }
    }

    private Method[] methodsFor(Class c) {
        String key = c.getName();
        Method[] cached = (Method[]) sndMethodCache.get(key);
        if (cached != null) return cached.length == 0 ? null : cached;
        try {
            Method[] ms = new Method[]{
                c.getMethod("llIilIiliI"),   // ResourceLocation
                c.getMethod("lIIilIiliI"),   // getX
                c.getMethod("lIlilIiliI"),   // getY
                c.getMethod("iililIiliI"),   // getZ
            };
            sndMethodCache.put(key, ms);
            return ms;
        } catch (Throwable t) {
            sndMethodCache.put(key, new Method[0]);
            return null;
        }
    }

    // ===== смерть услышана =====

    private void onDeathSound(GameContext ctx, String name, float x, float y, float z) {
        IIlIIliIiI me = (IIlIIliIiI) ctx.player;
        if (me == null) return;
        double dx = x - me.IlIiillIII(), dy = y - me.liiiIllIII(), dz = z - me.lIilillIII();
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > stDist.value * stDist.value) return;

        if (stOnlyMine.get() && AimBot.recentTargetNear(x, y, z, 8.0, 12000L) < 0) {
            return;
        }
        synchronized (fx) {
            if (!fx.isEmpty()) {
                Fx last = (Fx) fx.get(fx.size() - 1);
                double ddx = last.x - x, ddy = last.y - y, ddz = last.z - z;
                if (ddx * ddx + ddy * ddy + ddz * ddz < 4.0
                    && System.currentTimeMillis() - last.start < 2000L) {
                    return; // дедуп: тот же труп в той же точке
                }
            }
            Fx f = new Fx();
            f.x = x; f.y = y; f.z = z;
            f.start = System.currentTimeMillis();
            fx.add(f);
        }
        long now = System.currentTimeMillis();
        if (now - lastFxLog > 1000L) {
            lastFxLog = now;
            Log.info("KillEffect", "fx spawned: " + name + " @("
                + (int) x + "," + (int) y + "," + (int) z + ")");
        }
    }

    // ===== рендер =====

    private void ensureShaders() {
        if (shaderTried) return;
        shaderTried = true;
        try {
            progTornado = new ShaderUtil(Shaders.VERT, KillShaders.TORNADO_FSH);
            progPortal = new ShaderUtil(Shaders.VERT, KillShaders.PORTAL_FSH);
            Log.info("KillEffect", "shaders: tornado=" + progTornado.programId()
                + " portal=" + progPortal.programId());
        } catch (Throwable t) {
            Log.error("KillEffect", "shader init failed", t);
        }
    }

    public static void render(GameContext ctx, float partialTicks, int scaledW, int scaledH) {
        KillEffect inst = INSTANCE;
        if (inst == null || !inst.isState() || inst.fx.isEmpty()) return;
        if (!ctx.inWorld || ctx.player == null) return;
        try {
            Esp.updateCamera(ctx, partialTicks);
            if (!Esp.cameraReady()) return;
            inst.ensureShaders();

            int mode = inst.stMode.index();
            ShaderUtil prog = mode == 0 ? inst.progTornado : inst.progPortal;
            if (prog == null || prog.programId() == 0) return; // фрагмент не собрался

            int color = inst.stColor.argb;
            float intensity = st01(inst.stIntensity.value);
            long dur = (long) inst.stDuration.value;
            float size = inst.stSize.value;
            float gs = ctx.guiScale > 0 ? ctx.guiScale : 1f;
            int fbH = ctx.fbHeight > 0 ? ctx.fbHeight : (int) (scaledH * gs);

            boolean blendWas = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depthWas = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean texWas = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            boolean atWas = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(0x0BA0); // GL_LIGHTING
            GL11.glDisable(0x0B44); // GL_CULL_FACE
            GL11.glDisable(0x0BC0); // GL_ALPHA_TEST
            GL11.glDisable(0x0B57); // GL_COLOR_MATERIAL
            GL11.glDisable(0x0B60); // GL_FOG

            GL20.glUseProgram(prog.programId());
            GL20.glUniform1i(prog.uniform("atlas"), 0);
            prog.uniform4F("vertexColor", 1f, 1f, 1f, 1f);
            prog.uniformF("fbHeight", (float) fbH);
            prog.uniform4F("uColor",
                ((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f,
                (color & 0xFF) / 255f, ((color >> 24) & 0xFF) / 255f);
            prog.uniformF("uIntensity", intensity);
            prog.uniformF("uSize", size);

            long now = System.currentTimeMillis();
            synchronized (inst.fx) {
                for (int i = inst.fx.size() - 1; i >= 0; i--) {
                    Fx f = (Fx) inst.fx.get(i);
                    float progress = (float) (now - f.start) / (float) dur;
                    if (progress >= 1f) {
                        inst.fx.remove(i);
                        continue;
                    }
                    // билборд: база (земля) и вершина (y+size)
                    double cw = Esp.projectToScreen(f.x, f.y, f.z, scaledW, scaledH, P);
                    if (cw <= 0.2) continue;
                    float bx = P[0], by = P[1];
                    Esp.projectToScreen(f.x, f.y + size, f.z, scaledW, scaledH, P);
                    if (Esp.lastW() <= 0.2) continue;
                    float ty = P[1];
                    float h = Math.abs(by - ty);
                    if (h < 2f) continue;
                    float w = h * (mode == 0 ? 0.75f : 1.0f);
                    float left = bx - w * 0.5f;
                    float top = Math.min(by, ty);

                    prog.uniformF("uTime", (now - f.start) / 1000f);
                    prog.uniformF("uProgress", progress);
                    prog.uniform4F("rect",
                        left * gs, top * gs, Math.max(1f, w * gs), Math.max(1f, h * gs));

                    GL11.glBegin(GL11.GL_QUADS);
                    GL11.glVertex2f(left, top);
                    GL11.glVertex2f(left, top + h);
                    GL11.glVertex2f(left + w, top + h);
                    GL11.glVertex2f(left + w, top);
                    GL11.glEnd();
                }
            }

            GL20.glUseProgram(0);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            if (atWas) GL11.glEnable(GL11.GL_ALPHA_TEST);
            if (texWas) GL11.glEnable(GL11.GL_TEXTURE_2D);
            if (depthWas) GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (!blendWas) GL11.glDisable(GL11.GL_BLEND);
        } catch (Throwable t) {
            Log.error("KillEffect", "render failed", t);
        }
    }

    private static float st01(float v) {
        float f = v / 100f;
        return f < 0f ? 0f : (f > 1f ? 1f : f);
    }
}
