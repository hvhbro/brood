package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * entityMotion: поля скорости (motion X/Y/Z) Entity-класса по константам прыжка.
 * Y: метод с 0.42 (F|D), пишущий D-поле корня. X/Z: один метод с 0.2 (F|D),
 * пишущий ровно 2 distinct D-поля корня (спринт-буст прыжка: motionX -= sin*0.2,
 * motionZ += cos*0.2 — порядок по первой записи). Всё уникально — AUTO.
 */
public final class EntityMotionRole implements Role {
    public String name() { return "entityMotion"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit pos = ctx.byRole("entityPos");
        if (pos == null) {
            ctx.review.add("entityMotion: нет entityPos-роли — не от чего строить линк");
            return;
        }
        String root = pos.cls;
        Set<String> posFields = new HashSet<String>();
        for (RoleHit.Member m : pos.members) {
            if ("field".equals(m.kind) && "D".equals(m.desc)) posFields.add(m.name);
        }
        // Y: 0.42 -> D-поле корня. Два шага: прямо (константа и запись в одном
        // методе) или через геттер силы прыжка (()F с 0.42, ванильный
        // getJumpUpwardsMotion): метод, зовущий геттер и пишущий D-поле корня.
        // ВАЖНО: owner в байткоде — compile-time тип receiver'а (наследник!),
        // поэтому принадлежность — по декларации в корне, а не по owner.
        ClassInfo rootCi = idx.get(root);
        Set<String> rootD = new HashSet<String>();
        for (ClassInfo.FieldInfo f : rootCi.fields) {
            if ("D".equals(f.desc)) rootD.add(f.name);
        }
        Map<String, String> yEv = new HashMap<String, String>();
        Set<String> yCands = new HashSet<String>();
        Set<String> jumpGetters = new HashSet<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if ("()F".equals(m.desc) && hasConst(m, 0.42)) jumpGetters.add(ci.name + "#" + m.name);
            }
        }
        // XZ: 0.2 + ровно 2 distinct D-поля корня в одном методе
        Map<String, List<String>> xzEv = new HashMap<String, List<String>>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                boolean has042 = hasConst(m, 0.42);
                boolean has02 = hasConst(m, 0.2);
                if (!has042 && !has02) continue;
                List<String> dputs = new ArrayList<String>();
                List<String> dputsDeclared = new ArrayList<String>();
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == Opcodes.PUTFIELD || fr.opcode == Opcodes.PUTSTATIC)
                            && "D".equals(fr.desc) && root.equals(fr.owner)
                            && !posFields.contains(fr.name) && !dputs.contains(fr.name))
                        dputs.add(fr.name);
                    if ((fr.opcode == Opcodes.PUTFIELD || fr.opcode == Opcodes.PUTSTATIC)
                            && "D".equals(fr.desc) && rootD.contains(fr.name)
                            && !posFields.contains(fr.name) && !dputsDeclared.contains(fr.name))
                        dputsDeclared.add(fr.name);
                }
                if (has042) {
                    for (String f : dputs) {
                        yCands.add(f);
                        yEv.put(f, ci.name + "." + m.name);
                    }
                }
                if (!has042 && !jumpGetters.isEmpty()) {
                    boolean callsJump = false;
                    for (ClassInfo.MethodRef mr : m.methodRefs) {
                        if ("()F".equals(mr.desc)
                                && jumpGetters.contains(mr.owner + "#" + mr.name)) {
                            callsJump = true;
                            break;
                        }
                    }
                    if (callsJump) {
                        for (String f : dputsDeclared) {
                            yCands.add(f);
                            yEv.put(f, ci.name + "." + m.name + " viaJumpGetter");
                        }
                    }
                }
                if (has02 && dputs.size() == 2) {
                    String key = ci.name + "." + m.name;
                    if (!xzEv.containsKey(key)) xzEv.put(key, dputs);
                }
            }
        }
        if (yCands.size() != 1 || xzEv.size() != 1) {
            // сходимость прыжка и буста: спринт-прыжок пишет все три motion,
            // буст-метод — только X/Z. Y = прыжковое минус бустовые.
            if (yCands.size() == 3 && xzEv.size() == 1) {
                List<String> xz = xzEv.values().iterator().next();
                Set<String> xzSet = new HashSet<String>(xz);
                if (xzSet.size() == 2 && yCands.containsAll(xzSet)) {
                    Set<String> rest = new HashSet<String>(yCands);
                    rest.removeAll(xzSet);
                    if (rest.size() == 1) {
                        String y = rest.iterator().next();
                        finishTriple(ctx, idx, root, pos, y, xz, yEv, xzEv);
                        return;
                    }
                }
            }
            ctx.review.add("entityMotion:" + root + ": Y02=" + yCands + yEv
                    + " XZ02=" + xzEv + " (нужно по 1)");
            return;
        }
        String y = yCands.iterator().next();
        List<String> xz = xzEv.values().iterator().next();
        if (xz.contains(y) || new HashSet<String>(xz).size() != 2) {
            ctx.review.add("entityMotion:" + root + ": Y пересекается с XZ: Y=" + y + " XZ=" + xz);
            return;
        }
        finishTriple(ctx, idx, root, pos, y, xz, yEv, xzEv);
    }

    private void finishTriple(Ctx ctx, DumpIndex idx, String root, RoleHit pos,
                              String y, List<String> xz,
                              Map<String, String> yEv, Map<String, List<String>> xzEv) {
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = root;
        h.auto = true;
        h.evidence = "Y=" + y + " via " + yEv.get(y) + " XZ=" + xz + " via " + xzEv.keySet();
        ClassInfo ci = idx.get(root);
        String[] flds = {xz.get(0), y, xz.get(1)};
        String[] roles = {"motionX", "motionY", "motionZ"};
        for (int i = 0; i < 3; i++) {
            RoleUtil.addMember(h, root, "field", flds[i], "D", roles[i] + ".field", "jump-const");
            String setter = null;
            int writers = 0;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"(D)V".equals(m.desc)) continue;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == Opcodes.PUTFIELD || fr.opcode == Opcodes.PUTSTATIC)
                            && "D".equals(fr.desc) && root.equals(fr.owner)
                            && flds[i].equals(fr.name)) { writers++; setter = m.name; }
                }
            }
            if (writers == 1 && setter != null)
                RoleUtil.addMember(h, root, "method", setter, "(D)V", roles[i] + ".setter", "jump-const");
            String getter = RoleUtil.singleDReader(ci, flds[i]);
            if (getter != null)
                RoleUtil.addMember(h, root, "method", getter, "()D", roles[i] + ".getter", "jump-const");
        }
        ctx.hits.add(h);
    }

    private static boolean hasConst(ClassInfo.MethodInfo m, double v) {
        for (Float f : m.constFloats) {
            if (Math.abs(f - v) < 1e-6) return true;
        }
        for (Double d : m.constDoubles) {
            if (Math.abs(d - v) < 1e-9) return true;
        }
        for (Integer c : m.constInts) {
            if (Math.abs(c - v) < 1e-9) return true;
        }
        return false;
    }
}
