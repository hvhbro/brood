package org.lwjgl.opengl;

/**
 * КОМПИЛЯЦИОННЫЙ СТАБ (только для javac classpath).
 * В рантайме используется настоящий org.lwjgl.opengl.GL30 из LWJGL3 игры
 * (мод вызывает GL30 в 20 классах — точно присутствует).
 */
public final class GL30 {
    public static final int GL_FRAMEBUFFER = 0x8D40;
    public static final int GL_RENDERBUFFER = 0x8D41;
    public static final int GL_DEPTH_COMPONENT = 0x1902;
    public static final int GL_DEPTH_COMPONENT24 = 0x81A6;
    public static final int GL_DEPTH_ATTACHMENT = 0x8D00;
    public static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    public static final int GL_TEXTURE_2D = 0x0DE1;
    public static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    public static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    public static final int GL_NEAREST = 0x2600;

    private GL30() {}

    public static int glGenFramebuffers() { return 0; }
    public static void glBindFramebuffer(int target, int framebuffer) {}
    public static void glDeleteFramebuffers(int framebuffer) {}
    public static int glGenRenderbuffers() { return 0; }
    public static void glBindRenderbuffer(int target, int renderbuffer) {}
    public static void glDeleteRenderbuffers(int renderbuffer) {}
    public static void glRenderbufferStorage(int target, int internalformat, int width, int height) {}
    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {}
    public static void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {}
    public static int glCheckFramebufferStatus(int target) { return 0; }
}
