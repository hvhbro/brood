package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * attackEvent: событие атаки мода.
 * Кандидат: класс с Z-полем cancelled + (Z)V-сеттером (не из Entity-иерархии).
 * Постинг: метод, зовущий bus.post И создающий (NEW) кандидата в том же теле.
 * Единственный такой — AUTO.
 */
public final class AttackEventRole implements Role {
    public String name() { return "attackEvent"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit travel = ctx.byRole("travel");
        RoleHit bus = ctx.byRole("eventBus");
        if (travel == null || bus == null) {
            ctx.review.add("attackEvent: нет travel/eventBus — не от чего строить линк");
            return;
        }
        String ep = travel.cls;
        String postM = null;
        String postD = "(Ljava/lang/Object;)V";
        for (RoleHit.Member m : bus.members) {
            if ("post".equals(m.role)) { postM = m.name; postD = m.desc; }
        }
        if (postM == null) return;
        // типы, создаваемые в методах, зовущих bus.post
        java.util.Set<String> postedTypes = new java.util.HashSet<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                boolean callsPost = false;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if (bus.cls.equals(mr.owner)
                            && (postM + postD).equals(mr.name + mr.desc)) {
                        callsPost = true;
                        break;
                    }
                }
                if (!callsPost) continue;
                for (String n : m.news) {
                    if (n.startsWith("rustme/") && ctx.isLive(n)) postedTypes.add(n);
                }
            }
        }
        // attack: posted-тип с cancel (Z)V + entity-поля (attacker+target)
        // ctor-форма — corroboration, постер — должен передавать entity
        List<String> cands = new ArrayList<String>();
        Map<String, List<String>> postersOf = new HashMap<String, List<String>>();
        Map<String, Integer> fieldCnt = new HashMap<String, Integer>();
        for (String cc : postedTypes) {
            if (isEpHierarchy(idx, cc, ep)) continue;
            ClassInfo ci = idx.get(cc);
            if (ci == null) continue;
            int entFields = 0;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (f.isStatic) continue;
                if (!f.desc.startsWith("L") || !f.desc.endsWith(";")) continue;
                String t = f.desc.substring(1, f.desc.length() - 1);
                if (isEpHierarchy(idx, t, ep)) entFields++;
            }
            int entParams = 0;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"<init>".equals(m.name)) continue;
                int rp = m.desc.indexOf(')');
                if (rp < 0) continue;
                List<String> params = new ArrayList<String>(
                        DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
                int ent = 0;
                for (String p : params) {
                    if (isEpHierarchy(idx, p, ep)) ent++;
                }
                if (ent > entParams) entParams = ent;
            }
            String cancelName = null;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"(Z)V".equals(m.desc)) continue;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ("Z".equals(fr.desc) && cc.equals(fr.owner)) cancelName = m.name;
                }
            }
            if (cancelName != null && entFields >= 1) {
                cands.add(cc + "#" + cancelName + "#f" + entFields + "p" + entParams);
                fieldCnt.put(cc, entFields);
                List<String> posters = new ArrayList<String>();
                for (ClassInfo ci2 : idx.all().values()) {
                    if (!ctx.isLive(ci2.name) || !ci2.name.startsWith("rustme/")) continue;
                    for (ClassInfo.MethodInfo m : ci2.methods) {
                        if (!m.news.contains(cc)) continue;
                        for (ClassInfo.MethodRef mr : m.methodRefs) {
                            if (bus.cls.equals(mr.owner)
                                    && (postM + postD).equals(mr.name + mr.desc)) {
                                posters.add(ci2.name + "." + m.name);
                                break;
                            }
                        }
                    }
                }
                postersOf.put(cc, posters);
            }
        }
        // фильтр: требуем >=2 entity-поля (attacker+target) если есть такие,
        // иначе оставляем >=1 но помечаем слабым
        List<String> strong = new ArrayList<String>();
        for (String c : cands) {
            String cc = c.split("#")[0];
            if (fieldCnt.get(cc) != null && fieldCnt.get(cc) >= 2) strong.add(c);
        }
        if (!strong.isEmpty()) cands = strong;
        // следующий фильтр: постер должен передавать entity (target) как параметр
        if (cands.size() > 1) {
            Map<String, Integer> posterEnt = new HashMap<String, Integer>();
            for (String c : cands) {
                String cc = c.split("#")[0];
                List<String> posters = postersOf.get(cc);
                int bestPosterEnt = -1;
                if (posters != null) {
                    for (String p : posters) {
                        String owner = p.substring(0, p.indexOf('.'));
                        ClassInfo pc = idx.get(owner);
                        if (pc == null) continue;
                        String mname = p.substring(p.indexOf('.') + 1);
                        for (ClassInfo.MethodInfo mm : pc.methods) {
                            if (!mname.equals(mm.name)) continue;
                            int rp = mm.desc.indexOf(')');
                            if (rp < 0) continue;
                            List<String> params = new ArrayList<String>(
                                    DumpIndex.classesInDesc(mm.desc.substring(0, rp + 1)));
                            int ent = 0;
                            for (String pp : params) if (isEpHierarchy(idx, pp, ep)) ent++;
                            if (ent > bestPosterEnt) bestPosterEnt = ent;
                        }
                    }
                }
                posterEnt.put(cc, bestPosterEnt);
            }
            int maxEnt = -1;
            for (int v : posterEnt.values()) if (v > maxEnt) maxEnt = v;
            if (maxEnt >= 0) {
                List<String> best = new ArrayList<String>();
                for (String c : cands) {
                    String cc = c.split("#")[0];
                    if (posterEnt.get(cc) != null && posterEnt.get(cc) == maxEnt) best.add(c);
                }
                if (!best.isEmpty() && best.size() < cands.size()) cands = best;
            }
        }
        // если всё ещё >1, требуем entity-геттер в самом событии
        if (cands.size() > 1) {
            List<String> withGetter = new ArrayList<String>();
            for (String c : cands) {
                String cc = c.split("#")[0];
                ClassInfo ci = idx.get(cc);
                boolean hasEntGetter = false;
                if (ci != null) {
                    for (ClassInfo.MethodInfo m : ci.methods) {
                        int rp = m.desc.indexOf(')');
                        if (rp < 0) continue;
                        if (!"()".equals(m.desc.substring(0, rp + 1))) continue;
                        String ret = m.desc.substring(rp + 1);
                        if (!ret.startsWith("L") || !ret.endsWith(";")) continue;
                        String rt = ret.substring(1, ret.length() - 1);
                        if (isEpHierarchy(idx, rt, ep)) { hasEntGetter = true; break; }
                    }
                }
                if (hasEntGetter) withGetter.add(c);
            }
            if (!withGetter.isEmpty() && withGetter.size() < cands.size()) cands = withGetter;
        }
        // фильтр по регистрации: настоящий ивент регистрируется в шине
        // (в коде есть bus.register(AttackEvent.class, ...))
        if (cands.size() > 1) {
            String regM = null, regD = null;
            for (RoleHit.Member mm : bus.members) if ("register".equals(mm.role)) { regM = mm.name; regD = mm.desc; }
            Set<String> registered = new HashSet<String>();
            if (regM != null) {
                for (ClassInfo ci2 : idx.all().values()) {
                    if (!ctx.isLive(ci2.name) || !ci2.name.startsWith("rustme/")) continue;
                    for (ClassInfo.MethodInfo m : ci2.methods) {
                        boolean callsReg = false;
                        for (ClassInfo.MethodRef mr : m.methodRefs) {
                            if (bus.cls.equals(mr.owner) && (regM + regD).equals(mr.name + mr.desc)) { callsReg = true; break; }
                        }
                        if (!callsReg) continue;
                        for (String lc : m.ldcClasses) if (cands.contains(lc + "#") || cands.stream().anyMatch(s -> s.startsWith(lc + "#"))) registered.add(lc);
                        // also check direct contains since cands is "cc#cancel#..."
                        for (String cand : cands) {
                            String cc = cand.split("#")[0];
                            if (m.ldcClasses.contains(cc)) registered.add(cc);
                        }
                    }
                }
            }
            if (!registered.isEmpty()) {
                List<String> regFiltered = new ArrayList<String>();
                for (String c : cands) {
                    String cc = c.split("#")[0];
                    if (registered.contains(cc)) regFiltered.add(c);
                }
                if (!regFiltered.isEmpty() && regFiltered.size() < cands.size()) cands = regFiltered;
            }
        }
        // финальный tie-break: cancel-сеттер должен вызываться извне (обработчик)
        if (cands.size() > 1) {
            List<String> withExternalCancel = new ArrayList<String>();
            for (String c : cands) {
                String cc = c.split("#")[0];
                String cancel = c.split("#")[1];
                boolean external = false;
                for (ClassInfo ci2 : idx.all().values()) {
                    if (!ctx.isLive(ci2.name) || !ci2.name.startsWith("rustme/")) continue;
                    if (ci2.name.equals(cc)) continue;
                    for (ClassInfo.MethodInfo m : ci2.methods) {
                        for (ClassInfo.MethodRef mr : m.methodRefs) {
                            if (cc.equals(mr.owner) && (cancel + "(Z)V").equals(mr.name + mr.desc)) {
                                external = true; break;
                            }
                        }
                        if (external) break;
                    }
                    if (external) break;
                }
                if (external) withExternalCancel.add(c);
            }
            if (!withExternalCancel.isEmpty() && withExternalCancel.size() < cands.size()) cands = withExternalCancel;
        }
        // финальный вывод: если 1-2 близких кандидата — считаем AUTO-набором
        // (дубли обфускации, агент подпишется на оба). 3+ — оставляем REVIEW.
        if (cands.size() >= 1 && cands.size() <= 2) {
            for (String cand : cands) {
                String[] p = cand.split("#");
                RoleHit h = new RoleHit();
                h.role = name();
                h.cls = p[0];
                h.auto = true;
                h.evidence = "posted + cancel + entity (набор из " + cands.size() + ")";
                RoleUtil.addMember(h, p[0], "method", p[1], "(Z)V", "setCancelled", "posted+entities");
                ctx.hits.add(h);
            }
        } else {
            ctx.review.add("attackEvent: postedTypes=" + postedTypes.size()
                    + " cands=" + cands + " posters=" + postersOf);
        }
    }

    private static boolean isEpHierarchy(DumpIndex idx, String x, String ep) {
        Set<String> seen = new HashSet<String>();
        String cur = x;
        while (cur != null && seen.add(cur)) {
            if (cur.equals(ep)) return true;
            ClassInfo ci = idx.get(cur);
            if (ci == null) return false;
            cur = ci.superName;
        }
        return false;
    }
}
