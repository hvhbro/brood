package asmmapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/** Общие приёмы ролей: уникальные ридеры/райтеры, резолв по иерархии, связи. */
public final class RoleUtil {
    private RoleUtil() {}

    public static void addMember(RoleHit h, String cls, String kind, String name,
                                 String desc, String role, String via) {
        RoleHit.Member m = new RoleHit.Member();
        m.cls = cls;
        m.kind = kind;
        m.name = name;
        m.desc = desc;
        m.role = role;
        m.via = via;
        h.members.add(m);
    }

    /** Единственный ()D-ридер поля (ровно один getfield-D) или null. */
    public static String singleDReader(ClassInfo ci, String field) {
        return singleReader(ci, field, "D", "()D");
    }

    /** Единственный ()F-ридер поля или null. */
    public static String singleFReader(ClassInfo ci, String field) {
        return singleReader(ci, field, "F", "()F");
    }

    /** Единственный ()I-ридер поля или null. */
    public static String singleIReader(ClassInfo ci, String field) {
        return singleReader(ci, field, "I", "()I");
    }

    private static String singleReader(ClassInfo ci, String field, String fdesc, String mdesc) {
        String found = null;
        int n = 0;
        for (ClassInfo.MethodInfo m : ci.methods) {
            if (!mdesc.equals(m.desc)) continue;
            for (ClassInfo.FieldRef fr : m.fieldRefs) {
                if ((fr.opcode == Opcodes.GETFIELD || fr.opcode == Opcodes.GETSTATIC)
                        && fdesc.equals(fr.desc) && ci.name.equals(fr.owner)
                        && field.equals(fr.name)) {
                    n++;
                    found = m.name;
                }
            }
        }
        return n == 1 ? found : null;
    }

