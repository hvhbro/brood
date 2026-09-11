package modules.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import org.lwjglx.opengl.GL11;
import rustme.IIlIIliIiI;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.CustomFont;
import utils.render.RenderUtil;

/**
 * SoundESP — метки звуков мира с именем, кол-вом и дистанцией.
 *
 * НАШ ПУТЬ v2 (диаг 09-10): payload 'rust:sound:play' через шину НЕ ХОДИТ
 * (diag: только safe/upkeep/swing) — сервер не рассылает звуки пейлоадами.
 * Звуки играются через ванильный SoundManager — поллим его playingSounds
 * (LinkedHashMap, дифф по ключу) с агентного потока (1мс).
 *
 * Цепочка (декомп, верифицировано):
 *   gs.iiliIilliI() → SoundEngine (IIiililiiI)
 *   SoundEngine.iIliiiil → SoundManager (IililIiliI)
 *   SoundManager.IiliIliIl → LinkedHashMap<String, SoundInstance> playingSounds
 *   SoundInstance.IliiIliIl → ISound (IIIlIiliiI):
 *     llIilIiliI() → ResourceLocation → getName() = путь звука
 *     lIIilIiliI()/lIlilIiliI()/iililIiliI() → X/Y/Z
 *     (маппинг X/Y/Z верифицирован по ctor positioned-звука: fload 8/9/10 →
 *      putfield iIlllllI/IilllllI/IIlllllI, а геттеры читают ровно их)
 *
 * Классификация 1:1 из дизасма конкурента (категоризатор), рендер как у
 * Tracers: Esp.projectToScreen, плашка "%s x%d (%.0fm)", цвет по типу.
 */
public final class SoundEsp extends Module {

    private static SoundEsp INSTANCE;

    private final Module.FloatSetting stMaxDist = addSetting("Дистанция", 20f, 200f, 5f, 100f);
    private final Module.FloatSetting stLife = addSetting("Время метки", 1f, 10f, 0.5f, 3f);
    private final Module.FloatSetting stMerge = addSetting("Слияние", 1f, 10f, 0.5f, 3f);

    /** Одна звуковая метка (кластер). */
    public static final class SoundMark {
        public double x, y, z;
        public String label;
        public int count;
        public long firstSeen, lastSeen;
        public int color;
    }

    private static final List<SoundMark> marks = new ArrayList<SoundMark>();

    // --- звуковая цепочка (ленивый резолв) ---
    private Method sndEngineGetter;   // gs.iiliIilliI() → SoundEngine
    private Field sndMgrField;        // SoundEngine.iIliiiil → SoundManager
    private Field playingMapField;    // SoundManager.IiliIliIl → LinkedHashMap
    private Field wrapperSoundField;  // SoundInstance.IliiIliIl → ISound
    private Method resNameGetter;     // lllililIiI.lIilllillI() → путь звука
    private boolean sndResolved;
    // геттеры звука резолвим по РАНТАЙМ-классу объекта (имя интерфейса — ловушка регистра)
    private final java.util.HashMap<String, Method[]> sndMethodCache = new java.util.HashMap<String, Method[]>();
    private long lastSndResolve;
    private Object soundEngine;
    private Object soundManager;
    private Object playingSounds;
    private final java.util.HashSet<Object> seenKeys = new java.util.HashSet<Object>();

    private long lastLog;
    private long lastParseLog;

    // ===== СЛОВАРЬ конкурента (1:1 из дизасма категоризатора) =====

    /** Weapon map (TABLE A 0x2d6c20): подстрока → отображение. Проверяется В ПОРЯДКЕ. */
    private static final String[][] WEAPON_MAP = {
        {"ak47", "AK47"}, {"m4a1", "M4A1"}, {"lr300", "LR300"}, {"l96", "L96"},
        {"mp5", "MP5"}, {"m39", "M39"}, {"m249", "M249"}, {"spas12", "SPAS12"},
        {"c4", "C4"}, {"rpg", "RPG"}, {"f1", "F1"}, {"smg", "SMG"},
        {"sar", "SAR"}, {"db", "DB"}, {"dmr", "DMR"},
    };

