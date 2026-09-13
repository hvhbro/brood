package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * icons: система иконок предметов — helper с (stack)->rl ×2 (иконка+скин),
 * sys с getIcon (rl,String)->icon, icon с 4 float UV + ()I texId.
 * Стек — из itemStack-роли, rl — из resourceLocation-роли.
 */
public final class IconRole implements Role {
    public String name() { return "icons"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit ri = ctx.byRole("renderItem");
        RoleHit rl = ctx.byRole("resourceLocation");
        if (ri == null || rl == null) {
            ctx.review.add("icons: нет renderItem/resourceLocation — не от чего строить линк");
            return;
        }
        String stack = null;
        for (RoleHit.Member m : ri.members) {
            if ("itemStack".equals(m.role)) stack = m.cls;
        }
        if (stack == null) return;
        // icon-класс: 4 float-поля (U0/V0/U1/V1) + ()I
        List<String> icons = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            int floats = 0;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (!f.isStatic && "F".equals(f.desc)) floats++;
            }
            if (floats < 4) continue;
            boolean hasI = false;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if ("()I".equals(m.desc)) { hasI = true; break; }
            }
            if (hasI) icons.add(ci.name);
        }
        // sys: метод (rl,String)->icon
        List<String> sysHits = new ArrayList<String>();
        for (String icon : icons) {
            for (ClassInfo ci : idx.all().values()) {
                if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    int rp = m.desc.indexOf(')');
                    if (rp < 0) continue;
                    List<String> params = new ArrayList<String>(
                            DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
                    String ret = m.desc.substring(rp + 1);
                    if (params.size() == 2 && params.get(0).equals(rl.cls)
                            && "Ljava/lang/String;".equals(params.get(1))
                            || params.size() == 2 && params.contains(rl.cls)
                            && ("L" + icon + ";").equals(ret)) {
                        sysHits.add(ci.name + "." + m.name + m.desc + "->" + icon);
                    }
                }
            }
        }
        // helper: методы (stack)->rl, >=2 (иконка и скин)
        List<String> helpers = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            int n = 0;
            for (ClassInfo.MethodInfo m : ci.methods) {
                int rp = m.desc.indexOf(')');
                if (rp < 0) continue;
                List<String> params = new ArrayList<String>(
                        DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
                String ret = m.desc.substring(rp + 1);
                if (params.size() == 1 && params.get(0).equals(stack)
                        && ("L" + rl.cls + ";").equals(ret)) n++;
            }
            if (n >= 2) helpers.add(ci.name);
        }
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = "";
        h.auto = true;
        h.evidence = "icons=" + icons.size() + " sys=" + sysHits + " helpers=" + helpers;
        if (icons.size() == 1) {
            h.cls = icons.get(0);
            RoleUtil.addMember(h, icons.get(0), "class", shortName(icons.get(0)),
                    "L" + icons.get(0) + ";", "icon", "4F+()I");
        } else {
            h.auto = false;
        }
        if (sysHits.size() == 1) {
            String s = sysHits.get(0);
            String oc = s.substring(0, s.indexOf('.'));
            String rest = s.substring(s.indexOf('.') + 1);
            String mn = rest.substring(0, rest.indexOf('('));
            RoleUtil.addMember(h, oc, "method", mn, rest.substring(rest.indexOf('(')),
                    "getIcon", "rl-string-to-icon");
            if (h.cls.length() == 0) h.cls = oc;
        } else {
            h.auto = false;
        }
        if (helpers.size() == 1) {
            if (h.cls.length() == 0) h.cls = helpers.get(0);
            RoleUtil.addMember(h, helpers.get(0), "class", shortName(helpers.get(0)),
                    "L" + helpers.get(0) + ";", "iconHelper", "two-stack-to-rl");
        } else {
            h.auto = false;
        }
        if (!h.auto) {
            ctx.review.add("icons: icons=" + icons + " sys=" + sysHits + " helpers=" + helpers);
        }
        ctx.hits.add(h);
    }

    private static String shortName(String internal) {
        int i = internal.lastIndexOf('/');
        return i >= 0 ? internal.substring(i + 1) : internal;
    }
}
