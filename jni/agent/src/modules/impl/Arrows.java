package modules.impl;

import modules.api.Module;
import org.lwjgl.opengl.GL20;
import org.lwjglx.opengl.GL11;
import rustme.IIlIIliIiI;
import utils.etc.GameContext;
import utils.etc.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Arrows — стрелочки-указатели на игроков вокруг прицела (текстура
 * assets/arrow.png, белый шеврон вверх с альфой).
 *
 * РЕНДЕР: overlay-фаза (CheatHud.renderFrame → render, после Tracers).
 * Для каждого игрока: проекция груди Esp.projectToScreen → угол от центра
 * экрана → позиция на кольце радиуса Radius → квад текстуры, повёрнутый
 * остриём к игроку (углы считаются вручную, без glRotate).
 * Игрок за камерой: угол переворачивается (стандарт offscreen-стрелок).
 *
 * Настройки: Color (пикер), Radius (удаление от прицела, px),
 * Size (размер стрелки, px).
 */
public final class Arrows extends Module {

    private static final double MAX_DIST = 128.0;
    private static final double TELEPORT_DELTA = 8.0;

    public final Module.ColorSetting stColor = addColor("Color", 0xFF906BFF);
    public final Module.FloatSetting stRadius = addSetting("Radius", 20f, 200f, 5f, 60f);
    public final Module.FloatSetting stSize = addSetting("Size", 8f, 32f, 1f, 16f);

    /** Синглтон для CheatHud. */
    public static Arrows INSTANCE;

    private static int arrowTex;
    private static boolean texTried;
    private static long lastErr;

    public Arrows() {
        super("Arrows", "Visuals");
        INSTANCE = this;
        stColor.picker = true;
        Log.info("Arrows", "registered (toggle: menu bind)");
    }

