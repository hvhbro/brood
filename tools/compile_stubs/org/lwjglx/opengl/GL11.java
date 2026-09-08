package org.lwjglx.opengl;

/**
 * КОМПИЛЯЦИОННЫЙ СТАБ (только для javac classpath).
 * В рантайме используется настоящий org.lwjglx.opengl.GL11 из игры
 * (lwjgl3ify-шимп: LWJGL2-статик-API поверх LWJGL3).
 * Только методы, используемые нашим рендером.
 */
public final class GL11 {
    public static final int GL_QUADS = 7;
    public static final int GL_BLEND = 0x0BE2; // 3042! (0xBE — опечатка, из-за неё блендинг не включался)
    public static final int GL_TEXTURE_2D = 0xDE1;
    public static final int GL_SCISSOR_TEST = 0xC11;
    public static final int GL_SRC_ALPHA = 0x302;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 0x303;
    public static final int GL_ALPHA_TEST = 0xBC0;
    public static final int GL_LINE_SMOOTH = 0xB20;
    public static final int GL_CULL_FACE = 0xB44;
    public static final int GL_DEPTH_TEST = 0xB71;
    public static final int GL_NEAREST = 0x2600;
    public static final int GL_LINEAR = 0x2601;
    public static final int GL_CLAMP = 0x2900;
    public static final int GL_RGBA = 0x1908;
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    public static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    public static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    public static final int GL_TEXTURE_WRAP_S = 0x2802;
    public static final int GL_TEXTURE_WRAP_T = 0x2803;

    private GL11() {}

    public static void glEnable(int cap) {}
    public static void glDisable(int cap) {}

    public static void glPushMatrix() {}
    public static void glPopMatrix() {}

    public static void glMatrixMode(int mode) {}
    public static void glLoadIdentity() {}
    public static void glOrtho(double left, double right, double bottom, double top, double near, double far) {}
    public static int glGetError() { return 0; }
    public static void glPushAttrib(int mask) {}
    public static void glPopAttrib() {}
    public static void glTranslatef(float x, float y, float z) {}
    public static void glScalef(float x, float y, float z) {}
    public static void glRotatef(float angle, float x, float y, float z) {}

    public static void glBegin(int mode) {}
    public static void glEnd() {}
    public static void glVertex2f(float x, float y) {}
    public static void glVertex2d(double x, double y) {}
    public static void glVertex3f(float x, float y, float z) {}
    public static void glColor4f(float r, float g, float b, float a) {}
    public static void glColor4ub(byte r, byte g, byte b, byte a) {}
    public static void glTexCoord2f(float s, float t) {}

    public static void glBlendFunc(int sfactor, int dfactor) {}
    public static void glBlendFuncSeparate(int sRGB, int dRGB, int sA, int dA) {}
    public static void glScissor(int x, int y, int width, int height) {}
    public static void glLineWidth(float width) {}

    public static int glGenTextures() { return 0; }
    public static void glBindTexture(int target, int texture) {}
    public static void glTexParameteri(int target, int pname, int param) {}
    // сигнатура верифицирована по дампу: glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V
    public static void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, java.nio.ByteBuffer pixels) {}
    public static void glCopyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {}
    public static void glViewport(int x, int y, int width, int height) {}
    public static void glReadBuffer(int mode) {}
    public static int glGetInteger(int pname) { return 0; }
    public static void glGetInteger(int pname, java.nio.IntBuffer params) {}
    public static float glGetFloat(int pname) { return 0f; }
    public static void glGetFloat(int pname, java.nio.FloatBuffer params) {}
    public static boolean glIsEnabled(int cap) { return false; }
    public static void glEnableClientState(int array) {}
    public static void glDisableClientState(int array) {}
}
