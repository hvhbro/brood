package asmmapper;

import java.util.HashSet;
import java.util.Set;

/** world: ctor-параметр EntityPlayer (связь, не счётчики). */
public final class WorldRole implements Role {
    public String name() { return "world"; }

    /** Структурный признак World: есть поле-список сущностей (без счётчиков). */
    public static boolean hasListField(DumpIndex idx, String cls) {
        ClassInfo ci = idx.get(cls);
        if (ci == null) return false;
        for (ClassInfo.FieldInfo f : ci.fields) {
            if ("Ljava/util/List;".equals(f.desc)) return true;
        }
        return false;
    }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit ep = ctx.byRole("travel");
        if (ep == null) {
            ctx.review.add("world: нет travel-роли — не от чего строить линк");
            return;
        }
        ClassInfo epc = idx.get(ep.cls);
        Set<String> cands = new HashSet<String>();
        for (ClassInfo.MethodInfo m : epc.methods) {
            if (!"<init>".equals(m.name)) continue;
            for (String p : DumpIndex.classesInDesc(m.desc)) {
                if (ctx.isLive(p) && hasListField(idx, p)) cands.add(p);
            }
        }
        if (cands.size() == 1) {
            String w = cands.iterator().next();
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = w;
            h.auto = true;
            h.evidence = "ctor-параметр EntityPlayer " + ep.cls + " + List-поле";
            ctx.hits.add(h);
        } else {
            ctx.review.add("world: кандидатов=" + cands.size() + " " + cands + " (нужен 1)");
        }
    }
}