    /**
     * Категоризатор (0x7ffa193e1e50..0x4100, 1:1): ключевые группы в порядке
     * проверок. display=="" → скрыть.
     */
    private static final String[][] CATEGORY_CHAIN = {
        {"step.", "Footsteps"}, {"footstep", "Footsteps"},
        {"timed_explosive", "C4"}, {"timed.explosive", "C4"}, {"timedexplosive", "C4"},
        {"explosive_rifle", "Explosive Ammo"}, {"explosive.rifle", "Explosive Ammo"},
        {"explosive_bullet", "Explosive Ammo"}, {"explosive.bullet", "Explosive Ammo"},
        {"rifle.explosive", "Explosive Ammo"},
        {"incendiary_rifle", "Incendiary Ammo"}, {"incendiary.rifle", "Incendiary Ammo"},
        {"incendiary_bullet", "Incendiary Ammo"}, {"incendiary.bullet", "Incendiary Ammo"},
        {"rifle.incendiary", "Incendiary Ammo"},
        {"satchel", "Satchel"}, {"beancan", "Beancan"},
        {"f1_grenade", "F1 Grenade"}, {"f1.grenade", "F1 Grenade"}, {"f1", "F1 Grenade"},
        {"he_grenade", "HE Grenade"}, {"he.grenade", "HE Grenade"},
        {"supply_drop", "Airdrop"}, {"supplydrop", "Airdrop"}, {"airdrop", "Airdrop"},
        {"supply.drop", "Airdrop"},
        {"helicopter", "Helicopter"}, {"patrol_helicopter", "Helicopter"},
        {"patrolhelicopter", "Helicopter"}, {"minicopter", "Helicopter"},
        {"copter", "Helicopter"}, {"heli", "Helicopter"},
        {"bradley", "Bradley"},
        {"autoturret", "Auto Turret"}, {"auto.turret", "Auto Turret"},
        {"flame_turret", "Flame Turret"}, {"flameturret", "Flame Turret"},
        {"flame.turret", "Flame Turret"},
        {"samsite", "SAM Site"},
        {"landmine", "Landmine"}, {"mine.detonate", "Landmine"},
        {"cargo_ship", "Cargo Ship"}, {"cargoship", "Cargo Ship"},
        {"cargo.ship", "Cargo Ship"},
        {"chinook", "Chinook"}, {"ch47", "Chinook"},
        {"scrap_transport", "Scrap Heli"}, {"scraptransport", "Scrap Heli"},
        {"scrap.transport", "Scrap Heli"},
        {"assault.rifle", "Assault Rifle"},
        {"bolt_action", "Bolt Action"}, {"bolt.action", "Bolt Action"},
        {"semi.rifle", "Semi Rifle"}, {"semiauto_rifle", "Semi Rifle"},
        {"semi.pistol", "Semi Pistol"}, {"semiauto_pistol", "Semi Pistol"},
        {"double_shotgun", "Double Barrel"}, {"double.shotgun", "Double Barrel"},
        {"pump.shotgun", "Pump Shotgun"},
        {"waterpipe", "Waterpipe"},
        {"custom.smg", "Custom SMG"},
        {"rocket.launcher", "Rocket Launcher"},
        {"grenade.launcher", "Grenade Launcher"},
        {"compound.bow", "Compound Bow"},
        {"hunting_bow", "Bow"}, {"hunting.bow", "Bow"},
        {"flamethrower", "Flamethrower"},
        {"eoka", "Eoka"}, {"nailgun", "Nailgun"},
        {"python", "Python"}, {"revolver", "Revolver"}, {"thompson", "Thompson"},
    };

    private static final int COL_COMBAT = 0xFFFF8A3C;
    private static final int COL_VEHICLE = 0xFF54E0FF;
    private static final int COL_LOOT = 0xFFFFE14D;

    private final StringBuilder sb = new StringBuilder(64);

