# -*- coding: utf-8 -*-
# Разовая правка JumpDistort: depth -> read-only (probe+copy), проход в текущий таргет
import io

p = r'C:\Users\Admin\Desktop\rustme\jni\agent\src\utils\render\JumpDistort.java'
s = io.open(p, encoding='utf-8').read()

# 1) поля: swap-блок -> read-only блок
start = s.index('    // --- depth swap')
end = s.index('    private static int newTex')
new_fields = (
    '    // --- world depth: READ-ONLY копия глубины. Урок 09-10: подмена depth-\n'
    '    // аттачмента на ЧУЖОМ FBO (в оверлее забинжен не мировой FBO, rb=0) ломает\n'
    '    // игру (худ исчезает, картинка зависает). Теперь НИКАКИХ модификаций:\n'
    '    // однократный probe находит мировой FBO (status COMPLETE + color TEXTURE +\n'
    '    // depth RENDERBUFFER), каждый кадр из него копируем depth в свою текстуру\n'
    '    // (glCopyTexSubImage2D — читает, не пишет в игру). Валидация: у мирового\n'
    '    // FBO depth-картина неоднородна (не сплошной clear). ---\n'
    '    private static int depthTex;\n'
    '    private static int worldFbo = -1;\n'
    '    private static int worldW = -1, worldH = -1;\n'
    '    private static boolean hasDepth;\n'
    '    private static boolean probeLogged;\n'
    '    private static float nearPlane = 0.05f; // из захваченной PROJ: m14 = -2n\n\n'
)
s = s[:start] + new_fields + s[end:]

# 2) удалить старый ensureDepth (от его заголовка до конца метода)
start = s.index('    private static void ensureDepth')
end = s.index('    /**', start)
s = s[:start] + s[end:]

# 3) probe + copyDepth перед apply
NL = chr(10)
probe_lines = [
    '    private static int attachParam(int attachment, int pname) throws Exception {',
    '        IntBuffer b = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();',
    '        mGetAttachmentParam.invoke(null, GL_FRAMEBUFFER, attachment, pname, b);',
    '        return b.get(0);',
    '    }',
    '',
    '    private static final int GL_DEPTH_ATTACHMENT = 36096;',
    '    private static final int GL_RENDERBUFFER = 36161;',
    '    private static final int GL_DEPTH_COMPONENT24 = 33190;',
    '    private static final int GL_DEPTH_COMPONENT = 0x1902;',
    '    private static final int GL_UNSIGNED_INT = 0x1405;',
    '    private static final int GL_ATTACH_OBJ_TYPE = 0x8600;',
    '    private static final int GL_ATTACH_OBJ_NAME = 0x8641;',
    '',
    '    /** Read-only probe: найти мировой FBO (color TEXTURE + depth RENDERBUFFER),',
    '     *  подтвердить неоднородностью depth (copy+readback), кэшировать. */',
    '    private static void ensureWorldDepth(int curFbo, int w, int h) {',
    '        hasDepth = false;',
    '        if (worldFbo > 0 && worldW == w && worldH == h && depthTex != 0) {',
    '            hasDepth = true;',
    '            return;',
    '        }',
    '        worldFbo = -1;',
    '        try {',
    '            StringBuilder found = new StringBuilder();',
    '            int candidate = -1;',
    '            for (int id = 1; id <= 12; id++) {',
    '                try {',
    '                    mBindFramebuffer.invoke(null, GL_FRAMEBUFFER, id);',
    '                } catch (Throwable ig) {',
    '                    continue;',
    '                }',
    '                Object st = mCheckStatus.invoke(null, GL_FRAMEBUFFER);',
    '                if (!(st instanceof Number) || ((Number) st).intValue() != GL_FRAMEBUFFER_COMPLETE) {',
    '                    continue;',
    '                }',
    '                int colorType = attachParam(GL_COLOR_ATTACHMENT0, GL_ATTACH_OBJ_TYPE);',
    '                int depthType = attachParam(GL_DEPTH_ATTACHMENT, GL_ATTACH_OBJ_TYPE);',
    '                found.append(id).append(":c").append(colorType).append("/d").append(depthType).append(\' \');',
    '                if (depthType != GL_RENDERBUFFER || colorType != GL_TEXTURE) {',
    '                    continue;',
    '                }',
    '                if (candidate < 0) candidate = id;',
    '            }',
    '            mBindFramebuffer.invoke(null, GL_FRAMEBUFFER, curFbo);',
    '            if (candidate < 0) {',
    '                if (!probeLogged) {',
    '                    probeLogged = true;',
    '                    Log.info("JumpDistort", "no depth FBO found [" + found.toString().trim() + "] - occlusion off");',
    '                }',
    '                return;',
    '            }',
    '            if (depthTex != 0) GL11.glDeleteTextures(depthTex);',
    '            depthTex = GL11.glGenTextures();',
    '            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);',
    '            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);',
    '            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);',
    '            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);',
    '            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);',
    '            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0,',
    '                GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (ByteBuffer) null);',
    '            mBindFramebuffer.invoke(null, GL_FRAMEBUFFER, candidate);',
    '            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);',
    '            mBindFramebuffer.invoke(null, GL_FRAMEBUFFER, curFbo);',
    '            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);',
    '            java.nio.FloatBuffer fb = ByteBuffer.allocateDirect(w * h * 4)',
    '                .order(ByteOrder.nativeOrder()).asFloatBuffer();',
    '            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_FLOAT, fb);',
    '            float mn = 1f, mx = 0f;',
    '            for (int i = 0; i < w * h; i++) {',
    '                float v = fb.get(i);',
    '                if (v < mn) mn = v;',
    '                if (v > mx) mx = v;',
    '            }',
    '            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);',
    '            float spread = mx - mn;',
    '            if (spread < 0.02f) {',
    '                Log.info("JumpDistort", "fbo " + candidate + " depth uniform (" + mn + ".." + mx + ") - skip");',
    '                return;',
    '            }',
    '            worldFbo = candidate;',
    '            worldW = w;',
    '            worldH = h;',
    '            hasDepth = true;',
    '            try {',
    '                FloatBuffer proj = lliIilliiI.lIIlIlIl;',
    '                if (proj != null && proj.capacity() >= 16) {',
    '                    float m14 = proj.get(14);',
    '                    if (m14 < -1e-5f) nearPlane = -m14 / 2f;',
    '                }',
    '            } catch (Throwable ignore) {}',
    '            if (!probeLogged) {',
    '                probeLogged = true;',
    '                Log.info("JumpDistort", "world depth fbo=" + worldFbo + " " + w + "x" + h',
    '                    + " depth=" + mn + ".." + mx + " near=" + nearPlane + " probe=[" + found.toString().trim() + "]");',
    '            }',
    '        } catch (Throwable t) {',
    '            hasDepth = false;',
    '            Log.error("JumpDistort", "world depth probe failed", t);',
    '        }',
    '    }',
    '',
    '    /** Кадровая копия depth: мировой FBO -> наша текстура (read-only). */',
    '    private static void copyDepth(int curFbo, int w, int h) {',
    '        try {',
    '            mBindFramebuffer.invoke(null, GL_FRAMEBUFFER, worldFbo);',
    '            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);',
    '            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);',
    '            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);',
    '            mBindFramebuffer.invoke(null, GL_FRAMEBUFFER, curFbo);',
    '        } catch (Throwable ignore) {}',
    '    }',
    '',
]
anchor = '    /**' + NL + '     * Применить волну.'
assert anchor in s, 'apply anchor not found'
s = s.replace(anchor, NL.join(probe_lines) + NL + anchor, 1)

