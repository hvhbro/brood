package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * equipSlot: getItemStackFromSlot в wrapper — метод с ровно 1 объект-параметром,
 * возвращающий ItemStack (класс из renderItem-роли). Тип параметра — слот
 * (наследник Enum). values() у enum не обфусцируется — маппим только класс+геттер.
 */
public final class EquipSlotRole implements Role {
    public String name() { return "equipSlot"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit ph = ctx.byRole("playerHierarchy");
        RoleHit ri = ctx.byRole("renderItem");
        if (ph == null || ri == null) {
            ctx.review.add("equipSlot: нет playerHierarchy/renderItem — не от чего строить линк");
            return;
        }
        String stack = null;
        for (RoleHit.Member m : ri.members) {
            if ("itemStack".equals(m.role)) stack = m.cls;
        }
        if (stack == null) return;
        ClassInfo wc = idx.get(ph.cls);
        if (wc == null) return;
        List<String> cands = new ArrayList<String>();
        for (ClassInfo.MethodInfo m : wc.methods) {
            int rp = m.desc.indexOf(')');
            if (rp < 0) continue;
            List<String> params = new ArrayList<String>(
                    DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
            String ret = m.desc.substring(rp + 1);
            if (params.size() == 1 && ("L" + stack + ";").equals(ret)) {
                cands.add(params.get(0) + "#" + m.name + "#" + m.desc);
            }
        }
        // слот обязан быть enum'ом
        List<String> enums = new ArrayList<String>();
        for (String c : cands) {
            String slot = c.substring(0, c.indexOf('#'));
            ClassInfo ci = idx.get(slot);
            if (ci != null && "java/lang/Enum".equals(ci.superName)) enums.add(c);
        }
        if (enums.size() == 1) {
            String[] p = enums.get(0).split("#");
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = p[0];
            h.auto = true;
            h.evidence = "wrapper." + p[1] + " -> ItemStack " + stack;
            RoleUtil.addMember(h, ph.cls, "method", p[1], p[2], "getItemStackFromSlot", "slot-to-stack");
            ctx.hits.add(h);
        } else {
            ctx.review.add("equipSlot:" + ph.cls + ": кандидатов=" + enums.size() + " " + enums);
        }
    }
}
