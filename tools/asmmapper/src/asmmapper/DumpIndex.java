package asmmapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Индекс дампа: обход *.class, разбор через ASM, имя берётся из байткода
 * (файлы вида Name(12).class — суффикс игнорируется).
 */
public final class DumpIndex {
    private final Map<String, ClassInfo> byName = new HashMap<String, ClassInfo>();
    private int broken;

    public Map<String, ClassInfo> all() { return byName; }
    public int broken() { return broken; }
    public ClassInfo get(String internalName) { return byName.get(internalName); }

    public static DumpIndex load(File dumpDir, StringBuilder log) throws Exception {
        DumpIndex idx = new DumpIndex();
        List<File> files = new ArrayList<File>();
        collect(dumpDir, files);
        if (log != null) log.append("class files: ").append(files.size()).append('\n');
        for (File f : files) {
            try {
                InputStream in = new FileInputStream(f);
                byte[] bytes;
                try {
                    bytes = readAll(in);
                } finally {
                    in.close();
                }
                ClassReader cr = new ClassReader(bytes);
                ClassNode cn = new ClassNode();
                cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                ClassInfo ci = convert(cn);
                if (ci.name.length() == 0) { idx.broken++; continue; }
                if (!idx.byName.containsKey(ci.name)) idx.byName.put(ci.name, ci);
            } catch (Throwable t) {
                idx.broken++;
            }
        }
        return idx;
    }

    private static void collect(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".class")) out.add(f);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        byte[] buf = new byte[65536];
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static ClassInfo convert(ClassNode cn) {
        ClassInfo ci = new ClassInfo();
        ci.name = cn.name == null ? "" : cn.name;
        ci.superName = cn.superName == null ? "" : cn.superName;
        if (cn.interfaces != null) for (Object o : cn.interfaces) ci.interfaces.add((String) o);
        if (cn.fields != null) for (Object o : cn.fields) {
            FieldNode fn = (FieldNode) o;
            ClassInfo.FieldInfo fi = new ClassInfo.FieldInfo();
            fi.name = fn.name;
            fi.desc = fn.desc;
            fi.generic = fn.signature == null ? "" : fn.signature;
            fi.isStatic = (fn.access & Opcodes.ACC_STATIC) != 0;
            ci.fields.add(fi);
        }
        if (cn.methods != null) for (Object o : cn.methods) {
            MethodNode mn = (MethodNode) o;
            ClassInfo.MethodInfo mi = new ClassInfo.MethodInfo();
            mi.name = mn.name;
            mi.desc = mn.desc;
            mi.isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
            if (mn.instructions != null) {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode f = (FieldInsnNode) insn;
                        ClassInfo.FieldRef fr = new ClassInfo.FieldRef();
                        fr.opcode = f.getOpcode();
                        fr.owner = f.owner;
                        fr.name = f.name;
                        fr.desc = f.desc;
                        mi.fieldRefs.add(fr);
                    } else if (insn instanceof MethodInsnNode) {
                        MethodInsnNode m = (MethodInsnNode) insn;
                        ClassInfo.MethodRef mr = new ClassInfo.MethodRef();
                        mr.owner = m.owner;
                        mr.name = m.name;
                        mr.desc = m.desc;
                        mi.methodRefs.add(mr);
                    } else if (insn instanceof LdcInsnNode) {
                        Object cst = ((LdcInsnNode) insn).cst;
                        if (cst instanceof String) {
                            mi.ldcStrings.add((String) cst);
                            ci.strings.add((String) cst);
                        } else if (cst instanceof Integer) {
                            mi.ldcInts.add((Integer) cst);
                            mi.constInts.add((Integer) cst);
                            mi.events.add("C:" + cst);
                        } else if (cst instanceof Float) {
                            mi.constFloats.add((Float) cst);
                        } else if (cst instanceof Double) {
                            mi.constDoubles.add((Double) cst);
                        } else if (cst instanceof org.objectweb.asm.Type) {
                            org.objectweb.asm.Type t = (org.objectweb.asm.Type) cst;
                            if (t.getSort() == org.objectweb.asm.Type.OBJECT) {
                                mi.ldcClasses.add(t.getInternalName());
                            }
                        }
                    } else if (insn instanceof org.objectweb.asm.tree.TypeInsnNode) {
                        int op = insn.getOpcode();
                        if (op == Opcodes.NEW) {
                            mi.news.add(((org.objectweb.asm.tree.TypeInsnNode) insn).desc);
                        } else if (op == Opcodes.CHECKCAST) {
                            mi.checkcasts.add(((org.objectweb.asm.tree.TypeInsnNode) insn).desc);
                        } else if (op == Opcodes.INSTANCEOF) {
                            mi.instanceofs.add(((org.objectweb.asm.tree.TypeInsnNode) insn).desc);
                        }
                    } else if (insn instanceof org.objectweb.asm.tree.IntInsnNode) {
                        int op = insn.getOpcode();
                        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) {
                            int v = ((org.objectweb.asm.tree.IntInsnNode) insn).operand;
                            mi.constInts.add(v);
                            mi.events.add("C:" + v);
                        }
                    } else if (insn instanceof org.objectweb.asm.tree.InsnNode) {
                        int op = insn.getOpcode();
                        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
                            int v = op - Opcodes.ICONST_0; // M1 -> -1
                            mi.constInts.add(v);
                            mi.events.add("C:" + v);
                        } else if (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) {
                            mi.constFloats.add((float) (op - Opcodes.FCONST_0));
                        } else if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) {
                            mi.constDoubles.add((double) (op - Opcodes.DCONST_0));
                        }
                    }
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode f = (FieldInsnNode) insn;
                        int op = f.getOpcode();
                        if ((op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC)
                                && "I".equals(f.desc) && cn.name.equals(f.owner)) {
                            mi.events.add("G:" + f.owner + "/" + f.name);
                        }
                    }
                }
            }
            ci.methods.add(mi);
        }
        return ci;
    }

    /** Все Lxxx; из дескриптора. */
    public static Set<String> classesInDesc(String desc) {
        if (desc == null) return Collections.emptySet();
        Set<String> out = new HashSet<String>();
        int i = 0;
        while ((i = desc.indexOf('L', i)) >= 0) {
            int j = desc.indexOf(';', i);
            if (j < 0) break;
            String cls = desc.substring(i + 1, j);
            if (cls.length() > 0) out.add(cls);
            i = j + 1;
        }
        return out;
    }

    /**
     * Строка -> имя класса, если похожа на ссылку: rustme/xxx или rustme.xxx.
     * Точки конвертируются в слэши. Иначе null.
     */
    public static String stringToClass(String s) {
        if (s == null || s.length() < 8 || s.length() > 200) return null;
        String v = s;
        if (v.indexOf('.') >= 0 && v.indexOf('/') < 0) {
            if (!v.startsWith("rustme.") && !v.startsWith("ru.") && !v.startsWith("net.")
                    && !v.startsWith("com.") && !v.startsWith("org.")) return null;
            v = v.replace('.', '/');
        }
        if (v.indexOf('/') < 0) return null;
        if (!(v.startsWith("rustme/") || v.startsWith("ru/") || v.startsWith("net/")
                || v.startsWith("com/") || v.startsWith("org/") || v.startsWith("me/"))) return null;
        if (!v.matches("[A-Za-z0-9/$]+(/[A-Za-z0-9/$]+)+")) return null;
        return v;
    }
}
