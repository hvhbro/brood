package utils.render;

import org.lwjgl.opengl.GL20;
import org.lwjglx.opengl.GL11;
import org.lwjglx.opengl.GL13;
import utils.etc.GameContext;
import utils.etc.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Иконки предметов поверх мира (GearESP): сэмплируем ИХ блок-атлас (уже
 * загружен и дешифрован игрой) нашим шейдером с uniform-UV — тот же
 * доказанный механизм, что у MSDF-шрифта (texcoord через lwjglx в шейдер
 * не доходит, 13.4.2; их RenderItem из overlay-фазы не рисует — батчер и
 * негатив-кэш моделей в RenderItemController).
 *
 * Цепочка v2 (09-09, источник = СВОИ иконки мода — как у дропнутых предметов):
 *   rustme/iIIiiilliI.iiIiIIIIIl(stack) → RL иконки (lllililIiI, null = нет)
 *   rustme/iIIiiilliI.iIIiIIIIIl(stack) → String skin (nullable)
 *   IliIlIIliI.iiIIiilll (синглтон) .IliIllIiIl(rl, skin) → иконка iliIlIIliI
 *   icon.lIlillIiIl() → int texId ОБЩЕГО атласа иконок (НЕ своя текстура —
 *     доказано 09-10: без uv рисовался весь атлас разом);
 *   uv-прямоугольник иконки = публичные final float-поля
 *     (iIiIiilll, lIiIiilll, iiiIiilll, liiIiilll) = (u0, v0, u1, v1)
 *     [ctor(int,u0,v0,u1,v1,cb); спрайт-лист: (f7, f8, f7+w/atlas, f8+h/atlas)]
 *   → рисуем квад с uniform uvRect = uv иконки.
 *   Прежняя попытка (спрайт из квадов запечённой модели) дала «летание»:
 *   particle/квадовые текстуры моделей — служебные и анимированные.
 *   ГАТЧ: если rl=null → иконки нет (это норма для пустого стека).
 *   Прежняя (v1, атласная) цепочка оставлена в комментарии ниже.
 *   v1: RenderItem.lllIIIiiI → mesher.iiIIliliII(stack) → model.iIilIIIIil()
 *   sprite UV (liliilliII): lIIiilliII()=minU, liiIilliII()=maxU,
 *   IiliilliII()=minV, lIliilliII()=maxV; mesher.iliIliliII() → mgr(llllilliiI)
 *   mgr.iliIiIiiII() → TextureMap (iiiIliiIiI), texId = iliiIlliil().
 *
 * Шейдер: quad c uniform uvRect (u0,v0,u1,v1), UV = mix по gl_FragCoord
 * внутри rect (физ. пиксели, как в MSDF). Прозрачные пиксели discard.
 */
public final class ItemIcons {

    private static final int GL_ACTIVE_TEXTURE = 0x84E0;
    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static final int GL_CURRENT_PROGRAM = 0x8B8D;

    private static ShaderUtil shader;
    private static boolean tried;
    private static boolean ok;
    private static boolean logged;

    // --- reflection-кэш цепочки v2 (иконки мода) ---
    private static Method iconRlM;          // iIIiiilliI.iiIiIIIIIl(stack) → RL
    private static Method iconSkinM;        // iIIiiilliI.iIIiIIIIIl(stack) → String
    private static Field iconSystemF;       // IliIlIIliI.iiIIiilll (синглтон)
    private static Method iconGetM;         // IliIlIIliI.IliIllIiIl(rl, skin) → icon
    private static Method iconTexIdM;       // icon.lIlillIiIl() → int texId (АТЛАСА!)
    private static Field iconU0F, iconV0F, iconU1F, iconV1F; // uv-прямоугольник иконки
    private static boolean chainResolved;
    private static boolean chainFailedLogged;
    // one-shot диагностика (каждая причина пишется один раз за сессию)
    private static boolean chainOkLogged, failRlLogged, failIconLogged, failTexIdLogged;
    private static boolean firstDrawLogged;

