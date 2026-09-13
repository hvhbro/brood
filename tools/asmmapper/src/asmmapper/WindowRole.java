package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * window: провайдер хендла окна — static no-arg ()J, используемый кодом,
 * зовущим org.lwjgl.glfw.GLFW (клавиши/мышь, стабильные имена).
 */
public final class WindowRole implements Role {
    public String name() { return "window"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> cands = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!m.isStatic || !"()J".equals(m.desc)) continue;
                // зовётся из кода с GLFW-вызовами?
                boolean fromGlfw = false;
                for (ClassInfo ci2 : idx.all().values()) {
                    if (!ctx.isLive(ci2.name)) continue;
                    for (ClassInfo.MethodInfo m2 : ci2.methods) {
                        boolean callsM = false;
                        boolean callsGlfw = false;
                        for (ClassInfo.MethodRef mr : m2.methodRefs) {
                            if (ci.name.equals(mr.owner)
                                    && (m.name + "()J").equals(mr.name + mr.desc))
                                callsM = true;
                            if (mr.owner.startsWith("org/lwjgl/glfw/GLFW")) callsGlfw = true;
                        }
                        if (callsM && callsGlfw) { fromGlfw = true; break; }
                    }
                    if (fromGlfw) break;
                }
                if (fromGlfw) cands.add(ci.name + "#" + m.name);
            }
        }
        if (cands.size() == 1) {
            String[] p = cands.get(0).split("#");
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = p[0];
            h.auto = true;
            h.evidence = "static ()J из GLFW-кода";
            RoleUtil.addMember(h, p[0], "method", p[1], "()J", "getWindowHandle", "glfw-context");
            ctx.hits.add(h);
        } else {
            ctx.review.add("window: кандидатов=" + cands.size() + " " + cands);
        }
    }
}