# 4) apply: probe + copyDepth вместо ensureDepth
old4 = (
    '            // --- depth swap: главный FBO (prevFbo) сейчас забинжен — подменяем' + NL +
    '            // его depth renderbuffer на нашу текстуру (однократно на fbo/размер).' + NL +
    '            // С текущего кадра+1 игра пишет world-depth в неё (clearDepth 0.1' + NL +
    '            // каждый кадр тоже в неё). ---' + NL +
    '            ensureDepth(prevFbo, srcW, srcH);'
)
new4 = (
    '            // --- world depth: read-only probe (однократно) + кадровая копия ---' + NL +
    '            ensureWorldDepth(prevFbo, srcW, srcH);' + NL +
    '            if (hasDepth) copyDepth(prevFbo, srcW, srcH);'
)
assert old4 in s, 'step4 anchor not found'
s = s.replace(old4, new4, 1)

# 5) fullscreen-проход в текущий таргет (не в FBO 0)
old5 = '            // --- fullscreen-проход прямо в бэкбуфер ---' + NL + '            bindFbo(0);'
new5 = '            // --- fullscreen-проход в ТЕКУЩИЙ оверлей-таргет (игра рисует' + NL + '            // оверлей в свой FBO — рисовать в 0 значит рисовать мимо) ---'
assert old5 in s, 'step5 anchor not found'
s = s.replace(old5, new5, 1)

old6 = '            bindFbo(prevFbo);' + NL + '            GL11.glViewport(vpX, vpY, srcW, srcH);'
new6 = '            GL11.glViewport(vpX, vpY, srcW, srcH);'
assert old6 in s, 'step6 anchor not found'
s = s.replace(old6, new6, 1)

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('rewritten; bindFbo(0) gone:', 'bindFbo(0)' not in s, '; ensureDepth gone:', 'ensureDepth' not in s)
