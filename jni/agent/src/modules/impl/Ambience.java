package modules.impl;

import modules.api.Module;
import org.lwjgl.opengl.GL20;
import org.lwjglx.opengl.GL11;
import utils.etc.GameContext;
import utils.etc.Log;
import utils.render.ShaderUtil;
import utils.render.Shaders;

/**
 * Ambience — тонировка БЛОКОВ мира цветом игрока, небо и рука чистые.
 *
 * СЕМАНТИКА: цвет — множитель. Белый = обычный мир; наполовину белый+
 * фиолетовый = слегка фиолетовые блоки. Интенсивность смешивает к белому.
 *
 * МЕХАНИКА (замена, БЕЗ пиксельных эвристик): CheatRenderGlobal (прокси
 * RenderGlobal в gs.Iillllil) перехватывает IIliIIliII(layer,D,I,Entity)I —
 * рендер 3 слоёв террейна (mc.llilllIiII @679/756/808, проверено дизасмом;
 * сущности @1276, рука и HUD — позже). После super-террейна рисуем плоский
 * TINT_FSH-квад multiply-блендом (ZERO, SRC_COLOR: dst*src) с тестом глубины
 * GREATER: проходит только записанная террейном глубина (<1.0); небо глубину
 * не пишет (=1.0) — мимо. Сущности/рука/облака/HUD рисуются позже поверх и
 * остаются чистыми. Никаких uScene/uBlur/tol и GlassGrab.
 */
public final class Ambience extends Module {

    private static Ambience INSTANCE;

    /** Цвет-множитель (белый = обычный мир). */
    public final Module.ColorSetting stColor = addColor("Color", 0xFFFFFFFF);
    /** Смешение к белому: 0 = нейтрально, 1 = полный цвет. */
    public final Module.FloatSetting stStrength = addSetting("Intensity", 0f, 1f, 0.01f, 1f);

    private ShaderUtil tintProgram;
    private boolean shaderFailed;
    private long lastErr;
    private long lastAudit;

    public Ambience() {
        super("Ambience", "Visuals");
        INSTANCE = this;
        stColor.picker = true;
        Log.info("Ambience", "registered");
    }

    /** Нужен ли хук (для CheatRenderGlobal.sync). */
    public static boolean wants() {
        Ambience i = INSTANCE;
        return i != null && i.isState();
    }

    /**
     * Тинт только что отрисованного слоя террейна. Вызывается из прокси
     * CheatRenderGlobal.IIliIIliII на рендер-потоке (GL-контекст текущий),
     * сразу после super — в глубине только террейн этого и прошлых слоёв.
     */
    public static void tintTerrain() {
        Ambience inst = INSTANCE;
        if (inst == null || !inst.isState()) return;
        GameContext ctx = GameContext.get();
        if (ctx.gs == null || !ctx.inWorld || ctx.player == null) return;
        try {
            int argb = inst.stColor.argb;
            float k = inst.stStrength.value;
            if (k < 0f) k = 0f;
            if (k > 1f) k = 1f;
            // lerp(white, color, k): 1-канал = нейтраль, цветной = tint
            float r = 1f - k + k * (((argb >> 16) & 0xFF) / 255f);
            float g = 1f - k + k * (((argb >> 8) & 0xFF) / 255f);
            float b = 1f - k + k * ((argb & 0xFF) / 255f);
            if (r >= 0.999f && g >= 0.999f && b >= 0.999f) return; // тинт нет

            ShaderUtil sh = inst.tintProgram();
            if (sh == null) return; // fail-fast: пустой шейдер зальёт экран мусором

            long now = System.currentTimeMillis();
            // аудит утечек: какое состояние оставили прошлые кадры (раз в 5с)
            if (now - inst.lastAudit > 5000L) {
                inst.lastAudit = now;
                Log.info("SkyAudit", "tint entry: " + rustme.CheatRenderGlobal.glState());
            }

            int prevProgram = GL11.glGetInteger(0x8B8D); // GL_CURRENT_PROGRAM
            int prevBlend = GL11.glGetInteger(0x0BE2);   // GL_BLEND
            int prevBlendSrc = GL11.glGetInteger(0x0BE1);
            int prevBlendDst = GL11.glGetInteger(0x0BE0);
            int prevDepthTest = GL11.glGetInteger(0x0B71); // GL_DEPTH_TEST
            int prevDepthFunc = GL11.glGetInteger(0x0B74 /*GL_DEPTH_FUNC*/);
            int prevDepthMask = GL11.glGetInteger(0x0B72 /*GL_DEPTH_WRITEMASK*/);

            GL11.glEnable(GL11.GL_BLEND);
            // multiply: out = dst * src (альфа dst сохраняется: src.a = 1)
            GL11.glBlendFunc(0x0 /*ZERO*/, 0x300 /*SRC_COLOR*/);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            // GREATER: входящая глубина квада = 1.0 (z=1) проходит только там,
            // где террейн записал глубину <1.0; небо (=1.0) мимо
            GL11.glDepthFunc(0x204 /*GREATER*/);
            GL11.glDepthMask(false); // глубину не портим

            try {
                sh.start();
                sh.uniform4F("color", r, g, b, 1f);

                GL11.glBegin(GL11.GL_TRIANGLES);
                GL11.glVertex3f(-1f, -1f, 1f);
                GL11.glVertex3f(3f, -1f, 1f);
                GL11.glVertex3f(-1f, 3f, 1f);
                GL11.glEnd();

                sh.stop();
            } finally {
                // restore ВСЕГДА (иначе любая ошибка = залипший multiply-бленд
                // и «пропавший текст» до конца сессии)
                GL20.glUseProgram(prevProgram);

                // вернуть как было (кэш состояния игры)
                GL11.glBlendFunc(prevBlendSrc != 0 ? prevBlendSrc : GL11.GL_SRC_ALPHA,
                    prevBlendDst != 0 ? prevBlendDst : GL11.GL_ONE_MINUS_SRC_ALPHA);
                if (prevBlend != 0) GL11.glEnable(GL11.GL_BLEND);
                else GL11.glDisable(GL11.GL_BLEND);
                if (prevDepthTest != 0) GL11.glEnable(GL11.GL_DEPTH_TEST);
                else GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(prevDepthFunc != 0 ? prevDepthFunc : 0x201 /*LESS*/);
                GL11.glDepthMask(prevDepthMask != 0);
                GL11.glColor4f(1f, 1f, 1f, 1f);
            }
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - inst.lastErr > 5000L) {
                inst.lastErr = now;
                Log.error("Ambience", "tint failed", t);
            }
        }
    }

    private ShaderUtil tintProgram() {
        if (tintProgram != null || shaderFailed) return shaderFailed ? null : tintProgram;
        try {
            tintProgram = new ShaderUtil(Shaders.SKY_VERT, Shaders.TINT_FSH);
            if (tintProgram.programId() == 0) {
                tintProgram = null;
                shaderFailed = true;
                Log.error("Ambience", "tint shader compile failed (fail-fast)", null);
            } else {
                Log.info("Ambience", "flat tint shader OK");
            }
        } catch (Throwable t) {
            shaderFailed = true;
            Log.error("Ambience", "tint shader init failed", t);
        }
        return shaderFailed ? null : tintProgram;
    }
}
