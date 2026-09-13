package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * killParticles: спавн частиц убийства — метод с rl-параметром и >=3 double
 * (позиция) в классе с Map/списком частиц. Косметика KillEffect,
 * не блокирует ядро — честный REVIEW при неоднозначности.
 */
public final class KillRole implements Role {
    public String name() { return "killParticles"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit rl = ctx.byRole("resourceLocation");
        if (rl == null) {
            ctx.review.add("killParticles: нет resourceLocation — не от чего строить линк");
            return;
        }
        List<String> cands = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                int rp = m.desc.indexOf(')');
                if (rp < 0) continue;
                List<String> params = new ArrayList<String>(
                        DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
                if (!params.contains(rl.cls)) continue;
                int dd = 0;
                for (int i = 1; i < rp; i++) {
                    if (m.desc.charAt(i) == 'D') dd++;
                }
                if (dd >= 3) cands.add(ci.name + "." + m.name + m.desc);
            }
        }
        if (cands.size() == 1) {
            String s = cands.get(0);
            String oc = s.substring(0, s.indexOf('.'));
            String rest = s.substring(s.indexOf('.') + 1);
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = oc;
            h.auto = true;
            h.evidence = "rl + 3D позиция: " + rest;
            RoleUtil.addMember(h, oc, "method", rest.substring(0, rest.indexOf('(')),
                    rest.substring(rest.indexOf('(')), "spawnParticle", "rl-3D");
            ctx.hits.add(h);
        } else {
            int show = Math.min(10, cands.size());
            ctx.review.add("killParticles: кандидатов=" + cands.size() + " " + cands.subList(0, show));
        }
    }
}
