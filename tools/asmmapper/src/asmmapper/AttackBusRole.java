package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * eventBus: шина модовых событий — Map-поле + post(Object) +
 * register(Class, Function1/Consumer). Статика не требуется (может быть
 * синглтон-инстанс) — но тогда нужен static доступ (поле/геттер себя).
 */
public final class AttackBusRole implements Role {
    public String name() { return "eventBus"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> cands = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            boolean hasMap = false;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if ("Ljava/util/Map;".equals(f.desc)
                        || "Ljava/util/HashMap;".equals(f.desc)
                        || "Ljava/util/concurrent/ConcurrentHashMap;".equals(f.desc)) {
                    hasMap = true;
                    break;
                }
            }
            if (!hasMap) continue;
            String post = null;
            String postDesc = null;
            String reg = null;
            String regDesc = null;
            for (ClassInfo.MethodInfo m : ci.methods) {
                int rp = m.desc.indexOf(')');
                if (rp < 0) continue;
                String params = m.desc.substring(0, rp + 1);
                String ret = m.desc.substring(rp + 1);
                boolean voidLike = "V".equals(ret) || "Lkotlin/Unit;".equals(ret);
                if (!voidLike) continue;
                if ("(Ljava/lang/Object;)".equals(params)) { post = m.name; postDesc = m.desc; }
                if ("(Ljava/lang/Class;Lkotlin/jvm/functions/Function1;)".equals(params)
                        || "(Ljava/lang/Class;Ljava/util/function/Consumer;)".equals(params)) {
                    reg = m.name;
                    regDesc = m.desc;
                }
            }
            if (post == null || reg == null) continue;
            // доступ: static-члены или синглтон
            boolean accessible = false;
            for (ClassInfo.MethodInfo m : ci.methods) {
                int rp = m.desc.indexOf(')');
                if (rp < 0) continue;
                if ((post.equals(m.name) && postDesc.equals(m.desc)
                        || reg.equals(m.name) && regDesc.equals(m.desc)) && m.isStatic)
                    accessible = true;
            }
            if (!accessible) {
                for (ClassInfo.FieldInfo f : ci.fields) {
                    if (f.isStatic && ("L" + ci.name + ";").equals(f.desc)) accessible = true;
                }
                for (ClassInfo.MethodInfo m : ci.methods) {
                    if (!m.isStatic) continue;
                    int rp = m.desc.indexOf(')');
                    if (rp < 0) continue;
                    if ("()".equals(m.desc.substring(0, rp + 1))
                            && ("L" + ci.name + ";").equals(m.desc.substring(rp + 1)))
                        accessible = true;
                }
            }
            if (accessible) cands.add(ci.name + "#" + post + "#" + postDesc
                    + "#" + reg + "#" + regDesc);
        }
        if (cands.size() == 1) {
            String[] p = cands.get(0).split("#");
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = p[0];
            h.auto = true;
            h.evidence = "static Map + post(Object) + register(Class,listener)";
            RoleUtil.addMember(h, p[0], "method", p[1], p[2],
                    "post", "bus-shape");
            RoleUtil.addMember(h, p[0], "method", p[3], p[4],
                    "register", "bus-shape");
            ctx.hits.add(h);
        } else {
            // диагностика: static-Map классы и их static post/register-шейпы
            List<String> maps = new ArrayList<String>();
            for (ClassInfo ci : idx.all().values()) {
                if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
                boolean hasMap = false;
                for (ClassInfo.FieldInfo f : ci.fields) {
                    if (f.isStatic && ("Ljava/util/Map;".equals(f.desc)
                            || "Ljava/util/HashMap;".equals(f.desc)
                            || "Ljava/util/concurrent/ConcurrentHashMap;".equals(f.desc))) {
                        hasMap = true;
                        break;
                    }
                }
                if (!hasMap) continue;
                StringBuilder sb = new StringBuilder(ci.name);
                for (ClassInfo.MethodInfo m : ci.methods) {
                    if (!m.isStatic) continue;
                    int rp = m.desc.indexOf(')');
                    if (rp < 0) continue;
                    String params = m.desc.substring(0, rp + 1);
                    String ret = m.desc.substring(rp + 1);
                    boolean voidLike = "V".equals(ret) || "Lkotlin/Unit;".equals(ret);
                    if (!voidLike) continue;
                    if ("(Ljava/lang/Object;)".equals(params)
                            || params.startsWith("(Ljava/lang/Class;")) {
                        sb.append(' ').append(m.name).append(m.desc);
                    }
                }
                if (maps.size() < 25) maps.add(sb.toString());
            }
            ctx.review.add("eventBus: кандидатов=" + cands.size() + " staticMaps=" + maps);
        }
    }
}
