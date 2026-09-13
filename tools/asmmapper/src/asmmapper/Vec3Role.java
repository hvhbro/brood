package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * vec3: вектор из 3 double — <init>(DDD)V + 3 D-поля.
 * Corroboration: метод (Lself;)D (distanceTo) или (DDD)Lself (addVector).
 */
public final class Vec3Role implements Role {
    public String name() { return "vec3"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> cands = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            boolean hasCtor = false;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if ("<init>".equals(m.name) && "(DDD)V".equals(m.desc)) { hasCtor = true; break; }
            }
            if (!hasCtor) continue;
            int dcnt = 0;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (!f.isStatic && "D".equals(f.desc)) dcnt++;
            }
            if (dcnt < 3) continue;
            boolean corroborated = false;
            for (ClassInfo.MethodInfo m : ci.methods) {
                int rp = m.desc.indexOf(')');
                if (rp < 0) continue;
                String params = m.desc.substring(0, rp + 1);
                String ret = m.desc.substring(rp + 1);
                if (("D".equals(ret) && params.contains("L" + ci.name + ";"))
                        || (("L" + ci.name + ";").equals(ret) && params.contains("DDD"))) {
                    corroborated = true;
                    break;
                }
            }
            if (corroborated) cands.add(ci.name);
        }
        if (cands.size() == 1) {
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = cands.get(0);
            h.auto = true;
            h.evidence = "<init>(DDD)V + 3D + distance/addVector";
            ctx.hits.add(h);
        } else {
            ctx.review.add("vec3: кандидатов=" + cands.size() + " " + cands);
        }
    }
}
