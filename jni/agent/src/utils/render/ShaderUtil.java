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

    private int program;

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
        // FAIL-FAST: если запрошенный шейдер не собрался — program=0 (иначе
        // линкуется программа с одной вершинной стадией, и fullscreen-квад
        // с ней заливает экран мусором — «зависшая картинка»).
        if (fs == 0 || (vertexSource != null && vs == 0)) {
            if (vs != 0) GL20.glDeleteShader(vs);
            if (fs != 0) GL20.glDeleteShader(fs);
            program = 0;
            return;
        }
        int prog = GL20.glCreateProgram();
        if (vs != 0) GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glLinkProgram(prog);
        boolean linked = GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) != 0;
        if (!linked) {
            String log = GL20.glGetProgramInfoLog(prog, 1024);
            utils.etc.Log.error("Shader", "link failed: " + log, null);
        }
        if (vs != 0) GL20.glDetachShader(prog, vs);
        GL20.glDetachShader(prog, fs);
        if (vs != 0) GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (!linked) {
            // битой программой рисовать нельзя (иначе glUseProgram даёт
            // GL-ошибку каждый кадр) — только program=0 + ванильный fallback
            GL20.glDeleteProgram(prog);
            program = 0;
        } else {
            program = prog;
        }
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

    public void uniform3F(String name, float x, float y, float z) {
        GL20.glUniform3f(uniform(name), x, y, z);
    }

    public void uniform4F(String name, float x, float y, float z, float w) {
        GL20.glUniform4f(uniform(name), x, y, z, w);
    }

    public void uniformI(String name, int v) {
        GL20.glUniform1i(uniform(name), v);
    }
}
