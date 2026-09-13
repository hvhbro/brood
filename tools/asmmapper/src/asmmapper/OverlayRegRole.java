package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * overlays: реестр иконок-масок — static хаб-поле + static no-arg геттер
 * реестра (Map) + мапа имя->спека. Спеки-наследники — INFO-списком
 * (heavy/diving различаются рендером шлема — на проверку глазами).
 */
public final class OverlayRegRole implements Role {
    public String name() { return "overlayRegistry"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> cands = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            boolean hasStaticHub = false;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (f.isStatic && f.desc.startsWith("L") && f.desc.endsWith(";")) {
                    String t = f.desc.substring(1, f.desc.length() - 1);
                    if (t.startsWith("rustme/") && !t.equals(ci.name)) hasStaticHub = true;
                }
            }
            if (!hasStaticHub) continue;
            String mapGetter = null;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!m.isStatic) continue;
                int rp = m.desc.indexOf(')');
                if (rp < 0) continue;
                if (!"()".equals(m.desc.substring(0, rp + 1))) continue;
                String ret = m.desc.substring(rp + 1);
                if ("Ljava/util/Map;".equals(ret)) { mapGetter = m.name; break; }
            }
            if (mapGetter != null) cands.add(ci.name + "#" + mapGetter);
        }
        if (cands.size() == 1) {
            String[] p = cands.get(0).split("#");
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = p[0];
            h.auto = true;
            h.evidence = "static hub + static ()Map";
            RoleUtil.addMember(h, p[0], "method", p[1], "()Ljava/util/Map;",
                    "getRegistry", "static-map-getter");
            ctx.hits.add(h);
        } else {
            ctx.review.add("overlayRegistry: кандидатов=" + cands.size() + " " + cands);
        }
    }
}
