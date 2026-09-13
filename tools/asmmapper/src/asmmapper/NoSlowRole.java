package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * noSlow: зелья + модификаторы атрибутов + aim-флаг + riding.
 * Potion-класс: static (String)->self (реестр зелий, ванильный отпечаток).
 * В EntityPlayer: методы с ровно 1 potion-параметром (isPotionActive/remove).
 * Модификатор: getModifiers()Ljava/util/Collection<Modifier;> у инстанса
 * (movementSpeedAttr-роль) -> класс элемента -> ()D amount + ()I operation.
 * aimFlag: ()Z в EP с константой 7 (dataWatcher hand-flags).
 * riding: ()Z в EP-иерархии с единственным Object-getfield и без вызовов.
 * Неуникальное — в REVIEW, роль не врёт.
 */
public final class NoSlowRole implements Role {
    public String name() { return "noSlow"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit travel = ctx.byRole("travel");
        RoleHit attr = ctx.byRole("movementSpeedAttr");
        if (travel == null) {
            ctx.review.add("noSlow: нет travel — не от чего строить линк");
            return;
        }
        String ep = travel.cls;
        ClassInfo epic = idx.get(ep);
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = ep;
        h.auto = true;
        StringBuilder ev = new StringBuilder();
        // 1. potion: тип X, методы EP с X-параметром покрывают 3 формы:
        // (X)Z (isActive), (X)V (remove), (X)->Y (getEffect). Ванильный набор.
        Map<String, Set<String>> shapes = new HashMap<String, Set<String>>();
        Map<String, List<String>> shapeEv = new HashMap<String, List<String>>();
        for (ClassInfo.MethodInfo m : epic.methods) {
            int rp = m.desc.indexOf(')');
            if (rp < 0) continue;
            List<String> params = new ArrayList<String>(
                    DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
            if (params.size() != 1 || !params.get(0).startsWith("rustme/")) continue;
            String ret = m.desc.substring(rp + 1);
            String shape;
            if ("Z".equals(ret)) shape = "Z";
            else if ("V".equals(ret)) shape = "V";
            else if (ret.startsWith("L")) shape = "Y";
            else continue;
            String x = params.get(0);
            if (!shapes.containsKey(x)) {
                shapes.put(x, new HashSet<String>());
                shapeEv.put(x, new ArrayList<String>());
            }
            shapes.get(x).add(shape);
            shapeEv.get(x).add(m.name + m.desc);
        }
        List<String> potionClss = new ArrayList<String>();
        for (Map.Entry<String, Set<String>> e : shapes.entrySet()) {
            if (e.getValue().size() == 3) potionClss.add(e.getKey());
        }
        if (potionClss.size() > 1) {
            // тайбрейк: у настоящего Potion есть реестр static (String)->self
            List<String> withReg = new ArrayList<String>();
            for (String x : potionClss) {
                ClassInfo ci = idx.get(x);
                if (ci == null) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    if (m.isStatic
                            && ("(Ljava/lang/String;)L" + x + ";").equals(m.desc)) {
                        withReg.add(x);
                        break;
                    }
                }
            }
            if (withReg.size() == 1) potionClss = withReg;
        }
        if (potionClss.size() == 1) {
            String pcls = potionClss.get(0);
            RoleUtil.addMember(h, pcls, "class", pcls, "L" + pcls + ";", "potion", "shape-ZVY");
            ev.append("potion=").append(pcls).append(shapeEv.get(pcls)).append("; ");
            for (String mm : shapeEv.get(pcls)) {
                int p = mm.indexOf('(');
                String rd = mm.substring(p);
                String role = rd.endsWith(")Z") ? "isPotionActive"
                        : rd.endsWith(")V") ? "removePotionEffect"
                        : "getPotionEffect";
                RoleUtil.addMember(h, ep, "method", mm.substring(0, p), rd,
                        role, "potion-param");
            }
            // potionByName: static (String)->potion в классе зелья
            ClassInfo pc = idx.get(pcls);
            if (pc != null) {
                for (ClassInfo.MethodInfo m : pc.methods) {
                    if (m.isStatic && ("(Ljava/lang/String;)L" + pcls + ";").equals(m.desc)) {
                        RoleUtil.addMember(h, pcls, "method", m.name, m.desc,
                                "potionByName", "static-string-self");
                        break;
                    }
                }
            }
        } else {
            h.auto = false;
            ctx.review.add("noSlow: potionClss(форма Z+V+Y)=" + potionClss + " shapes=" + shapes);
        }
        // 3. модификатор: тип единственного параметра, общего у >=2 методов
        // инстанса (apply/removeModifier), + getModifiers()Collection
        if (attr != null) {
            String inst = null;
            for (RoleHit.Member m : attr.members) {
                if ("getValue".equals(m.role)) inst = m.cls;
            }
            if (inst != null) {
                ClassInfo ic = idx.get(inst);
                String modCls = null;
                if (ic != null) {
                    Map<String, Integer> paramUse = new HashMap<String, Integer>();
                    for (ClassInfo.MethodInfo m : ic.methods) {
                        int rp = m.desc.indexOf(')');
                        if (rp < 0) continue;
                        List<String> params = new ArrayList<String>(
                                DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
                        if (params.size() == 1 && params.get(0).startsWith("rustme/")) {
                            Integer c = paramUse.get(params.get(0));
                            paramUse.put(params.get(0), c == null ? 1 : c + 1);
                        }
                    }
                    int best = 0;
                    for (Map.Entry<String, Integer> e : paramUse.entrySet()) {
                        if (e.getValue() > best) { best = e.getValue(); modCls = e.getKey(); }
                    }
                    if (best < 2) modCls = null;
                }
                String mods = null;
                if (ic != null) {
                    for (ClassInfo.MethodInfo m : ic.methods) {
                        if ("()Ljava/util/Collection;".equals(m.desc)) { mods = m.name; break; }
                    }
                }
                if (mods != null && modCls != null) {
                    RoleUtil.addMember(h, inst, "method", mods, "()Ljava/util/Collection;",
                            "getModifiers", "collection-ret");
                    ClassInfo mc = idx.get(modCls);
                    if (mc != null) {
                        String amount = uniqueDesc(mc, "()D");
                        String op = uniqueDesc(mc, "()I");
                        if (amount != null)
                            RoleUtil.addMember(h, modCls, "method", amount, "()D",
                                    "modAmount", "unique-D");
                        if (op != null)
                            RoleUtil.addMember(h, modCls, "method", op, "()I",
                                    "modOperation", "unique-I");
                        if (amount == null || op == null)
                            ctx.review.add("noSlow:" + modCls + ": amount/op не уникальны");
                    }
                    ev.append("modifier=").append(modCls).append("; ");
                } else {
                    ctx.review.add("noSlow:" + inst + ": getModifiers не найден");
                }
            }
        }
        // 4. aimFlag: ()Z с const 7 в EP или его предках (dataWatcher hand-flags)
        List<String> aim = new ArrayList<String>();
        Set<String> seenH = new HashSet<String>();
        String curH = ep;
        while (curH != null && seenH.add(curH)) {
            ClassInfo ci = idx.get(curH);
            if (ci == null) break;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"()Z".equals(m.desc)) continue;
                for (Integer c : m.constInts) {
                    if (c == 7) { aim.add(ci.name + "#" + m.name); break; }
                }
            }
            curH = ci.superName;
        }
        if (aim.size() == 1) {
            String a = aim.get(0);
            RoleUtil.addMember(h, a.substring(0, a.indexOf('#')), "method",
                    a.substring(a.indexOf('#') + 1), "()Z", "aimFlag", "const-7");
            ev.append("aim=").append(a).append("; ");
        } else {
            ctx.review.add("noSlow:" + ep + ": aimFlag-кандидатов=" + aim.size() + " " + aim);
        }
        // 5. riding: ()Z иерархии EP с единственным Object-getfield и без вызовов
        List<String> riding = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            if (!SprintRole.inHierarchy(idx, ci.name, ep)) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"()Z".equals(m.desc) || !m.methodRefs.isEmpty()) continue;
                Set<String> of = new HashSet<String>();
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == Opcodes.GETFIELD || fr.opcode == Opcodes.GETSTATIC)
                            && fr.desc.startsWith("L")) of.add(fr.owner + "#" + fr.name);
                }
                if (of.size() == 1) riding.add(ci.name + "#" + m.name);
            }
        }
        if (riding.size() == 1) {
            String r = riding.get(0);
            RoleUtil.addMember(h, r.substring(0, r.indexOf('#')), "method",
                    r.substring(r.indexOf('#') + 1), "()Z", "riding", "nullcheck-shape");
            ev.append("riding=").append(r).append("; ");
        } else {
            ctx.review.add("noSlow: riding-кандидатов=" + riding.size());
        }
        h.evidence = ev.toString();
        ctx.hits.add(h);
    }

    private static String uniqueDesc(ClassInfo ci, String desc) {
        String found = null;
        int n = 0;
        for (ClassInfo.MethodInfo m : ci.methods) {
            if (!desc.equals(m.desc)) continue;
            // Object-бойлерплейт не в счёт (equals/hashCode/toString)
            if ("equals".equals(m.name) || "hashCode".equals(m.name)
                    || "toString".equals(m.name)) continue;
            n++;
            found = m.name;
        }
        return n == 1 ? found : null;
    }

    /** Простой мультимап для evidence. */
    static final class Map2 {
        final List<String> keys = new ArrayList<String>();
        final List<String> vals = new ArrayList<String>();
        void add(String k, String v) { keys.add(k); vals.add(v); }
        int total() { return vals.size(); }
        List<String> all() { return vals; }
        public String toString() { return keys.toString() + vals.toString(); }
    }
}
