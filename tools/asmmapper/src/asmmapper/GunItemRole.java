package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * gunItem: подкласс Item (iIiilIIIiI) для Hotbar/ADS.
 * Признак: метод (Lstack;)I, зовомый из хотбар-рендера (метод с const 9),
 * читает NBT через stack.getTag/getCompound — счётчик патронов.
 */
public final class GunItemRole implements Role {
    public String name() { return "gunItem"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit ri = ctx.byRole("renderItem");
        if (ri == null) {
            ctx.review.add("gunItem: нет renderItem — не от чего строить линк");
            return;
        }
        String stack = null;
        for (RoleHit.Member m : ri.members) if ("itemStack".equals(m.role)) stack = m.cls;
        if (stack == null) return;
        // base Item
        String itemBase = "rustme/iIiilIIIiI";
        // Gun = Item subclass, проверяемый через INSTANCEOF в хотбар-рендере (const 9)
        // Прямо или через 1 вызов (хотбар делегирует)
        List<String> cands = new ArrayList<String>();
        // предсобираем множество методов хотбара (с 9) и их прямых калле
        java.util.Set<String> hotbarMethods = new java.util.HashSet<String>();
        java.util.Set<String> hotbarCallees = new java.util.HashSet<String>();
        for (ClassInfo ci2 : idx.all().values()) {
            if (!ctx.isLive(ci2.name)) continue;
            for (ClassInfo.MethodInfo m2 : ci2.methods) {
                boolean has9 = false;
                for (Integer c : m2.constInts) if (c == 9) { has9 = true; break; }
                if (!has9) continue;
                hotbarMethods.add(ci2.name + "#" + m2.name + m2.desc);
                for (ClassInfo.MethodRef mr : m2.methodRefs) {
                    hotbarCallees.add(mr.owner + "#" + mr.name + mr.desc);
                }
            }
        }
        for (ClassInfo ci : idx.all().values()) {
            if (!itemBase.equals(ci.superName)) continue;
            if (!ctx.isLive(ci.name)) continue;
            boolean hotbarInstanceof = false;
            for (ClassInfo ci2 : idx.all().values()) {
                if (!ctx.isLive(ci2.name)) continue;
                for (ClassInfo.MethodInfo m2 : ci2.methods) {
                    boolean isHotbar = hotbarMethods.contains(ci2.name + "#" + m2.name + m2.desc)
                            || hotbarCallees.contains(ci2.name + "#" + m2.name + m2.desc);
                    if (!isHotbar) continue;
                    if (m2.instanceofs.contains(ci.name) || m2.checkcasts.contains(ci.name)) {
                        hotbarInstanceof = true; break;
                    }
                }
                if (hotbarInstanceof) break;
            }
            if (hotbarInstanceof) cands.add(ci.name);
        }
        if (cands.size() == 1) {
            String cls = cands.get(0);
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = cls;
            h.auto = true;
            h.evidence = "INSTANCEOF в хотбар-рендере (const 9)";
            RoleUtil.addMember(h, cls, "class", shortName(cls), "L" + cls + ";", "gunItem", "hotbar-instanceof");
            ctx.hits.add(h);
        } else {
            ctx.review.add("gunItem: кандидатов=" + cands.size() + " " + cands);
        }
    }

    private static String shortName(String internal) {
        int i = internal.lastIndexOf('/');
        return i >= 0 ? internal.substring(i + 1) : internal;
    }
}
