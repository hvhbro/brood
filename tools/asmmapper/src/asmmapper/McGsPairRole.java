package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * minecraft+gameSettings: совместное разрешение взаимной пары.
 * Связи без счётчиков: MC держит World; GS имеет run()V + static ()Lself.
 * Пара: ctor MC берёт GS (MC без настроек не строится) И код GS ссылается
 * на MC (поле или вызов — mcInstance в форке лежит в Object-поле, поэтому
 * дескриптор поля недостаточен, смотрим и вызовы). Единственная пара — AUTO.
 * Выдаёт сразу две роли: minecraft + gameSettings.
 */
public final class McGsPairRole implements Role {
    public String name() { return "minecraft+gameSettings"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit w = ctx.byRole("world");
        if (w == null) {
            ctx.review.add("minecraft: нет world-роли — не от чего строить линк");
            ctx.review.add("gameSettings: нет world-роли — не от чего строить линк");
            return;
        }
        List<String> mcCands = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (("L" + w.cls + ";").equals(f.desc)) { mcCands.add(ci.name); break; }
            }
        }
        List<String> gsCands = new ArrayList<String>();
        Map<String, String> gsMeth = new HashMap<String, String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            boolean hasRun = false;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if ("run".equals(m.name) && "()V".equals(m.desc)) { hasRun = true; break; }
            }
            if (!hasRun && !RoleUtil.implementsTransitive(idx, ci, "java/lang/Runnable", new HashSet<String>())) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (m.isStatic && ("()L" + ci.name + ";").equals(m.desc)) {
                    gsCands.add(ci.name);
                    gsMeth.put(ci.name, m.name);
                    break;
                }
            }
        }
        List<String[]> pairs = new ArrayList<String[]>();
        for (String g : gsCands) {
            for (String m : mcCands) {
                if (RoleUtil.ctorTakes(idx, m, g) && RoleUtil.usesClass(idx, g, m))
                    pairs.add(new String[]{g, m});
            }
        }
        if (pairs.size() > 1) {
            // тайбрейк 1: главный цикл run()V у GS зовёт методы настоящего MC
            List<String[]> driven = new ArrayList<String[]>();
            for (String[] p : pairs) {
                if (RoleUtil.runCalls(idx, p[0], p[1])) driven.add(p);
            }
            if (driven.size() == 1) pairs = driven;
            else if (!driven.isEmpty() && driven.size() < pairs.size()) {
                ctx.review.add("mcGsPair: тайбрейк run()V сузил " + pairs.size()
                        + "->" + driven.size() + " " + fmtPairs(driven));
                pairs = driven;
            }
        }
        if (pairs.size() > 1) {
            // тайбрейк 2: ctor настоящего Minecraft берёт Mojang-auth-сервисы
            // (ванильный отпечаток, либы не обфусцированы)
            List<String[]> withAuth = new ArrayList<String[]>();
            for (String[] p : pairs) {
                if (RoleUtil.ctorTakesLib(idx, p[1],
                        "com/mojang/authlib/yggdrasil/YggdrasilAuthenticationService"))
                    withAuth.add(p);
            }
            if (withAuth.size() == 1) pairs = withAuth;
            else if (!withAuth.isEmpty() && withAuth.size() < pairs.size()) {
                ctx.review.add("mcGsPair: тайбрейк auth сузил " + pairs.size()
                        + "->" + withAuth.size() + " " + fmtPairs(withAuth));
                pairs = withAuth;
            }
        }
        if (pairs.size() == 1) {
            String g = pairs.get(0)[0];
            String m = pairs.get(0)[1];
            RoleHit hm = new RoleHit();
            hm.role = "minecraft";
            hm.cls = m;
            hm.auto = true;
            hm.evidence = "держит World " + w.cls + " + взаимная ссылка с " + g;
            ctx.hits.add(hm);
            RoleHit hg = new RoleHit();
            hg.role = "gameSettings";
            hg.cls = g;
            hg.auto = true;
            hg.evidence = "run()V + singleton " + gsMeth.get(g) + "()Lself"
                    + " + взаимная ссылка с " + m;
            RoleUtil.addMember(hg, g, "method", gsMeth.get(g), "()L" + g + ";",
                    "instance", "gamesettings");
            ctx.hits.add(hg);
        } else {
            ctx.review.add("mcGsPair: mcCands=" + mcCands.size() + " gsCands=" + gsCands
                    + " взаимныхПар=" + pairs.size() + " " + fmtPairs(pairs));
        }
    }

    private static String fmtPairs(List<String[]> pairs) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < pairs.size() && i < 8; i++) {
            if (i > 0) b.append(",");
            b.append(pairs.get(i)[0]).append("<->").append(pairs.get(i)[1]);
        }
        if (pairs.size() > 8) b.append("...");
        return b.append("]").toString();
    }
}