    public SoundEsp() {
        super("SoundEsp", "Visuals");
        INSTANCE = this;
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    tick(event); // поллим всегда: Hitmarker ждёт hit-звуки даже при выключенном SoundEsp
                } catch (Throwable t) {
                    Log.error("SoundEsp", "tick exception", t);
                }
            }
        });
        Log.info("SoundEsp", "registered (toggle: menu bind)");
    }

    @Override
    protected void onDisable() {
        synchronized (marks) { marks.clear(); }
    }

    private void tick(TickEvent event) {
        GameContext ctx = GameContext.get();
        if (ctx.mc == null || ctx.gs == null) return;
        if (!sndResolved) resolveSoundChain(ctx);
        if (!sndResolved) return;
        // при выключенном SoundEsp поллим реже (50мс) — 1мс-рефлексия не нужна,
        // но hit-звуки для Hitmarker должны ловиться всегда
        long nowMs = System.currentTimeMillis();
        if (!isState() && nowMs - lastPoll < 50L) return;
        lastPoll = nowMs;
        pollSounds(ctx);
        if (!isState()) return;
        long now = System.currentTimeMillis();
        long life = (long) (stLife.value * 1000f);
        synchronized (marks) {
            Iterator<SoundMark> it = marks.iterator();
            while (it.hasNext()) {
                SoundMark m = it.next();
                if (now - m.lastSeen > life) it.remove();
            }
        }
    }

    // ===== Hit-звуки (серверное подтверждение попаданий → Hitmarker) =====

    /** Слушатель hurt/hit-звуков (позиция = жертва). */
    public interface HitSoundListener {
        void onHitSound(String name, float x, float y, float z);
    }

    private static final ArrayList<HitSoundListener> hitListeners = new ArrayList<HitSoundListener>();
    private long lastPoll;

    public static void addHitSoundListener(HitSoundListener l) {
        synchronized (hitListeners) {
            if (!hitListeners.contains(l)) hitListeners.add(l);
        }
    }

    /** Потенциальные звуки попадания (форк ванильный по звукам: hurt/flesh). */
    private static boolean isHitSound(String name) {
        if (name == null) return false;
        String s = name.toLowerCase();
        return s.contains("hurt") || s.contains("hit") || s.contains("flesh") || s.contains("damage");
    }

    private void notifyHitListeners(String name, float x, float y, float z) {
        ArrayList<HitSoundListener> copy;
        synchronized (hitListeners) {
            if (hitListeners.isEmpty()) return;
            copy = new ArrayList<HitSoundListener>(hitListeners);
        }
        for (int i = 0; i < copy.size(); i++) {
            try {
                copy.get(i).onHitSound(name, x, y, z);
            } catch (Throwable ignore) {}
        }
    }

    // ===== Резолв звуковой цепочки =====

    private boolean resolveSoundChain(GameContext ctx) {
        long now = System.currentTimeMillis();
        if (now - lastSndResolve < 2000L) return false;
        lastSndResolve = now;
        try {
            sndEngineGetter = ctx.gs.getClass().getMethod("iiliIilliI");
            Class engineC = ctx.gameLoader.loadClass("rustme.IIiililiiI");
            sndMgrField = engineC.getField("iIliiiil");
            Class mgrC = ctx.gameLoader.loadClass("rustme.IililIiliI");
            playingMapField = mgrC.getField("IiliIliIl");
            Class wrapperC = ctx.gameLoader.loadClass("rustme.llIilIiliI");
            wrapperSoundField = wrapperC.getField("IliiIliIl");
            // ResourceLocation = lllililIiI (НЕ wrapper llIilIiliI!):
            // lIilllillI() → path (iIIlIlIlI), ililllillI() → domain.
            // Баг 09-10: resNameGetter резолвился от wrapper'а → not an instance.
            Class resLocC = ctx.gameLoader.loadClass("rustme.lllililIiI");
            resNameGetter = resLocC.getMethod("lIilllillI");
            sndResolved = true;
            Log.info("SoundEsp", "sound chain resolved (Engine→Manager→playingSounds)");
            return true;
        } catch (Throwable t) {
            Log.error("SoundEsp", "sound chain resolve failed", t);
            return false;
        }
    }

    // ===== Поллинг playingSounds (дифф по ключу) =====

    private void pollSounds(GameContext ctx) {
        try {
            if (soundEngine == null) {
                soundEngine = sndEngineGetter.invoke(ctx.gs);
                if (soundEngine == null) return;
                soundManager = null;
            }
            if (soundManager == null) {
                soundManager = sndMgrField.get(soundEngine);
                if (soundManager == null) return;
                playingSounds = playingMapField.get(soundManager);
                if (playingSounds == null) return;
            }
            java.util.Map map = (java.util.Map) playingSounds;
            Object[] arr;
            try {
                arr = map.entrySet().toArray();
            } catch (Throwable cme) {
                return; // звуковой поток мутирует мапу — пропускаем тик
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
                float x = (Float) sms[1].invoke(isound);
                float y = (Float) sms[2].invoke(isound);
                float z = (Float) sms[3].invoke(isound);
                if (name == null) continue;
                if (isHitSound(name)) {
                    notifyHitListeners(name, x, y, z);
                    diagParse("hit-sound: " + name + " @(" + (int) x + "," + (int) y + "," + (int) z + ")");
                }
                if (isState()) classifyAndAdd(name, x, y, z);
            }
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastLog > 5000L) {
                lastLog = now;
                Log.error("SoundEsp", "poll failed", t);
            }
        }
    }

    /** Геттеры {loc, x, y, z} по рантайм-классу (кэш); null = не звуковой объект. */
    private Method[] methodsFor(Class c) {
        String key = c.getName();
        Method[] cached = sndMethodCache.get(key);
        if (cached != null) return cached.length == 0 ? null : cached;
        try {
            Method[] ms = new Method[]{
                c.getMethod("llIilIiliI"),
                c.getMethod("lIIilIiliI"),
                c.getMethod("lIlilIiliI"),
                c.getMethod("iililIiliI"),
            };
            sndMethodCache.put(key, ms);
            return ms;
        } catch (Throwable t) {
            sndMethodCache.put(key, new Method[0]);
            long now = System.currentTimeMillis();
            if (now - lastLog > 3000L) {
                lastLog = now;
                Log.info("SoundEsp", "non-sound entry class: " + key);
            }
            return null;
        }
    }

    private void diagParse(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastParseLog > 1000L) {
            lastParseLog = now;
            Log.info("SoundEsp", msg);
        }
    }

    // ===== Категоризация (1:1 их категоризатор) =====

    /** Отображение по имени звука; null = скрыть. */
    public static String classify(String sound) {
        if (sound == null) return null;
        String s = sound.toLowerCase().replace('_', '.');
        for (int i = 0; i < CATEGORY_CHAIN.length; i++) {
            if (s.contains(CATEGORY_CHAIN[i][0])) {
                return CATEGORY_CHAIN[i][1].isEmpty() ? null : CATEGORY_CHAIN[i][1];
            }
        }
        for (int i = 0; i < WEAPON_MAP.length; i++) {
            if (s.contains(WEAPON_MAP[i][0])) return WEAPON_MAP[i][1];
        }
        return null;
    }

    private void classifyAndAdd(String entryId, float x, float y, float z) {
        String label = classify(entryId);
        if (label == null) return;

        long now = System.currentTimeMillis();
        float merge = stMerge.value;
        double mergeSq = merge * merge;
        int color = colorFor(label);

        synchronized (marks) {
            SoundMark best = null;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < marks.size(); i++) {
                SoundMark m = marks.get(i);
                if (!m.label.equals(label)) continue;
                double dx = m.x - x, dy = m.y - y, dz = m.z - z;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < mergeSq && d < bestD) { bestD = d; best = m; }
            }
            if (best != null) {
                best.lastSeen = now;
                best.count++;
                best.x = x; best.y = y; best.z = z;
                best.color = color;
            } else {
                SoundMark m = new SoundMark();
                m.x = x; m.y = y; m.z = z;
                m.label = label;
                m.count = 1;
                m.firstSeen = now;
                m.lastSeen = now;
                m.color = color;
                marks.add(m);
                if (marks.size() > 64) marks.remove(0);
            }
        }
    }

    private static int colorFor(String label) {
        if (label.equals("Airdrop") || label.equals("Chinook") || label.equals("Helicopter")
                || label.equals("Cargo Ship") || label.equals("Bradley")
                || label.equals("Scrap Heli") || label.equals("SAM Site")) {
            return COL_VEHICLE;
        }
        if (label.equals("Landmine") || label.equals("Auto Turret")
                || label.equals("Flame Turret")) {
            return COL_LOOT;
        }
        return COL_COMBAT;
    }

    // ===== Рендер (главный поток, из CheatHud) =====

    private static final float[] PROJ = new float[2];

    public static void render(GameContext ctx, float partialTicks, int scaledW, int scaledH) {
        SoundEsp inst = INSTANCE;
        if (inst == null || !inst.isState()) return;
        synchronized (marks) {
            if (marks.isEmpty()) return;
        }
        try {
            Esp.updateCamera(ctx, partialTicks);
            if (!Esp.cameraReady()) return;
            IIlIIliIiI me = (IIlIIliIiI) ctx.player;
            if (me == null) return;
            double mx = me.IlIiillIII(), my = me.liiiIllIII(), mz = me.lIilillIII();
            double maxDist = inst.stMaxDist.value;
            float maxDistSq = (float) (maxDist * maxDist);

            boolean blendWas = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depthWas = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean texWas = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);

            long now = System.currentTimeMillis();
            long life = (long) (inst.stLife.value * 1000f);
            List<SoundMark> list;
            synchronized (marks) { list = new ArrayList<SoundMark>(marks); }
            for (int i = 0; i < list.size(); i++) {
                SoundMark m = list.get(i);
                double dx = m.x - mx, dy = m.y - my, dz = m.z - mz;
                float distSq = (float) (dx * dx + dy * dy + dz * dz);
                if (distSq > maxDistSq) continue;
                double cw = Esp.projectToScreen(m.x, m.y, m.z, scaledW, scaledH, PROJ);
                if (cw <= 0.0) continue;

                float alpha = 1f;
                long age = now - m.lastSeen;
                float fade = (life - age) / 700f;
                if (fade < 1f) alpha = Math.max(0f, fade);
                int a = (int) (alpha * 255f);
                int color = (m.color & 0x00FFFFFF) | (a << 24);
                int bgA = (int) (alpha * 160f);

                String text = inst.formatMark(m, Math.sqrt(distSq));
                float textW = CustomFont.getWidth(text, 7f);
                float textH = CustomFont.cellHeight(7f);
                float padX = 3f, padY = 2f;
                float bw = textW + padX * 2f;
                float bh = textH + padY * 2f;
                float bx = PROJ[0] - bw / 2f;
                float by = PROJ[1] - bh;

                GL11.glColor4f((m.color >> 16 & 0xFF) / 255f, (m.color >> 8 & 0xFF) / 255f,
                    (m.color & 0xFF) / 255f, a / 255f);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(PROJ[0] - 1.5f, PROJ[1] - 1.5f);
                GL11.glVertex2f(PROJ[0] + 1.5f, PROJ[1] - 1.5f);
                GL11.glVertex2f(PROJ[0] + 1.5f, PROJ[1] + 1.5f);
                GL11.glVertex2f(PROJ[0] - 1.5f, PROJ[1] + 1.5f);
                GL11.glEnd();

                RenderUtil.drawRoundedRect(ctx, bx, by, bw, bh, 2f,
                    (bgA << 24) | 0x0C0C12);
                CustomFont.drawString(text, bx + padX, by + padY, color, false, 7f);
            }

            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            if (depthWas) GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (!blendWas) GL11.glDisable(GL11.GL_BLEND);
        } catch (Throwable t) {
            Log.error("SoundEsp", "render failed", t);
        }
    }

    private String formatMark(SoundMark m, double dist) {
        sb.setLength(0);
        sb.append(m.label);
        if (m.count > 1) sb.append(" x").append(m.count);
        sb.append(" (").append((int) Math.round(dist)).append("m)");
        return sb.toString();
    }
}