    /** stack identity → [texId, u0, v0, u1, v1] (texId=0 = иконки нет).
     *  Кэш по СТЕКУ: скины различаются NBT'ом. */
    private static final Map<Object, float[]> texCache = new HashMap<Object, float[]>();

    private ItemIcons() {}

    /** Резолв reflection-цепочки v2 (иконки мода, лениво). */
    private static boolean resolveChain(GameContext ctx) {
        if (chainResolved) return true;
        try {
            ClassLoader cl = ctx.gameLoader;
            Class stackC = cl.loadClass("rustme.liIIIIIIiI");
            Class helperC = cl.loadClass("rustme.iIIiiilliI");
            Class sysC = cl.loadClass("rustme.IliIlIIliI");
            Class rlC = cl.loadClass("rustme.lllililIiI");
            Class iconC = cl.loadClass("rustme.iliIlIIliI");

            iconRlM = helperC.getMethod("iiIiIIIIIl", stackC);
            iconSkinM = helperC.getMethod("iIIiIIIIIl", stackC);
            iconSystemF = sysC.getField("iiIIiilll");
            iconGetM = sysC.getMethod("IliIllIiIl", rlC, String.class);
            iconTexIdM = iconC.getMethod("lIlillIiIl");
            // UV-прямоугольник: ctor(texId, u0, v0, u1, v1, cb) — спрайт-лист
            // создаёт (f7, f8, f7+w/atlas, f8+h/atlas) → порядок (u0,v0,u1,v1);
            // lIlillIiIl() = texId ОБЩЕГО атласа, без uv рисуется весь атлас.
            iconU0F = iconC.getField("iIiIiilll");
            iconV0F = iconC.getField("lIiIiilll");
            iconU1F = iconC.getField("iiiIiilll");
            iconV1F = iconC.getField("liiIiilll");

            chainResolved = true;
            if (!chainOkLogged) {
                chainOkLogged = true;
                Log.info("ItemIcons", "chain v2 OK (иконки мода IliIlIIliI)");
            }
            return true;
        } catch (Throwable t) {
            if (!chainFailedLogged) {
                chainFailedLogged = true;
                Log.error("ItemIcons", "chain resolve failed", t);
            }
            return false;
        }
    }

    /** Лениво инициализирует шейдер. */
    private static boolean ensureShader() {
        if (tried) return ok;
        tried = true;
        try {
            shader = new ShaderUtil(Shaders.VERT, Shaders.ITEM_ICON);
            if (shader.programId() == 0) {
                shader = null;
                Log.error("ItemIcons", "shader program is 0", null);
                return false;
            }
            ok = true;
            Log.info("ItemIcons", "shader OK");
        } catch (Throwable t) {
            Log.error("ItemIcons", "shader init failed", t);
        }
        return ok;
    }

    /** texId иконки предмета (кэш по СТЕКУ: скины различаются NBT'ом).
     *  null = иконки нет. До готовности системы кэш не пишем. */
    private static float[] texForStack(GameContext ctx, Object stack) {
        try {
            if (!resolveChain(ctx)) return null;
            float[] cached = (float[]) texCache.get(stack);
            if (cached != null) return cached[0] <= 0 ? null : cached;
            Object rl = iconRlM.invoke(null, stack);
            if (rl == null) {
                if (!failRlLogged) {
                    failRlLogged = true;
                    Log.info("ItemIcons", "diag: rl null (стек без иконки)");
                }
                texCache.put(stack, new float[]{0f});
                return null;
            }
            Object skin = iconSkinM.invoke(null, stack);
            Object sys = iconSystemF.get(null);
            if (sys == null) return null;
            Object icon = iconGetM.invoke(sys, rl, skin);
            if (icon == null) {
                if (!failIconLogged) {
                    failIconLogged = true;
                    Log.info("ItemIcons", "diag: icon null (RL ещё не загружен системой?)");
                }
                return null; // не кэшируем: система могжет догрузить позже
            }
            int texId = ((Integer) iconTexIdM.invoke(icon)).intValue();
            if (texId <= 0) {
                if (!failTexIdLogged) {
                    failTexIdLogged = true;
                    Log.info("ItemIcons", "diag: icon texId<=0 (GL ещё не создан)");
                }
                return null; // не кэшируем: текстура ещё не создана
            }
            float[] entry = new float[]{
                texId,
                iconU0F.getFloat(icon), iconV0F.getFloat(icon),
                iconU1F.getFloat(icon), iconV1F.getFloat(icon)};
            texCache.put(stack, entry);
            return entry;
        } catch (Throwable t) {
            Log.error("ItemIcons", "texForStack failed", t);
            return null;
        }
    }

