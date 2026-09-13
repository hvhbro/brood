package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * worldTime: World.getWorldInfo (no-arg -> объект) + WorldInfo.setWorldTime(J)
 * (CustomTime). Ванильные сигнатуры.
 */
public final class WorldTimeRole implements Role {
    public String name() { return "worldTime"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit w = ctx.byRole("world");
        if (w == null) {
            ctx.review.add("worldTime: нет world — не от чего строить линк");
            return;
        }
        ClassInfo wc = idx.get(w.cls);
        if (wc == null) return;
        // кандидаты: no-arg -> объект с (J)V внутри
        List<String> raw = new ArrayList<String>();
        for (ClassInfo.MethodInfo m : wc.methods) {
            int rp = m.desc.indexOf(')');
            if (rp < 0) continue;
            if (!"()".equals(m.desc.substring(0, rp + 1))) continue;
            String ret = m.desc.substring(rp + 1);
            if (!ret.startsWith("L") || !ret.endsWith(";")) continue;
            String t = ret.substring(1, ret.length() - 1);
            if (!t.startsWith("rustme/") || !ctx.isLive(t)) continue;
            ClassInfo tc = idx.get(t);
            if (tc == null) continue;
            for (ClassInfo.MethodInfo mm : tc.methods) {
                if ("(J)V".equals(mm.desc)) raw.add(t + "#" + m.name + "#" + mm.name);
            }
        }
        // из них — пара (J)V, зовущаяся вместе из одного хендлера
        // (total+world); второй вызов = setWorldTime (порядок ванильного хендлера)
        Map<String, Integer> pairVotes = new HashMap<String, Integer>();
        Map<String, String> pairGetter = new HashMap<String, String>();
        for (String r : raw) {
            String[] p = r.split("#");
            for (ClassInfo ci : idx.all().values()) {
                if (!ctx.isLive(ci.name)) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    List<String> calls = new ArrayList<String>();
                    for (ClassInfo.MethodRef mr : m.methodRefs) {
                        if (p[0].equals(mr.owner) && mr.desc.endsWith("(J)V")) {
                            String k = mr.name;
                            if (!calls.contains(k)) calls.add(k);
                        }
                    }
                    if (calls.size() == 2) {
                        String key = p[0] + "|" + calls.get(0) + "|" + calls.get(1);
                        Integer c = pairVotes.get(key);
                        pairVotes.put(key, c == null ? 1 : c + 1);
                        pairGetter.put(key, p[1]);
                    }
                }
            }
        }
        List<String> cands = new ArrayList<String>();
        String bestPair = null;
        int bestN = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> e : pairVotes.entrySet()) {
            if (e.getValue() > bestN) { bestN = e.getValue(); bestPair = e.getKey(); tie = false; }
            else if (e.getValue() == bestN) tie = true;
        }
        if (bestPair != null && !tie) cands.add(bestPair + "#" + pairGetter.get(bestPair));
        if (cands.size() == 1) {
            // entry: "owner|call1|call2#getter" — второй вызов пары = setWorldTime
            String e = cands.get(0);
            String pair = e.substring(0, e.indexOf('#'));
            String getter = e.substring(e.indexOf('#') + 1);
            String[] pp = pair.split("\\|");
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = pp[0];
            h.auto = true;
            h.evidence = "world." + getter + "() + пара " + pp[1] + "," + pp[2]
                    + " (второй=setWorldTime)";
            RoleUtil.addMember(h, w.cls, "method", getter, "()L" + pp[0] + ";",
                    "getWorldInfo", "noarg-object");
            RoleUtil.addMember(h, pp[0], "method", pp[2], "(J)V",
                    "setWorldTime", "handler-order-2nd");
            RoleUtil.addMember(h, pp[0], "method", pp[1], "(J)V",
                    "setTotalTime", "handler-order-1st");
            ctx.hits.add(h);
        } else {
            ctx.review.add("worldTime:" + w.cls + ": кандидатов=" + cands.size() + " " + cands);
        }
    }

    private static java.util.List<String> splitParams(String desc) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        int rp = desc.indexOf(')');
        if (rp < 0) return out;
        int i = 1;
        while (i < rp) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int j = desc.indexOf(';', i);
                if (j < 0 || j > rp) break;
                out.add(desc.substring(i, j + 1));
                i = j + 1;
            } else if (c == '[') {
                int j = i;
                while (j < rp && desc.charAt(j) == '[') j++;
                if (j < rp && desc.charAt(j) == 'L') {
                    int k = desc.indexOf(';', j);
                    if (k < 0 || k > rp) break;
                    j = k + 1;
                } else {
                    j++;
                }
                out.add(desc.substring(i, j));
                i = j;
            } else {
                out.add(String.valueOf(c));
                i++;
            }
        }
        return out;
    }
}
