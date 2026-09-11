package modules.api;

import events.EventBus;

import java.util.ArrayList;
import java.util.List;

/**
 * Базовый класс модуля (expensive-стиль): name, state, toggle.
 * Наследники переопределяют onEnable/onDisable/onTick.
 */
public abstract class Module {
    public final String name;
    /** Категория для меню ("Combat", "Visuals"...). */
    public final String category;
    /** Клавиша тоггла «из кода» (агент-слой); меню её не трогает. */
    public final int toggleKey;
    /** Клавиша, назначенная в меню (GLFW); тогглит модуль в тике. -1 = нет. */
    public int bindKey = -1;
    private boolean bindWasDown;
    private boolean state;

    // ================= НАСТРОЙКИ МОДУЛЯ =================
    // Элементы меню (rock Class215/"modern", createComponent 1:1):
    //   Section  — заголовок секции (текст 9px, pad 10/5);
    //   Bool     — чекбокс: ряд h18, pill 13x8 r3.5, ON=акцент OFF=flat@a102,
    //              knob 5px (inset 1.5), сигнат-анимация 300мс;
    //   Float    — слайдер: track h3, knob r3 (кольцо 1.5px + тёмный центр);
    //   Mode     — чипы-режимы (wrap, gap 2, r2.5, pad 3), выбран = акцент;
    //   Multi    — те же чипы, но тогглятся каждый (мультибокс);
    //   Color    — ряд цветовых свотчей 11x11 r3.
    // Bool/Mode наследуют FloatSetting (value 0..N) — код модулей читает value.

    /** База настройки: имя + условия видимости (kimiko visibleWhen). */
    public static class Setting {
        public final String name;
        private final ArrayList conds = new ArrayList();

        public Setting(String name) { this.name = name; }

        /** Условие видимости: настройка видна, пока бул s в состоянии v. */
        @SuppressWarnings("unchecked")
        public <T extends Setting> T visibleWhen(BoolSetting s, boolean v) {
            conds.add(new CondBool(s, v));
            return (T) this;
        }

        /** Условие видимости: настройка видна, пока режим s стоит на позиции idx. */
        @SuppressWarnings("unchecked")
        public <T extends Setting> T visibleWhen(ModeSetting s, int idx) {
            conds.add(new CondMode(s, idx));
            return (T) this;
        }

        public boolean isVisible() {
            for (int i = 0; i < conds.size(); i++) {
                Object c = conds.get(i);
                if (c instanceof CondBool) {
                    CondBool cb = (CondBool) c;
                    if (cb.s.value >= 0.5f != cb.v) return false;
                } else {
                    CondMode cm = (CondMode) c;
                    if (cm.s.index() != cm.idx) return false;
                }
            }
            return true;
        }

        private static final class CondBool {
            final BoolSetting s;
            final boolean v;
            CondBool(BoolSetting s, boolean v) { this.s = s; this.v = v; }
        }

        private static final class CondMode {
            final ModeSetting s;
            final int idx;
            CondMode(ModeSetting s, int idx) { this.s = s; this.idx = idx; }
        }
    }

    /** Флоат-настройка (слайдер). */
    public static class FloatSetting extends Setting {
        public final float min, max, step;
        public float value;
        public FloatSetting(String name, float min, float max, float step, float def) {
            super(name);
            this.min = min; this.max = max; this.step = step;
            this.value = Math.max(min, Math.min(max, def));
        }
    }

    /** Чекбокс (0/1 в value — модуль может читать как раньше). */
    public static class BoolSetting extends FloatSetting {
        public BoolSetting(String name, boolean def) {
            super(name, 0f, 1f, 1f, def ? 1f : 0f);
        }
        public boolean get() { return value >= 0.5f; }
        public void set(boolean v) { value = v ? 1f : 0f; }
    }

    /** Режим (набор строковых опций, value = индекс). */
    public static class ModeSetting extends FloatSetting {
        public final String[] modes;
        public ModeSetting(String name, String[] modes, int def) {
            super(name, 0f, Math.max(0, modes.length - 1), 1f, def);
            this.modes = modes;
        }
        public int index() {
            int i = Math.round(value);
            if (i < 0) i = 0;
            if (i >= modes.length) i = modes.length - 1;
            return i;
        }
        public String get() { return modes[index()]; }
        public boolean is(int i) { return index() == i; }
    }

    /** Мультибокс: набор опций, каждая тогглится независимо. */
    public static class MultiSetting extends Setting {
        public final String[] options;
        public final boolean[] selected;
        public MultiSetting(String name, String[] options, int[] defaults) {
            super(name);
            this.options = options;
            this.selected = new boolean[options.length];
            for (int d = 0; defaults != null && d < defaults.length; d++) {
                if (defaults[d] >= 0 && defaults[d] < selected.length) {
                    selected[defaults[d]] = true;
                }
            }
        }
        public boolean isSelected(int i) { return selected[i]; }
        public void toggle(int i) { selected[i] = !selected[i]; }
        public int count() {
            int n = 0;
            for (int i = 0; i < selected.length; i++) if (selected[i]) n++;
            return n;
        }
    }

