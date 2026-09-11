package rustme;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Полностью прозрачная 1×1 текстура (AntiOverlay: подмена масок).
 * Тот же контракт, что SvoTexture (implements iiIiliiIiI, TextureObject):
 * регистрация через TextureManager.llIilliII(rl, tex) — он зовёт
 * loadTexture на главном потоке, там грузим GL-текстуру с alpha=0.
 */
public class TransparentTexture implements iiIiliiIiI {

    private int texId = -1;
    private boolean failed;

    private void upload() {
        if (failed) return;
        try {
            ByteBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            buf.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0); // RGBA = 0,0,0,0
            buf.flip();
            if (texId < 0) texId = org.lwjglx.opengl.GL11.glGenTextures();
            int prevTex = org.lwjglx.opengl.GL11.glGetInteger(0x8069);     // GL_TEXTURE_BINDING_2D
            int prevProgram = org.lwjglx.opengl.GL11.glGetInteger(0x8B8D); // GL_CURRENT_PROGRAM
            if (prevProgram != 0) org.lwjgl.opengl.GL20.glUseProgram(0);
            org.lwjglx.opengl.GL11.glBindTexture(0x0DE1, texId);
            org.lwjglx.opengl.GL11.glTexParameteri(0x0DE1, 0x2801, 0x2600); // MIN_FILTER NEAREST
            org.lwjglx.opengl.GL11.glTexParameteri(0x0DE1, 0x2800, 0x2600); // MAG_FILTER NEAREST
            org.lwjglx.opengl.GL11.glTexParameteri(0x0DE1, 0x2802, 0x2900); // WRAP_S CLAMP
            org.lwjglx.opengl.GL11.glTexParameteri(0x0DE1, 0x2803, 0x2900); // WRAP_T CLAMP
            org.lwjglx.opengl.GL11.glTexImage2D(0x0DE1, 0, 0x1908, 1, 1, 0,
                0x1908, 0x1401, buf);
            org.lwjglx.opengl.GL11.glBindTexture(0x0DE1, prevTex);
            if (prevProgram != 0) org.lwjgl.opengl.GL20.glUseProgram(prevProgram);
        } catch (Throwable t) {
            failed = true;
            utils.etc.Log.error("TransparentTexture", "upload failed", t);
        }
    }

    @Override
    public void iIlIllliil(iIIiiIiIiI resourceManager) {
        if (!failed) upload();
    }

    @Override
    public int iliiIlliil() {
        return texId;
    }

    @Override
    public void IliiIlliil(boolean animate, boolean interpolate) {
    }

    @Override
    public void lIiiIlliil() {
    }
}
