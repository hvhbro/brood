package org.lwjgl.opengl;

/**
 * КОМПИЛЯЦИОННЫЙ СТАБ (только для javac classpath).
 * В рантайме используется настоящий org.lwjgl.opengl.GL20 из LWJGL3 игры
 * (мод сам вызывает эти же методы в 8 классах).
 */
public final class GL20 {
    public static final int GL_FRAGMENT_SHADER = 0x8B30;
    public static final int GL_VERTEX_SHADER = 0x8B31;
    public static final int GL_COMPILE_STATUS = 0x8B81;
    public static final int GL_LINK_STATUS = 0x8B82;

    private GL20() {}

    public static int glCreateProgram() { return 0; }
    public static void glAttachShader(int program, int shader) {}
    public static void glLinkProgram(int program) {}
    public static int glGetProgrami(int program, int pname) { return 0; }
    public static String glGetProgramInfoLog(int program, int maxLength) { return ""; }
    public static int glCreateShader(int type) { return 0; }
    public static void glShaderSource(int shader, CharSequence source) {}
    public static void glCompileShader(int shader) {}
    public static int glGetShaderi(int shader, int pname) { return 0; }
    public static String glGetShaderInfoLog(int shader, int maxLength) { return ""; }
    public static void glDetachShader(int program, int shader) {}
    public static void glDeleteShader(int shader) {}
    public static void glDeleteProgram(int program) {}
    public static void glUseProgram(int program) {}
    public static void glBindAttribLocation(int program, int index, CharSequence name) {}
    public static int glGetUniformLocation(int program, CharSequence name) { return 0; }
    public static void glUniform1f(int location, float v0) {}
    public static void glUniform1i(int location, int v0) {}
    public static void glUniform2f(int location, float v0, float v1) {}
    public static void glUniform3f(int location, float v0, float v1, float v2) {}
    public static void glUniform4f(int location, float v0, float v1, float v2, float v3) {}

    // FBO (LWJGL3 int-overloads)
    public static void glActiveTexture(int texture) {}
}