    /** Единственный (D)V-писатель (ровно один putfield-D в классе) или null. */
    public static String singleDWriter(ClassInfo ci) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        Map<String, String> fieldOf = new HashMap<String, String>();
        for (ClassInfo.MethodInfo m : ci.methods) {
            if (!"(D)V".equals(m.desc)) continue;
            Set<String> puts = new HashSet<String>();
            for (ClassInfo.FieldRef fr : m.fieldRefs) {
                if ((fr.opcode == Opcodes.PUTFIELD || fr.opcode == Opcodes.PUTSTATIC)
                        && "D".equals(fr.desc) && ci.name.equals(fr.owner)) puts.add(fr.name);
            }
            if (puts.size() != 1) return null;
            String f = puts.iterator().next();
            Integer c = counts.get(f);
            counts.put(f, c == null ? 1 : c + 1);
            fieldOf.put(m.name, f);
        }
        if (counts.size() != 1) return null;
        for (Map.Entry<String, String> e : fieldOf.entrySet()) {
            if (counts.get(e.getValue()) == 1) return e.getKey();
        }
        return null;
    }

    /** Единственный (F)V-писатель поля (ровно один putfield-F) или null. */
    public static String singleFWriter(ClassInfo ci, String field) {
        String found = null;
        int n = 0;
        for (ClassInfo.MethodInfo m : ci.methods) {
            if (!"(F)V".equals(m.desc)) continue;
            for (ClassInfo.FieldRef fr : m.fieldRefs) {
                if ((fr.opcode == Opcodes.PUTFIELD || fr.opcode == Opcodes.PUTSTATIC)
                        && "F".equals(fr.desc) && ci.name.equals(fr.owner)
                        && field.equals(fr.name)) {
                    n++;
                    found = m.name;
                }
            }
        }
        return n == 1 ? found : null;
    }

    /** Имя геттера по полю в карте геттер->поле или null. */
    public static String getterForField(Map<String, String> getterField, String field) {
        for (Map.Entry<String, String> e : getterField.entrySet()) {
            if (field.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    /** tick-метод, зовущий speed-сеттер travel-роли (wrapper onLivingUpdate). */
    public static String findSpeedTick(DumpIndex idx, Ctx ctx, RoleHit travel) {
        String setter = null;
        for (RoleHit.Member m : travel.members) {
            if ("speed.setter".equals(m.role)) setter = m.name;
        }
        if (setter == null) return null;
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if (!(setter + "(F)V").equals(mr.name + mr.desc)) continue;
                    String decl = resolveMethod(idx, mr.owner, mr.name, mr.desc);
                    if (travel.cls.equals(decl)) return ci.name + "." + m.name + m.desc;
                }
            }
        }
        return null;
    }

    /** Арность дескриптора (число параметров, включая примитивы). */
    public static int countParams(String desc) {
        int rp = desc.indexOf(')');
        if (rp < 0) return -1;
        int n = 0;
        int i = 1;
        while (i < rp) {
            char c = desc.charAt(i);
            if (c == 'L') {
                i = desc.indexOf(';', i);
                if (i < 0) return -1;
                i++;
                n++;
            } else if (c == '[') {
                while (i < rp && desc.charAt(i) == '[') i++;
                if (i < rp && desc.charAt(i) == 'L') {
                    i = desc.indexOf(';', i);
                    if (i < 0) return -1;
                    i++;
                } else {
                    i++;
                }
                n++;
            } else {
                i++;
                n++;
            }
        }
        return n;
    }

    public static String resolveMethod(DumpIndex idx, String owner, String name, String desc) {
        Set<String> seen = new HashSet<String>();
        String cur = owner;
        while (cur != null && seen.add(cur)) {
            ClassInfo ci = idx.get(cur);
            if (ci == null) return null;
            for (ClassInfo.MethodInfo mm : ci.methods) {
                if (name.equals(mm.name) && desc.equals(mm.desc)) return cur;
            }
            cur = ci.superName;
        }
        return null;
    }

    /** cls — транзитивный наследник parent (строго ниже). */
    public static boolean isSubclassOf(DumpIndex idx, String cls, String parent) {
        Set<String> seen = new HashSet<String>();
        String cur = cls;
        while (cur != null && seen.add(cur)) {
            ClassInfo ci = idx.get(cur);
            if (ci == null) return false;
            if (parent.equals(ci.superName)) return true;
            cur = ci.superName;
        }
        return false;
    }

    /** Транзитивная реализация интерфейса (вверх по super+ifaces). */
    public static boolean implementsTransitive(DumpIndex idx, ClassInfo ci,
                                              String itf, Set<String> seen) {
        if (ci == null || !seen.add(ci.name)) return false;
        if (ci.interfaces.contains(itf)) return true;
        for (String i : ci.interfaces) {
            if (implementsTransitive(idx, idx.get(i), itf, seen)) return true;
        }
        if (ci.superName.length() > 0) {
            ClassInfo sup = idx.get(ci.superName);
            if (sup != null && implementsTransitive(idx, sup, itf, seen)) return true;
        }
        return false;
    }

    /** У holder есть поле типа target. */
    public static boolean holdsFieldOf(DumpIndex idx, String holder, String target) {
        ClassInfo ci = idx.get(holder);
        if (ci == null) return false;
        for (ClassInfo.FieldInfo f : ci.fields) {
            if (("L" + target + ";").equals(f.desc)) return true;
        }
        return false;
    }

    /** У m есть <init> с параметром типа g. */
    public static boolean ctorTakes(DumpIndex idx, String m, String g) {
        ClassInfo ci = idx.get(m);
        if (ci == null) return false;
        for (ClassInfo.MethodInfo mi : ci.methods) {
            if (!"<init>".equals(mi.name)) continue;
            if (DumpIndex.classesInDesc(mi.desc).contains(g)) return true;
        }
        return false;
    }

    /** Код user ссылается на target: поле типа target или вызов его метода. */
    public static boolean usesClass(DumpIndex idx, String user, String target) {
        ClassInfo ci = idx.get(user);
        if (ci == null) return false;
        if (holdsFieldOf(idx, user, target)) return true;
        for (ClassInfo.MethodInfo mi : ci.methods) {
            for (ClassInfo.MethodRef mr : mi.methodRefs) {
                if (target.equals(mr.owner)) return true;
            }
        }
        return false;
    }

    /** run()V класса g вызывает методы класса m (главный цикл крутит MC). */
    public static boolean runCalls(DumpIndex idx, String g, String m) {
        ClassInfo ci = idx.get(g);
        if (ci == null) return false;
        for (ClassInfo.MethodInfo mi : ci.methods) {
            if (!"run".equals(mi.name) || !"()V".equals(mi.desc)) continue;
            for (ClassInfo.MethodRef mr : mi.methodRefs) {
                if (m.equals(mr.owner)) return true;
            }
        }
        return false;
    }

    /** У m есть <init> с параметром из стабильной (необфусцированной) либы. */
    public static boolean ctorTakesLib(DumpIndex idx, String m, String libCls) {
        ClassInfo ci = idx.get(m);
        if (ci == null) return false;
        for (ClassInfo.MethodInfo mi : ci.methods) {
            if (!"<init>".equals(mi.name)) continue;
            if (DumpIndex.classesInDesc(mi.desc).contains(libCls)) return true;
        }
        return false;
    }
}