    /** Заголовок секции настроек. */
    public static class SectionSetting extends Setting {
        public SectionSetting(String name) { super(name); }
    }

    /** Цвет (ARGB): выбор из фиксированной палитры свотчей. */
    public static class ColorSetting extends Setting {
        public int argb;
        /** true = в меню открывать ColorPicker (rockstar) вместо свотчей. */
        public boolean picker;
        public ColorSetting(String name, int def) {
            super(name);
            this.argb = def;
        }
    }

    /** Палитра свотчей для ColorSetting (kimiko-цвета + наши + базовые). */
    public static final int[] COLOR_SWATCHES = {
        0xFFFFFFFF, 0xFF18151D, 0xFF906BFF, 0xFF4D00FF,
        0xFF7FF2FF, 0xFFFF3296, 0xFFFFE14D, 0xFF4DFF6E,
        0xFFFF5040, 0xFFFF8B26, 0xFF35C2FF, 0xFF00D48A,
    };

    private final List settings = new ArrayList();

    /** Добавляет слайдер-настройку. Вызывать из конструктора наследника. */
    protected FloatSetting addSetting(String name, float min, float max, float step, float def) {
        FloatSetting fs = new FloatSetting(name, min, max, step, def);
        settings.add(fs);
        return fs;
    }

    protected BoolSetting addBool(String name, boolean def) {
        BoolSetting s = new BoolSetting(name, def);
        settings.add(s);
        return s;
    }

    protected ModeSetting addMode(String name, String[] modes, int def) {
        ModeSetting s = new ModeSetting(name, modes, def);
        settings.add(s);
        return s;
    }

    protected MultiSetting addMulti(String name, String[] options, int[] defaults) {
        MultiSetting s = new MultiSetting(name, options, defaults);
        settings.add(s);
        return s;
    }

    protected ColorSetting addColor(String name, int def) {
        ColorSetting s = new ColorSetting(name, def);
        settings.add(s);
        return s;
    }

    protected SectionSetting addSection(String name) {
        SectionSetting s = new SectionSetting(name);
        settings.add(s);
        return s;
    }

    /** Настройки модуля (пусто = меню рисует «No settings»). */
    public List settings() { return settings; }

    protected Module(String name) {
        this(name, "Misc", -1);
    }

    /** Модуль без дефолтного бинда — клавиша назначается в меню (bindKey). */
    protected Module(String name, String category) {
        this(name, category, -1);
    }

    protected Module(String name, String category, int key) {
        this.name = name;
        this.category = category;
        this.toggleKey = key;
        Modules.register(this);
    }

    /** Тик бинда из меню (вызывается из главного цикла агента). */
    public void tickBind(utils.etc.GameContext ctx, boolean menuOpen) {
        if (bindKey < 0) return; // -1 = нет бинда; 0..7 = мышь, 32+ = клавиатура
        try {
            boolean down = bindKey <= 7
                ? ctx.isMouseButtonDown(bindKey)
                : ctx.isKeyDown(bindKey);
            // меню открыто: клавиатура принадлежит GUI (поиск/бинд-захват) —
            // держим edge-детект «прижатым», тоггл только вне меню.
            if (menuOpen) {
                bindWasDown = down;
                // diag: раз в 3с показываем, что бинд жив
                long now = System.currentTimeMillis();
                if (now - bindDiag >= 3000L) {
                    bindDiag = now;
                    utils.etc.Log.info(name, "bind diag: key=" + bindKey
                        + " menuOpen=true down=" + down);
                }
                return;
            }
            if (down && !bindWasDown) {
                toggle();
                utils.etc.Log.info(name, "bind -> " + (isState() ? "ON" : "OFF"));
            }
            bindWasDown = down;
            long now2 = System.currentTimeMillis();
            if (now2 - bindDiag >= 3000L) {
                bindDiag = now2;
                utils.etc.Log.info(name, "bind diag: key=" + bindKey
                    + " menuOpen=false down=" + down);
            }
        } catch (Throwable ignore) {}
    }

    private long bindDiag;

    public boolean isState() { return state; }

    public void toggle() { setState(!state); }

    public void setState(boolean v) {
        if (state == v) return;
        state = v;
        Modules.refresh();
        if (v) onEnable(); else onDisable();
    }

    protected void onEnable() {}
    protected void onDisable() {}

    /** Тик модуля (вызывается из главного цикла, если включен). */
    public void onTick(events.EventBus.TickEvent event) {}
}