    /**
     * Рисует иконку предмета (16x16 база, size = итоговый scaled-размер).
     * Шейдер + uniform-UV + наш вызов glBindTexture — GlStateManager не участвует.
     */
    /**
     * Рисует иконку предмета (size = итоговый scaled-размер).
     * Источник — иконки мода (IliIlIIliI, своя текстура на предмет, UV полный).
     * Шейдер + наш glBindTexture — GlStateManager не участвует.
     */
    public static void drawItemIcon(GameContext ctx, Object stack, float x, float y, float size) {
        if (!ensureShader() || shader == null) return;
        float[] ico = texForStack(ctx, stack);
        if (ico == null) return;
        int texId = (int) ico[0];
        try {
            int prevProgram = GL11.glGetInteger(GL_CURRENT_PROGRAM);
            if (prevProgram != 0) GL20.glUseProgram(0);
            int prevUnit = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
            if (prevUnit != GL_TEXTURE0) GL13.glActiveTexture(GL_TEXTURE0);
            int prevTex = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);

            boolean prevAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            if (prevAlphaTest) GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            int fbH = ctx.fbHeight > 0 ? ctx.fbHeight : 720;
            float gs = ctx.guiScale > 0 ? ctx.guiScale : 1f;

            GL20.glUseProgram(shader.programId());
            GL20.glUniform1i(shader.uniform("atlas"), 0);
            shader.uniform4F("rect", x * gs, y * gs, size * gs, size * gs);
            // uv самой иконки в атласе (0..1 только у full-texture иконок)
            shader.uniform4F("uvRect", ico[1], ico[2], ico[3], ico[4]);
            shader.uniform4F("tint", 1f, 1f, 1f, 1f);
            // БЕЗ ЭТОГО ИКОНКИ НЕВИДИМЫ: шейдер переворачивает Y через
            // p.y = fbHeight - p.y; незалитый uniform = 0.0 → uv.y улетает
            // в отрицательные и сэмплируются прозрачные/мусорные пиксели.
            shader.uniformF("fbHeight", (float) fbH);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x, y + size);
            GL11.glVertex2f(x + size, y + size);
            GL11.glVertex2f(x + size, y);
            GL11.glEnd();

            if (!firstDrawLogged) {
                firstDrawLogged = true;
                Log.info("ItemIcons", "draw #1: texId=" + texId
                    + " xy=" + Math.round(x) + "," + Math.round(y) + " size=" + Math.round(size)
                    + " gs=" + gs + " fbH=" + fbH
                    + " uv=(" + ico[1] + "," + ico[2] + "," + ico[3] + "," + ico[4] + ")");
            }

            // restore
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            if (prevAlphaTest) GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            if (prevUnit != GL_TEXTURE0) GL13.glActiveTexture(prevUnit);
            GL20.glUseProgram(prevProgram);
        } catch (Throwable t) {
            Log.error("ItemIcons", "draw failed", t);
        }
    }

    /** Сброс кэша UV (смена мира/ресурсов). */
    public static void invalidate() {
        texCache.clear();
    }
}
