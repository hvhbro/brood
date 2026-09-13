package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * travel: (FFF)V с константой 0.16277F -> speed-поле/геттер/сеттер.
 * Развилка база/override — override-гейтом (без счётчиков); поле скорости —
 * сходимостью геттера из travel и сеттера из tick-методов наследников.
 */
public final class TravelRole implements Role {
    public String name() { return "travel"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> hitCls = new ArrayList<String>();
        Map<String, String> hitMeth = new HashMap<String, String>();
        Map<String, String> hitField = new HashMap<String, String>();
        Map<String, Set<String>> hitDirect = new HashMap<String, Set<String>>();
        Map<String, Map<String, String>> hitGF = new HashMap<String, Map<String, String>>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"(FFF)V".equals(m.desc)) continue;
                boolean hasK = false;
                for (Float f : m.constFloats) {
                    if (Math.abs(f - 0.16277136F) < 1e-6F) { hasK = true; break; }
                }
                if (!hasK) continue;
                // speed: ()F-метод своего класса, ВЫЗЫВАЕМЫЙ из travel, который
                // читает ровно одно F-поле своего класса (кандидат в getSpeed).
                // Прямое чтение F-поля в travel — вторая гипотеза (ветки travel
                // различаются между билдами). Если гипотезы сходятся — AUTO,
                // иначе метод AUTO + поле добирается сеттером из тиков.
                Set<String> direct = new HashSet<String>();
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == Opcodes.GETFIELD || fr.opcode == Opcodes.GETSTATIC)
                            && "F".equals(fr.desc) && ci.name.equals(fr.owner)) direct.add(fr.name);
                }
                Set<String> calledF = new HashSet<String>();
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if (ci.name.equals(mr.owner) && "()F".equals(mr.desc)) calledF.add(mr.name);
                }
                Map<String, String> getterField = new HashMap<String, String>();
                for (String gname : calledF) {
                    ClassInfo.MethodInfo g = null;
                    for (ClassInfo.MethodInfo mm : ci.methods) {
                        if (gname.equals(mm.name) && "()F".equals(mm.desc)) { g = mm; break; }
                    }
                    if (g == null) continue;
                    Set<String> gf = new HashSet<String>();
                    for (ClassInfo.FieldRef fr : g.fieldRefs) {
                        if ((fr.opcode == Opcodes.GETFIELD || fr.opcode == Opcodes.GETSTATIC)
                                && "F".equals(fr.desc) && ci.name.equals(fr.owner)) gf.add(fr.name);
                    }
                    if (gf.size() == 1) getterField.put(gname, gf.iterator().next());
                }
                // сходимость: ровно один вызванный геттер И его поле читается напрямую
                String speed = null;
                String getter = null;
                int ok = 0;
                for (Map.Entry<String, String> e : getterField.entrySet()) {
                    if (direct.contains(e.getValue())) { ok++; speed = e.getValue(); getter = e.getKey(); }
                }
                String hyp = "viaGetter=" + getterField + " direct=" + direct;
                if (ok == 1) {
                    hitCls.add(ci.name);
                    hitMeth.put(ci.name, m.name);
                    hitField.put(ci.name, speed + "|" + getter + "|full");
                    hitDirect.put(ci.name, direct);
                    hitGF.put(ci.name, getterField);
                } else if (!getterField.isEmpty() || !direct.isEmpty()) {
                    // метод найден, поле — добираем сеттером из тиков ниже
                    hitCls.add(ci.name);
                    hitMeth.put(ci.name, m.name);
                    hitField.put(ci.name, "|soft|" + hyp);
                    hitDirect.put(ci.name, direct);
                    hitGF.put(ci.name, getterField);
                }
            }
        }
        String chosen = null;
        if (hitCls.size() == 1) {
            chosen = hitCls.get(0);
        } else if (hitCls.size() > 1) {
            // override-гейт (без счётчиков): кандидат, который транзитивно наследует
            // другого кандидата и переопределяет тот же travel (имя+деск совпадают
            // по требованию JVM) — делегат/дубль, выбывает. База остаётся.
            Set<String> dropped = new HashSet<String>();
            for (String a : hitCls) {
                for (String b : hitCls) {
                    if (a.equals(b) || dropped.contains(a)) continue;
                    if (RoleUtil.isSubclassOf(idx, a, b)
                            && hitMeth.get(a).equals(hitMeth.get(b))) {
                        dropped.add(a);
                        ctx.review.add("travel:" + a + ": переопределяет travel "
                                + b + " — делегат/дубль, скип");
                    }
                }
            }
            List<String> rest = new ArrayList<String>();
            for (String cls : hitCls) if (!dropped.contains(cls)) rest.add(cls);
            if (rest.size() == 1) {
                chosen = rest.get(0);
            } else {
                // corroboration: ctor с World (тип с List-полем, без счётчиков)
                List<String> withWorld = new ArrayList<String>();
                for (String cls : rest) {
                    ClassInfo ci = idx.get(cls);
                    for (ClassInfo.MethodInfo m : ci.methods) {
                        if (!"<init>".equals(m.name)) continue;
                        boolean takesWorld = false;
                        for (String p : DumpIndex.classesInDesc(m.desc)) {
                            if (WorldRole.hasListField(idx, p)) { takesWorld = true; break; }
                        }
                        if (takesWorld) { withWorld.add(cls); break; }
                    }
                }
                if (withWorld.size() == 1) chosen = withWorld.get(0);
                else ctx.review.add("travel: развилка после override-гейта: rest=" + rest
                        + " withWorld=" + withWorld);
            }
        }
        if (chosen != null) {
            String cls = chosen;
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = cls;
            h.auto = true;
            h.evidence = hitMeth.get(cls) + "(FFF)V с 0.16277F";
            String[] fg = hitField.get(cls).split("\\|", -1);
            RoleUtil.addMember(h, cls, "method", hitMeth.get(cls), "(FFF)V", "travel", "travel-const");
            if (fg.length > 1 && "soft".equals(fg[1])) {
                // добираем сеттером из tick-методов наследников (второй признак);
                // метод travel при этом уже AUTO
                String[] sf = findSetterFromTicks(idx, ctx, cls, hitDirect.get(cls), hitGF.get(cls));
                if (sf != null) {
                    RoleUtil.addMember(h, cls, "field", sf[1], "F", "speed.field", "travel-ticksetter");
                    RoleUtil.addMember(h, cls, "method", sf[0], "(F)V", "speed.setter", "travel-ticksetter");
                    h.evidence += " // speedAUTOчерезТик: " + sf[2];
                    String g = RoleUtil.getterForField(hitGF.get(cls), sf[1]);
                    if (g != null)
                        RoleUtil.addMember(h, cls, "method", g, "()F", "speed.getter", "travel-const");
                    else
                        ctx.review.add("travel:" + cls + ": поле+сеттер AUTO, геттер не уникален — на проверку");
                } else {
                    h.evidence += " // поле скорости НЕ сошлось: " + (fg.length > 2 ? fg[2] : "?");
                    ctx.review.add("travel:" + cls + ": метод AUTO, поле+сеттер не сошлись: "
                            + (fg.length > 2 ? fg[2] : "?"));
                }
            } else {
                RoleUtil.addMember(h, cls, "field", fg[0], "F", "speed.field", "travel-const");
                RoleUtil.addMember(h, cls, "method", fg[1], "()F", "speed.getter", "travel-const");
                String setter = RoleUtil.singleFWriter(idx.get(cls), fg[0]);
                if (setter != null)
                    RoleUtil.addMember(h, cls, "method", setter, "(F)V", "speed.setter", "travel-const");
                else
                    ctx.review.add("travel:" + cls + ": (F)V-сеттер поля " + fg[0] + " не уникален — добить глазами");
            }
            ctx.hits.add(h);
        } else {
            ctx.review.add("travel: кандидатов=" + hitCls.size() + " " + hitCls + " (нужен 1)");
        }
    }

    /**
     * Сеттер скорости из tick-методов: в наследниках travel-класса ищем ()V-методы,
     * вызывающие (F)V (owner — travel-класс ИЛИ сам наследник: виртуальный вызов
     * компилируется с типом receiver'а). Цель резолвим вверх по иерархии: декларация
     * обязана быть в travel-классе и писать ровно одно F-поле travel-класса из
     * множества известных (direct/getter travel). Ровно один — AUTO.
     * Возвращает {setter, field, evidence} или null.
     */
    static String[] findSetterFromTicks(DumpIndex idx, Ctx ctx, String travelCls,
                                        Set<String> direct, Map<String, String> getterField) {
        Set<String> candidates = new HashSet<String>();
        Map<String, String> callerEv = new HashMap<String, String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            if (!RoleUtil.isSubclassOf(idx, ci.name, travelCls)) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"()V".equals(m.desc)) continue;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if (!"(F)V".equals(mr.desc)) continue;
                    if (!travelCls.equals(mr.owner) && !ci.name.equals(mr.owner)) continue;
                    String decl = RoleUtil.resolveMethod(idx, mr.owner, mr.name, mr.desc);
                    if (decl == null || !travelCls.equals(decl)) continue;
                    candidates.add(mr.name);
                    if (!callerEv.containsKey(mr.name))
                        callerEv.put(mr.name, ci.name + "." + m.name);
                }
            }
        }
        Set<String> knownFields = new HashSet<String>(direct);
        knownFields.addAll(getterField.values());
        String win = null;
        String winF = null;
        int ok = 0;
        StringBuilder ev = new StringBuilder();
        for (String sname : candidates) {
            final ClassInfo tc = idx.get(travelCls);
            Set<String> puts = new HashSet<String>();
            for (ClassInfo.MethodInfo mm : tc.methods) {
                if (!sname.equals(mm.name) || !"(F)V".equals(mm.desc)) continue;
                for (ClassInfo.FieldRef fr : mm.fieldRefs) {
                    if ((fr.opcode == Opcodes.PUTFIELD || fr.opcode == Opcodes.PUTSTATIC)
                            && "F".equals(fr.desc) && travelCls.equals(fr.owner)) puts.add(fr.name);
                }
            }
            if (puts.size() != 1) continue;
            String fld = puts.iterator().next();
            if (!knownFields.contains(fld)) continue;
            ok++;
            win = sname;
            winF = fld;
            ev.append(sname).append("(F)->").append(fld)
              .append(" via ").append(callerEv.get(sname)).append("; ");
        }
        if (ok == 1) return new String[]{win, winF, ev.toString()};
        return null;
    }
}
