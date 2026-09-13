package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * textures: менеджер текстур + объект текстуры.
 * Менеджер: Map-поле + register (rl,texобъект) + getTexture (rl)->texобъект.
 * Объект: ()I gl-id (единственный). РЛ-класс — из resourceLocation-роли.
 */
public final class TextureRole implements Role {
    public String name() { return "textures"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit rl = ctx.byRole("resourceLocation");
        if (rl == null) {
            ctx.review.add("textures: нет resourceLocation — не от чего строить линк");
            return;
        }
        List<String> cands = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            boolean hasMap = false;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (f.desc.contains("Map<") || "Ljava/util/Map;".equals(f.desc)
                        || "Ljava/util/HashMap;".equals(f.desc)) {
                    if (f.generic.length() == 0
                            || f.generic.contains("L" + rl.cls + ";")) hasMap = true;
                }
            }
            if (!hasMap) continue;
            String reg = null;
            String get = null;
            String tex = null;
            for (ClassInfo.MethodInfo m : ci.methods) {
                int rp = m.desc.indexOf(')');
                if (rp < 0) continue;
                List<String> params = new ArrayList<String>(
                        DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
                String ret = m.desc.substring(rp + 1);
                if (params.size() == 2 && params.get(0).equals(rl.cls)
                        && ret.startsWith("L")) {
                    reg = m.name;
                    tex = ret.substring(1, ret.length() - 1);
                }
                if (params.size() == 1 && params.get(0).equals(rl.cls)
                        && ret.startsWith("L")) {
                    get = m.name;
                    tex = ret.substring(1, ret.length() - 1);
                }
            }
            if (reg != null && get != null && tex != null && tex.startsWith("rustme/"))
                cands.add(ci.name + "#" + reg + "#" + get + "#" + tex);
        }
        if (cands.size() == 1) {
            String[] p = cands.get(0).split("#");
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = p[0];
            h.auto = true;
            h.evidence = "Map + register(rl,tex) + get(rl)";
            ClassInfo tc = idx.get(p[3]);
            String glId = null;
            String glField = null;
            int glN = 0;
            if (tc != null) {
                // glId: ()I reader with matching (I)V writer on same field (pair)
                for (ClassInfo.MethodInfo m : tc.methods) {
                    if (!"()I".equals(m.desc)) continue;
                    String field = null;
                    for (ClassInfo.FieldRef fr : m.fieldRefs) {
                        if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                                || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                                && "I".equals(fr.desc) && p[3].equals(fr.owner)) {
                            field = fr.name;
                            break;
                        }
                    }
                    if (field == null) continue;
                    // look for writer
                    boolean hasWriter = false;
                    for (ClassInfo.MethodInfo mm : tc.methods) {
                        if (!"(I)V".equals(mm.desc)) continue;
                        for (ClassInfo.FieldRef fr : mm.fieldRefs) {
                            if ((fr.opcode == org.objectweb.asm.Opcodes.PUTFIELD
                                    || fr.opcode == org.objectweb.asm.Opcodes.PUTSTATIC)
                                    && "I".equals(fr.desc) && p[3].equals(fr.owner)
                                    && field.equals(fr.name)) {
                                hasWriter = true;
                                break;
                            }
                        }
                    }
                    if (hasWriter) {
                        glN++;
                        glId = m.name;
                        glField = field;
                    }
                }
                // fallback: if no pair found, count all readers (old behavior)
                if (glN == 0) {
                    for (ClassInfo.MethodInfo m : tc.methods) {
                        if (!"()I".equals(m.desc)) continue;
                        for (ClassInfo.FieldRef fr : m.fieldRefs) {
                            if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                                    || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                                    && "I".equals(fr.desc) && p[3].equals(fr.owner)) {
                                glN++;
                                glId = m.name;
                                glField = fr.name;
                            }
                        }
                    }
                }
            }
            RoleUtil.addMember(h, p[0], "method", p[1], "(L" + rl.cls + ";L" + p[3] + ";)V",
                    "registerTexture", "rl-tex-pair");
            RoleUtil.addMember(h, p[0], "method", p[2], "(L" + rl.cls + ";)L" + p[3] + ";",
                    "getTexture", "rl-to-tex");
            RoleUtil.addMember(h, p[3], "class", shortName(p[3]), "L" + p[3] + ";",
                    "textureObject", "registered-type");
            // если пар несколько — выбираем тот, что читается рядом с GL вызовом
            if (glN != 1) {
                List<String> glCandidates = new ArrayList<String>();
                if (tc != null) {
                    for (ClassInfo.MethodInfo m : tc.methods) {
                        if (!"()I".equals(m.desc)) continue;
                        String field = null;
                        for (ClassInfo.FieldRef fr : m.fieldRefs) {
                            if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                                    || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                                    && "I".equals(fr.desc) && p[3].equals(fr.owner)) {
                                field = fr.name; break;
                            }
                        }
                        if (field == null) continue;
                        // check pair exists
                        boolean hasWriter = false;
                        for (ClassInfo.MethodInfo mm : tc.methods) {
                            if (!"(I)V".equals(mm.desc)) continue;
                            for (ClassInfo.FieldRef fr : mm.fieldRefs) {
                                if ((fr.opcode == org.objectweb.asm.Opcodes.PUTFIELD
                                        || fr.opcode == org.objectweb.asm.Opcodes.PUTSTATIC)
                                        && "I".equals(fr.desc) && p[3].equals(fr.owner)
                                        && field.equals(fr.name)) { hasWriter = true; break; }
                            }
                        }
                        if (!hasWriter) continue;
                        // check GL usage: any method reading this field calls GL
                        boolean glUsed = false;
                        for (ClassInfo.MethodInfo mm : tc.methods) {
                            boolean readsField = false;
                            boolean callsGL = false;
                            for (ClassInfo.FieldRef fr : mm.fieldRefs) {
                                if (field.equals(fr.name) && p[3].equals(fr.owner) && "I".equals(fr.desc)) readsField = true;
                            }
                            for (ClassInfo.MethodRef mr : mm.methodRefs) {
                                if (mr.owner.startsWith("org/lwjgl/opengl/GL")) callsGL = true;
                            }
                            if (readsField && callsGL) { glUsed = true; break; }
                        }
                        // also check manager class reading it with GL
                        if (!glUsed) {
                            ClassInfo mc = idx.get(p[0]);
                            if (mc != null) {
                                for (ClassInfo.MethodInfo mm : mc.methods) {
                                    boolean readsField = false;
                                    boolean callsGL = false;
                                    for (ClassInfo.FieldRef fr : mm.fieldRefs) {
                                        if (field.equals(fr.name) && p[3].equals(fr.owner)) readsField = true;
                                    }
                                    for (ClassInfo.MethodRef mr : mm.methodRefs) {
                                        if (mr.owner.startsWith("org/lwjgl/opengl/GL")) callsGL = true;
                                    }
                                    if (readsField && callsGL) { glUsed = true; break; }
                                }
                            }
                        }
                        if (glUsed) glCandidates.add(field + "#" + m.name);
                    }
                    if (glCandidates.size() == 1) {
                        String[] pp = glCandidates.get(0).split("#");
                        glField = pp[0]; glId = pp[1]; glN = 1;
                    }
                }
            }
            if (glN == 1 && glId != null) {
                RoleUtil.addMember(h, p[3], "method", glId, "()I", "glTextureId", "pair+GL");
                RoleUtil.addMember(h, p[3], "field", glField, "I", "glTextureIdField", "pair+GL");
            } else {
                // glId не критичен для ядра — оставляем класс AUTO, метод на проверку
                ctx.review.add("textures:" + p[3] + ": gl-id не уникален (pairs=" + glN + ") — на проверку, класс AUTO");
            }
            ctx.hits.add(h);
        } else {
            ctx.review.add("textures: кандидатов=" + cands.size() + " " + cands);
        }
    }

    private static String shortName(String internal) {
        int i = internal.lastIndexOf('/');
        return i >= 0 ? internal.substring(i + 1) : internal;
    }
}
