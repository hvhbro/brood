package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * resourceLocation: (String)V + (String,String)V ctor (путь[/домен] ресурса).
 * Ванильная форма, стабильна.
 */
public final class ResourceRole implements Role {
    public String name() { return "resourceLocation"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> cands = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            boolean s1 = false;
            boolean s2 = false;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"<init>".equals(m.name)) continue;
                if ("(Ljava/lang/String;)V".equals(m.desc)) s1 = true;
                if ("(Ljava/lang/String;Ljava/lang/String;)V".equals(m.desc)) s2 = true;
            }
            if (s1 && s2) cands.add(ci.name);
        }
        if (cands.size() > 1) {
            // тайбрейк: настоящий RL — Comparable (рабочая модель;
            // пустой wrapper-наследник тоже equals, но не Comparable)
            List<String> withEq = new ArrayList<String>();
            for (String c : cands) {
                ClassInfo ci = idx.get(c);
                if (ci == null) continue;
                boolean eq = false;
                boolean hc = false;
                boolean cmp = false;
                for (String itf : ci.interfaces) {
                    if ("java/lang/Comparable".equals(itf)) cmp = true;
                }
                for (ClassInfo.MethodInfo m : ci.methods) {
                    if ("equals(Ljava/lang/Object;)Z".equals(m.name + m.desc)) eq = true;
                    if ("hashCode()I".equals(m.name + m.desc)) hc = true;
                }
                if (eq && hc && cmp) withEq.add(c);
            }
            if (withEq.size() == 1) cands = withEq;
        }
        if (cands.size() > 1) {
            // второй тайбрейк: создаётся кодом с двумя String-аргументами
            List<String> withStrPair = new ArrayList<String>();
            for (String c : cands) {
                for (ClassInfo ci : idx.all().values()) {
                    if (!ctx.isLive(ci.name)) continue;
                    for (ClassInfo.MethodInfo m : ci.methods) {
                        if (m.news.contains(c) && m.desc.contains("Ljava/lang/String;")) {
                            if (!withStrPair.contains(c)) withStrPair.add(c);
                        }
                    }
                }
            }
            if (withStrPair.size() == 1) cands = withStrPair;
        }
        if (cands.size() == 1) {
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = cands.get(0);
            h.auto = true;
            h.evidence = "ctor(String)+ctor(String,String)";
            ctx.hits.add(h);
        } else {
            ctx.review.add("resourceLocation: кандидатов=" + cands.size() + " " + cands);
        }
    }
}
