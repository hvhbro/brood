package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * tablist: NetworkPlayerInfo — держатель List&lt;X&gt; (таб-лист).
 * latency-метод — отдельным REVIEW: несколько ()I-ридеров статикой
 * неразличимы, пинг не блокирует ядро (вотермарк покажет 0).
 */
public final class TablistRole implements Role {
    public String name() { return "tablist"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> cands = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (f.generic.length() == 0) continue;
                for (String x : genericArgs(f.generic)) {
                    if (!x.startsWith("rustme/")) continue;
                    ClassInfo xc = idx.get(x);
                    if (xc == null) continue;
                    for (ClassInfo.MethodInfo m : xc.methods) {
                        if (!"()I".equals(m.desc)) continue;
                        for (ClassInfo.FieldRef fr : m.fieldRefs) {
                            if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                                    || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                                    && "I".equals(fr.desc) && x.equals(fr.owner)) {
                                String e = x + " via " + ci.name + "." + f.name;
                                if (!cands.contains(e)) cands.add(e);
                            }
                        }
                    }
                }
            }
        }
        // пересечение: X с GameProfile-полем (ванильный info держит профиль)
        List<String> final_ = new ArrayList<String>();
        for (String e : cands) {
            String x = e.split(" via ")[0];
            ClassInfo xc = idx.get(x);
            if (xc == null) continue;
            for (ClassInfo.FieldInfo f : xc.fields) {
                if ("Lcom/mojang/authlib/GameProfile;".equals(f.desc)
                        && !final_.contains(e)) final_.add(e);
            }
        }
        if (final_.size() == 1) cands = final_;
        if (cands.size() == 1) {
            String info = cands.get(0).split(" via ")[0];
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = info;
            h.auto = true;
            h.evidence = "List-holder: " + cands.get(0);
            ctx.hits.add(h);
            ClassInfo ci = idx.get(info);
            List<String> readers = new ArrayList<String>();
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"()I".equals(m.desc)) continue;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                            || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                            && "I".equals(fr.desc) && info.equals(fr.owner)) {
                        String re = m.name + "#" + fr.name;
                        if (!readers.contains(re)) readers.add(re);
                    }
                }
            }
            ctx.review.add("tablist:" + info + ": latency-кандидаты=" + readers
                    + " (пинг не блокирует ядро)");
        } else {
            int show = Math.min(15, cands.size());
            ctx.review.add("tablist: list-holders=" + cands.size() + " " + cands.subList(0, show));
        }
    }

    /** Все типовые аргументы generic (L...; внутри). */
    static List<String> genericArgs(String generic) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        while ((i = generic.indexOf('L', i)) >= 0) {
            int j = generic.indexOf(';', i);
            if (j < 0) break;
            String t = generic.substring(i + 1, j);
            if (t.length() > 0 && !out.contains(t)) out.add(t);
            i = j + 1;
        }
        return out;
    }
}
