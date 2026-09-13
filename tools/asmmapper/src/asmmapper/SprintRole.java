package asmmapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * sprint: isSprinting + isSneaking + setSprint/sprintFlag.
 * isSprinting: ()Z с вызовом (I)Z и константой 3 (dataWatcher id=3, ванила
 * форка; зовётся из прыжка для спринт-буста).
 * isSneaking: тривиальный ()Z-геттер Z-поля иерархии, зовомый из (DDD*)-move.
 * setSprint: единственный (Z)V EntityPlayer, пишущий Z иерархии (+поле).
 */
public final class SprintRole implements Role {
    public String name() { return "sprint"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit travel = ctx.byRole("travel");
        RoleHit epos = ctx.byRole("entityPos");
        if (travel == null || epos == null) {
            ctx.review.add("sprint: нет travel/entityPos — не от чего строить линк");
            return;
        }
        String ep = travel.cls;
        String root = epos.cls;
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = ep;
        h.auto = true;
        StringBuilder ev = new StringBuilder();
        // 1. isSprinting: ()Z с (I)Z-вызовом и const 3
        List<String> sprinting = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            if (!inHierarchy(idx, ci.name, ep)) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"()Z".equals(m.desc)) continue;
                boolean callsI = false;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if ("(I)Z".equals(mr.desc)) { callsI = true; break; }
                }
                if (!callsI) continue;
                boolean has3 = false;
                for (Integer c : m.constInts) {
                    if (c == 3) { has3 = true; break; }
                }
                if (has3) sprinting.add(ci.name + "#" + m.name);
            }
        }
        if (sprinting.size() == 1) {
            String s = sprinting.get(0);
            RoleUtil.addMember(h, s.substring(0, s.indexOf('#')), "method",
                    s.substring(s.indexOf('#') + 1), "()Z", "isSprinting", "datawatcher-3");
            ev.append("isSprinting=").append(s).append("; ");
        } else {
            h.auto = false;
            ctx.review.add("sprint: isSprinting-кандидатов=" + sprinting.size() + " " + sprinting);
        }
        // 2. isSneaking: тривиальный ()Z-геттер Z иерархии, зовомый из кода
        // движения: (DDD*)-методы корня, travel (FFF)V или update-метод,
        // зовущий travel. Единственный такой — AUTO.
        Set<String> moveCallers = new HashSet<String>();
        ClassInfo rootc = idx.get(root);
        if (rootc != null) {
            for (ClassInfo.MethodInfo m : rootc.methods) {
                if (m.desc.indexOf("(DDD") < 0) continue;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if ("()Z".equals(mr.desc)) moveCallers.add(mr.owner + "#" + mr.name);
                }
            }
        }
        RoleHit trav = ctx.byRole("travel");
        String travelMeth = null;
        for (RoleHit.Member mm : trav.members) {
            if ("travel".equals(mm.role)) travelMeth = mm.name;
        }
        Set<String> updateCallers = new HashSet<String>();
        if (travelMeth != null) {
            for (ClassInfo ci : idx.all().values()) {
                if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    boolean callsTravel = false;
                    for (ClassInfo.MethodRef mr : m.methodRefs) {
                        if (trav.cls.equals(mr.owner) && (travelMeth + "(FFF)V").equals(mr.name + mr.desc)) {
                            callsTravel = true;
                            break;
                        }
                    }
                    if (!callsTravel) continue;
                    for (ClassInfo.MethodRef mr : m.methodRefs) {
                        if ("()Z".equals(mr.desc)) updateCallers.add(mr.owner + "#" + mr.name);
                    }
                    // сам travel: ()Z-вызовы внутри
                    if (trav.cls.equals(ci.name) && ("(FFF)V").equals(m.desc) && travelMeth.equals(m.name)) {
                        for (ClassInfo.MethodRef mr : m.methodRefs) {
                            if ("()Z".equals(mr.desc)) updateCallers.add(mr.owner + "#" + mr.name);
                        }
                    }
                }
            }
        }
        List<String> sneaking = new ArrayList<String>();
        Set<String> allCallers = new HashSet<String>(moveCallers);
        allCallers.addAll(updateCallers);
        for (String key : allCallers) {
            String owner = key.substring(0, key.indexOf('#'));
            String mname = key.substring(key.indexOf('#') + 1);
            String decl = RoleUtil.resolveMethod(idx, owner, mname, "()Z");
            if (decl == null) continue;
            ClassInfo dc = idx.get(decl);
            boolean trivialGetter = false;
            String fld = null;
            for (ClassInfo.MethodInfo mm : dc.methods) {
                if (!mname.equals(mm.name) || !"()Z".equals(mm.desc)) continue;
                if (!mm.methodRefs.isEmpty()) continue;
                Set<String> zf = new HashSet<String>();
                for (ClassInfo.FieldRef fr : mm.fieldRefs) {
                    if ((fr.opcode == Opcodes.GETFIELD || fr.opcode == Opcodes.GETSTATIC)
                            && "Z".equals(fr.desc) && inHierarchy(idx, fr.owner, ep))
                        zf.add(fr.owner + "#" + fr.name);
                }
                if (zf.size() == 1) { trivialGetter = true; fld = zf.iterator().next(); }
            }
            if (trivialGetter) {
                String entry = decl + "#" + mname + "#" + fld;
                if (!sneaking.contains(entry)) sneaking.add(entry);
            }
        }
        if (sneaking.size() == 1) {
            String[] p = sneaking.get(0).split("#");
            RoleUtil.addMember(h, p[0], "method", p[1], "()Z", "isSneaking", "move-trivial-getter");
            RoleUtil.addMember(h, p[2], "field", p[3], "Z",
                    "sneakFlag", "move-trivial-getter");
            ev.append("isSneaking=").append(sneaking.get(0)).append("; ");
        } else {
            h.auto = false;
            ctx.review.add("sprint: isSneaking-кандидатов=" + sneaking.size() + " " + sneaking);
        }
        // 3. setSprint: единственный (Z)V EP, пишущий Z иерархии
        String writer = null;
        String wfield = null;
        int wn = 0;
        ClassInfo epic = idx.get(ep);
        if (epic != null) {
            for (ClassInfo.MethodInfo m : epic.methods) {
                if (!"(Z)V".equals(m.desc)) continue;
                Set<String> puts = new HashSet<String>();
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == Opcodes.PUTFIELD || fr.opcode == Opcodes.PUTSTATIC)
                            && "Z".equals(fr.desc) && inHierarchy(idx, fr.owner, ep))
                        puts.add(fr.owner + "#" + fr.name);
                }
                if (puts.size() == 1) {
                    wn++;
                    writer = m.name;
                    wfield = puts.iterator().next();
                }
            }
        }
        if (wn == 1) {
            RoleUtil.addMember(h, ep, "method", writer, "(Z)V", "setSprint", "unique-Z-writer");
            RoleUtil.addMember(h, ep, "field", wfield.substring(wfield.indexOf('#') + 1), "Z",
                    "sprintFlag", "unique-Z-writer");
            ev.append("setSprint=").append(writer).append("->").append(wfield).append("; ");
        } else {
            h.auto = false;
            ctx.review.add("sprint:" + ep + ": (Z)V-писателей=" + wn);
        }
        h.evidence = ev.toString();
        ctx.hits.add(h);
    }

    /** X — сам EP или его предок из дампа (унаследованные члены видны в EP). */
    static boolean inHierarchy(DumpIndex idx, String x, String cls) {
        Set<String> seen = new HashSet<String>();
        String cur = cls;
        while (cur != null && seen.add(cur)) {
            if (cur.equals(x)) return true;
            ClassInfo ci = idx.get(cur);
            if (ci == null) return false;
            cur = ci.superName;
        }
        return false;
    }
}
