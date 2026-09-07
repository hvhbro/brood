package utils.render;

import org.lwjgl.opengl.GL20;

/**
 * ShaderUtil: компиляция и использование GLSL-программ (expensive-стиль,
 * но GLSL-строки хранятся прямо в Java-классах).
 * Рисование: ванильный GL11-квад с текстурой FBO НЕ нужен — мы рисуем
 * «квад-подложку» и отдаём экранные координаты в uniforms SDF-шейдера,
 * который сам решает форму/цвет каждого пикселя.
 *
 * Обязательно вызывать только с главного потока (GL-контекст).
 */
public final class ShaderUtil {

    private final int program;

    /** id GL-программы (для glGetInteger-обёрток save/restore). */
    public int programId() {
        return program;
    }

    public ShaderUtil(String fragmentSource) {
        this(null, fragmentSource);
    }

    public ShaderUtil(String vertexSource, String fragmentSource) {
        int vs = vertexSource == null ? 0 : compile(GL20.GL_VERTEX_SHADER, vertexSource);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);
        program = GL20.glCreateProgram();
        if (vs != 0) GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(program, 1024);
            utils.etc.Log.error("Shader", "link failed: " + log, null);
        }
        if (vs != 0) GL20.glDetachShader(program, vs);
        GL20.glDetachShader(program, fs);
        if (vs != 0) GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(shader, 1024);
            utils.etc.Log.error("Shader", "compile failed: " + log, null);
            return 0;
        }
        return shader;
    }

    public void start() {
        GL20.glUseProgram(program);
    }

    public void stop() {
        GL20.glUseProgram(0);
    }

    public int uniform(String name) {
        return GL20.glGetUniformLocation(program, name);
    }

    public void uniformF(String name, float v) {
        GL20.glUniform1f(uniform(name), v);
    }

    public void uniform2F(String name, float x, float y) {
        GL20.glUniform2f(uniform(name), x, y);
    }

    public void uniform4F(String name, float x, float y, float z, float w) {
        GL20.glUniform4f(uniform(name), x, y, z, w);
    }

    public void uniformI(String name, int v) {
        GL20.glUniform1i(uniform(name), v);
    }
}
