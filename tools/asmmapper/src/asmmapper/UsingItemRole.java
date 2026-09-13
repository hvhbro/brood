package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * usingItem: активное использование предмета (еда/лук/ADS-база) —
 * ()Z в EP-иерархии, читающий stack-поле и зовущий stack.isEmpty()
 * (пустые руки = не использует). Класс стака — из itemStack-роли.
 */
public final class UsingItemRole implements Role {
    public String name() { return "usingItem"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit travel = ctx.byRole("travel");
        RoleHit ri = ctx.byRole("renderItem");
        if (travel == null || ri == null) {
            ctx.review.add("usingItem: нет travel/renderItem — не от чего строить линк");
            return;
        }
        String stack = null;
        String isEmpty = null;
        for (RoleHit.Member m : ri.members) {
            if ("itemStack".equals(m.role)) stack = m.cls;
        }
        for (RoleHit.Member m : ctx.byRole("itemStack") != null
                ? ctx.byRole("itemStack").members : new ArrayList<RoleHit.Member>()) {
            if ("isEmpty".equals(m.role)) isEmpty = m.name;
        }
        if (stack == null || isEmpty == null) {
            ctx.review.add("usingItem: нет itemStack/isEmpty — не от чего строить линк");
            return;
        }
        String ep = travel.cls;
        List<String> cands = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            if (!SprintRole.inHierarchy(idx, ci.name, ep)) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"()Z".equals(m.desc)) continue;
                boolean readsStack = false;
                boolean callsEmpty = false;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if (("L" + stack + ";").equals(fr.desc)) readsStack = true;
                }
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if ((isEmpty + "()Z").equals(mr.name + mr.desc)) callsEmpty = true;
                }
                if (readsStack && callsEmpty) cands.add(ci.name + "#" + m.name);
            }
        }
        if (cands.size() == 1) {
            String[] p = cands.get(0).split("#");
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = p[0];
            h.auto = true;
            h.evidence = "stack-поле + isEmpty в EP-иерархии";
            RoleUtil.addMember(h, p[0], "method", p[1], "()Z", "isUsingItem", "stack-empty-check");
            ctx.hits.add(h);
        } else {
            ctx.review.add("usingItem: кандидатов=" + cands.size() + " " + cands);
        }
    }
}
