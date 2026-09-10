package org.lwjgl.opengl;

/**
 * КОМПИЛЯЦИОННЫЙ СТАБ (только для javac classpath).
 * В рантайме настоящий org.lwjgl.opengl.GL13 из LWJGL3 игры.
 */
public final class GL13 {
    public static final int GL_TEXTURE0 = 0x84C0;
    public static final int GL_TEXTURE1 = 0x84C1;

    private GL13() {}

    public static void glActiveTexture(int texture) {}
}
