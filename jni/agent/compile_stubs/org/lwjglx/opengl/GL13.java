package org.lwjglx.opengl;

/**
 * КОМПИЛЯЦИОННЫЙ СТАБ (только для javac classpath).
 * В рантайме настоящий org.lwjglx.opengl.GL13 из игры
 * (glActiveTexture(I)V верифицирован по дампу).
 */
public final class GL13 {
    public static final int GL_TEXTURE0 = 0x84C0;

    private GL13() {}

    public static void glActiveTexture(int texture) {}
}
