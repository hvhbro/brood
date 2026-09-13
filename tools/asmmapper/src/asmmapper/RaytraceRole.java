package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * raytrace: метод World (Lvec;Lvec;...)Lhit — трассировка взгляда (ESP/Aim).
 * Хит-класс — тип возврата (содержит точку/сторону/блок).
 */
public final class RaytraceRole implements Role {
    public String name() { return "raytrace"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit w = ctx.byRole("world");
        RoleHit v = ctx.byRole("vec3");
        if (w == null || v == null) {
            ctx.review.add("raytrace: нет world/vec3 — не от чего строить линк");
            return;
        }
        ClassInfo wc = idx.get(w.cls);
        if (wc == null) return;
        List<String> cands = new ArrayList<String>();
        for (ClassInfo.MethodInfo m : wc.methods) {
            int rp = m.desc.indexOf(')');
            if (rp < 0) continue;
            String params = m.desc.substring(0, rp + 1);
            String ret = m.desc.substring(rp + 1);
            if (!ret.startsWith("L") || !ret.endsWith(";")) continue;
            int vecs = 0;
            int at = 0;
            while ((at = params.indexOf("L" + v.cls + ";", at)) >= 0) {
                vecs++;
                at++;
            }
            if (vecs >= 2) cands.add(m.name + m.desc);
        }
        if (cands.size() == 1) {
            emit(ctx, idx, w, cands.get(0));
        } else if (cands.size() > 1) {
            // тайбрейк: ванильная полная сигнатура (vec,vec,ZZZ)
            List<String> full = new ArrayList<String>();
            for (String mm : cands) {
                if (mm.contains("(L" + v.cls + ";L" + v.cls + ";ZZZ)")) full.add(mm);
            }
            if (full.size() == 1) emit(ctx, idx, w, full.get(0));
            else ctx.review.add("raytrace:" + w.cls + ": кандидатов=" + cands.size() + " " + cands);
        } else {
            ctx.review.add("raytrace:" + w.cls + ": кандидатов=0");
        }
    }

    private void emit(Ctx ctx, DumpIndex idx, RoleHit w, String mm) {
        String ret = mm.substring(mm.indexOf(')') + 2, mm.length() - 1);
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = w.cls;
        h.auto = true;
        h.evidence = mm + " с 2xVec3";
        RoleUtil.addMember(h, w.cls, "method", mm.substring(0, mm.indexOf('(')),
                mm.substring(mm.indexOf('(')), "rayTraceBlocks", "two-vec-params");
        RoleUtil.addMember(h, ret, "class", shortName(ret), "L" + ret + ";",
                "rayTraceHit", "raytrace-ret");
        ctx.hits.add(h);
    }

    private static String shortName(String internal) {
        int i = internal.lastIndexOf('/');
        return i >= 0 ? internal.substring(i + 1) : internal;
    }
}
