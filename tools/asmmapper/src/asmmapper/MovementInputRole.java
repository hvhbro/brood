package asmmapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * movementInput: холдер ввода в wrapper-классе.
 * Тип холдера — поле wrapper'а, к чьим полям/методам обращается tick-метод
 * (тот что зовёт speed-сеттер): ввод читается каждый тик. Форма-подпорка:
 * есть float-поля. Единственный такой — AUTO (+класс).
 */
public final class MovementInputRole implements Role {
    public String name() { return "movementInput"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit travel = ctx.byRole("travel");
        RoleHit ph = ctx.byRole("playerHierarchy");
        if (travel == null || ph == null) {
            ctx.review.add("movementInput: нет travel/playerHierarchy — не от чего строить линк");
            return;
        }
        String tick = RoleUtil.findSpeedTick(idx, ctx, travel);
        if (tick == null) {
            ctx.review.add("movementInput: tick со speed-сеттером не найден");
            return;
        }
        ClassInfo tickCi = idx.get(tick.substring(0, tick.indexOf('.')));
        String tickM = tick.substring(tick.indexOf('.') + 1, tick.indexOf('('));
        String tickD = tick.substring(tick.indexOf('('));
        ClassInfo wc = idx.get(ph.cls);
        // типы полей wrapper'а
        Set<String> fieldTypes = new HashSet<String>();
        for (ClassInfo.FieldInfo f : wc.fields) {
            for (String t : DumpIndex.classesInDesc(f.desc)) {
                if (t.startsWith("rustme/")) fieldTypes.add(t);
            }
        }
        // какие из них трогает tick
        Set<String> touched = new HashSet<String>();
        for (ClassInfo.MethodInfo m : tickCi.methods) {
            if (!tickM.equals(m.name) || !tickD.equals(m.desc)) continue;
            for (ClassInfo.FieldRef fr : m.fieldRefs) {
                if (fieldTypes.contains(fr.owner)) touched.add(fr.owner);
            }
            for (ClassInfo.MethodRef mr : m.methodRefs) {
                if (fieldTypes.contains(mr.owner)) touched.add(mr.owner);
            }
        }
        List<String> withFloats = new ArrayList<String>();
        for (String t : touched) {
            ClassInfo ci = idx.get(t);
            if (ci == null) continue;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (!f.isStatic && "F".equals(f.desc)) { withFloats.add(t); break; }
            }
        }
        // развилка: настоящий ввод читает сам travel (strafe/forward едут в (FFF)V)
        List<String> travelTouched = new ArrayList<String>();
        RoleHit travelHit = ctx.byRole("travel");
        if (travelHit != null) {
            for (RoleHit.Member mm : travelHit.members) {
                if (!"travel".equals(mm.role)) continue;
                for (ClassInfo.MethodInfo m : idx.get(travelHit.cls).methods) {
                    if (!mm.name.equals(m.name) || !"(FFF)V".equals(m.desc)) continue;
                    for (ClassInfo.FieldRef fr : m.fieldRefs) {
                        if (withFloats.contains(fr.owner) && !travelTouched.contains(fr.owner))
                            travelTouched.add(fr.owner);
                    }
                    for (ClassInfo.MethodRef mr : m.methodRefs) {
                        if (withFloats.contains(mr.owner) && !travelTouched.contains(mr.owner))
                            travelTouched.add(mr.owner);
                    }
                }
            }
        }
        if (travelTouched.size() == 1) withFloats = travelTouched;
        if (withFloats.size() == 1) {
            String holder = withFloats.get(0);
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = holder;
            h.auto = true;
            h.evidence = "поле " + ph.cls + " + трогает " + tick;
            // само поле-холдер во wrapper'е
            for (ClassInfo.FieldInfo f : wc.fields) {
                if (("L" + holder + ";").equals(f.desc)) {
                    RoleUtil.addMember(h, ph.cls, "field", f.name, f.desc,
                            "movementInput", "tick-touched");
                    break;
                }
            }
            ctx.hits.add(h);
        } else {
            ctx.review.add("movementInput:" + ph.cls + ": touched=" + touched
                    + " с float=" + withFloats + " (нужен 1)");
        }
    }
}
