package asmmapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Достижимость от main (способ Антона против фейков-дублей):
 * BFS от сидов по рёбрам ClassInfo.refs(). Что недостижимо — подозреваемые фейки.
 */
public final class Reach {
    public final Set<String> reachable = new HashSet<String>();
    public final List<String> fakeSuspects = new ArrayList<String>();

    public static Reach build(DumpIndex idx, String mainClass) {
        Reach r = new Reach();
        Map<String, ClassInfo> all = idx.all();
        Set<String> seeds = new HashSet<String>();
        if (all.containsKey(mainClass)) seeds.add(mainClass);
        // Стабильный модовый код — тоже сиды (не обфусцирован, всегда живой).
        for (String n : all.keySet()) {
            if (n.startsWith("ru/rustme/") || n.startsWith("ru/meproject/")) seeds.add(n);
        }
        Deque<String> q = new ArrayDeque<String>(seeds);
        r.reachable.addAll(seeds);
        while (!q.isEmpty()) {
            String cur = q.poll();
            ClassInfo ci = all.get(cur);
            if (ci == null) continue;
            for (String ref : ci.refs()) {
                if (!all.containsKey(ref)) continue; // вне дампа (JDK/либы)
                if (r.reachable.add(ref)) q.add(ref);
            }
        }
        for (String n : all.keySet()) {
            if (n.startsWith("rustme/") && !r.reachable.contains(n)) r.fakeSuspects.add(n);
        }
        return r;
    }
}
