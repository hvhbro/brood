package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * strafeModifiers: apply/removeAttributeModifier в классе инстанса —
 * два (Lmod;)V. Класс модификатора — из noSlow-роли. Пустая тела-метода
 * (bytecode 0) не дают put/remove-сигнала — fallback: единственная пара
 * (Lmod;)V как candidates на рантайм-проверку (Strafe сам проверит).
 */
public final class StrafeModRole implements Role {
    public String name() { return "strafeModifiers"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit attr = ctx.byRole("movementSpeedAttr");
        if (attr == null) {
            ctx.review.add("strafeModifiers: нет movementSpeedAttr — не от чего строить линк");
            return;
        }
        String inst = null;
        for (RoleHit.Member m : attr.members) {
            if ("getValue".equals(m.role)) inst = m.cls;
        }
        if (inst == null) return;
        ClassInfo ic = idx.get(inst);
        if (ic == null) return;
        java.util.Map<String, List<String>> byMod = new java.util.HashMap<String, List<String>>();
        for (ClassInfo.MethodInfo m : ic.methods) {
            int rp = m.desc.indexOf(')');
            if (rp < 0) continue;
            List<String> params = new ArrayList<String>(
                    DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
            if (params.size() != 1 || !params.get(0).startsWith("rustme/")) continue;
            if (!"V".equals(m.desc.substring(rp + 1))) continue;
            String mod = params.get(0);
            if (!byMod.containsKey(mod)) byMod.put(mod, new ArrayList<String>());
            byMod.get(mod).add(m.name);
        }
        List<String> winners = new ArrayList<String>();
        List<String> modTakers = new ArrayList<String>();
        // сначала в самом классе (put/remove там если тела не пустые)
        collectPairs(idx, inst, winners, modTakers);
        // затем в наследниках (apply/remove бывают абстрактными в базе)
        if (winners.isEmpty()) {
            for (ClassInfo ci : idx.all().values()) {
                if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
                if (!RoleUtil.isSubclassOf(idx, ci.name, inst)) continue;
                collectPairs(idx, ci.name, winners, modTakers);
            }
        }
        // put/remove не в байткоде (пустые тела): fallback — единственная пара (Lmod;)V
        if (winners.isEmpty()) {
            for (java.util.Map.Entry<String, List<String>> e : byMod.entrySet()) {
                if (e.getValue().size() != 2) continue;
                winners.add(e.getKey() + "#" + e.getValue().get(0) + "#" + e.getValue().get(1));
                modTakers.add(e.getKey() + e.getValue().toString() + "[fallback-unique-pair]");
            }
        }
        if (winners.size() == 1) {
            String[] p = winners.get(0).split("#");
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = inst;
            h.auto = true;
            h.evidence = "пара для " + p[0] + (p.length > 3 ? "" : " [fallback]");
            RoleUtil.addMember(h, inst, "method", p[1], "(L" + p[0] + ";)V",
                    "applyModifier", "mod-pair");
            RoleUtil.addMember(h, inst, "method", p[2], "(L" + p[0] + ";)V",
                    "removeModifier", "mod-pair");
            ctx.hits.add(h);
        } else {
            ctx.review.add("strafeModifiers:" + inst + ": пар=" + winners.size() + " " + winners
                    + " modTakers=" + modTakers);
        }
    }

    static void collectPairs(DumpIndex idx, String searchCls,
                             List<String> winners, List<String> modTakers) {
        ClassInfo ic = idx.get(searchCls);
        if (ic == null) return;
        java.util.Map<String, List<String>> byMod = new java.util.HashMap<String, List<String>>();
        for (ClassInfo.MethodInfo m : ic.methods) {
            int rp = m.desc.indexOf(')');
            if (rp < 0) continue;
            List<String> params = new ArrayList<String>(
                    DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
            if (params.size() != 1 || !params.get(0).startsWith("rustme/")) continue;
            if (!"V".equals(m.desc.substring(rp + 1))) continue;
            String mod = params.get(0);
            if (!byMod.containsKey(mod)) byMod.put(mod, new ArrayList<String>());
            byMod.get(mod).add(m.name);
        }
        for (java.util.Map.Entry<String, List<String>> e : byMod.entrySet()) {
            if (e.getValue().size() < 2) continue;
            String putter = null;
            String remover = null;
            for (String mn : e.getValue()) {
                for (ClassInfo.MethodInfo m : ic.methods) {
                    if (!mn.equals(m.name)) continue;
                    for (ClassInfo.MethodRef mr : m.methodRefs) {
                        if ("put".equals(mr.name)) putter = mn;
                        if ("remove".equals(mr.name)) remover = mn;
                    }
                }
            }
            if (putter != null && remover != null && !putter.equals(remover))
                winners.add(e.getKey() + "#" + putter + "#" + remover);
            else if (modTakers.size() < 8)
                modTakers.add(searchCls + ":" + e.getKey() + e.getValue().toString());
        }
    }
}