    /** Стрелки. Вызывается из CheatHud.renderFrame после Tracers.render. */
    public static void render(GameContext ctx, float partialTicks, int scaledW, int scaledH) {
        try {
            if (INSTANCE == null || !INSTANCE.isState()) return;
            if (!ctx.inWorld || ctx.world == null || ctx.player == null) return;
            if (!ensureTex()) return;

            Esp.updateCamera(ctx, partialTicks);
            if (!Esp.cameraReady()) return;

            float radius = INSTANCE.stRadius.value;
            if (radius < 10f) radius = 10f;
            if (radius > 400f) radius = 400f;
            float size = INSTANCE.stSize.value;
            if (size < 4f) size = 4f;
            if (size > 64f) size = 64f;
            int argb = INSTANCE.stColor.argb;

            List<?> players = (List<?>) ctx.playersField.get(ctx.world);
            if (players == null || players.isEmpty()) return;
            Object[] arr = players.toArray(new Object[0]);

            float cx = scaledW * 0.5f, cy = scaledH * 0.5f;

            // yaw камеры (во front-виде третьего лица — развёрнутый, как боксы ESP)
            float viewYaw = 0f;
            try {
                viewYaw = ((IIlIIliIiI) ctx.player).IIiIillIII();
                if (Esp.thirdPersonView() == 2) viewYaw += 180f;
            } catch (Throwable ignore) {}

            int prevProgram = GL11.glGetInteger(0x8B8D); // GL_CURRENT_PROGRAM
            boolean blendWas = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depthWas = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean texWas = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            boolean cullWas = GL11.glIsEnabled(0x0B44); // GL_CULL_FACE
            boolean alphaWas = GL11.glIsEnabled(0x0BC0); // GL_ALPHA_TEST
            boolean lightWas = GL11.glIsEnabled(0x0BA0); // GL_LIGHTING

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(0x0B44);
            GL11.glDisable(0x0BC0);
            GL11.glDisable(0x0BA0);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL20.glUseProgram(0); // fixed pipeline: texcoord из immediate mode
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, arrowTex);
            GL11.glColor4ub(
                (byte) ((argb >> 16) & 0xFF), (byte) ((argb >> 8) & 0xFF),
                (byte) (argb & 0xFF), (byte) ((argb >> 24) & 0xFF));

            GL11.glBegin(GL11.GL_QUADS);
            for (int i = 0; i < arr.length; i++) {
                Object el = arr[i];
                if (el == null || el == ctx.player) continue;
                if (ctx.localSpClass.isInstance(el)) continue;
                if (!(el instanceof IIlIIliIiI)) continue;
                IIlIIliIiI e = (IIlIIliIiI) el;

                // интерполяция XZ (bearing не зависит от pitch/Y —
                // стрелки стоят при наклоне камеры и видят спину на 360°)
                double px = e.IlIiillIII();
                double py = e.liiiIllIII();
                double pz = e.lIilillIII();
                double prevX = e.IlilillIII();
                double prevY = e.lIiiIllIII();
                double prevZ = e.IliIlIlIII();
                double rx = Math.abs(px - prevX) > TELEPORT_DELTA ? px : prevX + (px - prevX) * partialTicks;
                double ry = Math.abs(py - prevY) > TELEPORT_DELTA ? py : prevY + (py - prevY) * partialTicks;
                double rz = Math.abs(pz - prevZ) > TELEPORT_DELTA ? pz : prevZ + (pz - prevZ) * partialTicks;

                double ddx = rx - Esp.camX(), ddy = ry - Esp.camY(), ddz = rz - Esp.camZ();
                if (ddx * ddx + ddy * ddy + ddz * ddz > MAX_DIST * MAX_DIST) continue;

                // bearing цели в yaw-терминах минус yaw камеры → экранный угол
                // (0°=вправо, -90°=вверх). Позади камеры = внизу — без проекции.
                double worldYaw = Math.toDegrees(Math.atan2(-ddx, ddz));
                double ang = Math.toRadians(wrapDeg(worldYaw - (double) viewYaw) - 90.0);

                float ux = (float) Math.cos(ang), uy = (float) Math.sin(ang);
                float rxv = -uy, ryv = ux;
                float px0 = cx + ux * radius, py0 = cy + uy * radius;
                float h = size * 0.5f;
                // углы: P ± R*h ± U*h; v перевёрнута (PNG сверху вниз, tip на v=0)
                quad(
                    px0 - rxv * h - ux * h, py0 - ryv * h - uy * h, 0f, 1f,
                    px0 + rxv * h - ux * h, py0 + ryv * h - uy * h, 1f, 1f,
                    px0 + rxv * h + ux * h, py0 + ryv * h + uy * h, 1f, 0f,
                    px0 - rxv * h + ux * h, py0 - ryv * h + uy * h, 0f, 0f);
            }
            GL11.glEnd();

            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL20.glUseProgram(prevProgram);
            if (lightWas) GL11.glEnable(0x0BA0);
            if (alphaWas) GL11.glEnable(0x0BC0);
            if (cullWas) GL11.glEnable(0x0B44);
            if (!texWas) GL11.glDisable(GL11.GL_TEXTURE_2D);
            if (depthWas) GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (!blendWas) GL11.glDisable(GL11.GL_BLEND);
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastErr > 5000L) {
                lastErr = now;
                Log.error("Arrows", "render failed", t);
            }
        }
    }

    private static void quad(float x0, float y0, float u0, float v0,
                             float x1, float y1, float u1, float v1,
                             float x2, float y2, float u2, float v2,
                             float x3, float y3, float u3, float v3) {
        GL11.glTexCoord2f(u0, v0);
        GL11.glVertex2f(x0, y0);
        GL11.glTexCoord2f(u1, v1);
        GL11.glVertex2f(x1, y1);
        GL11.glTexCoord2f(u2, v2);
        GL11.glVertex2f(x2, y2);
        GL11.glTexCoord2f(u3, v3);
        GL11.glVertex2f(x3, y3);
    }

    private static double wrapDeg(double d) {
        while (d > 180.0) d -= 360.0;
        while (d < -180.0) d += 360.0;
        return d;
    }

    /** Ленивая загрузка arrow.png → GL-текстура (главный поток). */
    private static boolean ensureTex() {
        if (arrowTex != 0) return true;
        if (texTried) return false;
        texTried = true;
        try {
            byte[] png = utils.etc.AssetData.get("arrow");
            if (png == null) {
                Log.error("Arrows", "arrow asset missing", null);
                return false;
            }
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(png));
            if (img == null) {
                Log.error("Arrows", "png decode failed", null);
                return false;
            }
            int w = img.getWidth(), h = img.getHeight();
            int[] px = new int[w * h];
            img.getRGB(0, 0, w, h, px, 0, w);
            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4)
                .order(ByteOrder.nativeOrder());
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                buf.put((byte) ((p >> 16) & 0xFF));
                buf.put((byte) ((p >> 8) & 0xFF));
                buf.put((byte) (p & 0xFF));
                buf.put((byte) ((p >> 24) & 0xFF));
            }
            buf.flip();
            int t = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            arrowTex = t;
            Log.info("Arrows", "texture OK " + w + "x" + h + " tex=" + t);
            return true;
        } catch (Throwable t) {
            Log.error("Arrows", "texture init failed", t);
            return false;
        }
    }
}
