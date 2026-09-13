package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/** entityPos: setpos (DDD)V с 3+ (D)V-сеттерами -> D-поля X,Y,Z + ()D-геттеры. */
public final class EntityPosRole implements Role {
    public String name() { return "entityPos"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> candCls = new ArrayList<String>();
        Map<String, String> candEv = new HashMap<String, String>();
        Map<String, List<String[]>> candTriple = new HashMap<String, List<String[]>>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            // сеттеры (D)V с ровно одним D-putfield своего класса
            Map<String, String> setterToField = new HashMap<String, String>();
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"(D)V".equals(m.desc)) continue;
                String put = null;
                int puts = 0;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == Opcodes.PUTFIELD || fr.opcode == Opcodes.PUTSTATIC)
                            && "D".equals(fr.desc) && ci.name.equals(fr.owner)) {
                        puts++;
                        put = fr.name;
                    }
                }
                if (puts == 1) setterToField.put(m.name, put);
            }
            if (setterToField.size() < 3) continue;
            Set<String> setterNames = new HashSet<String>(setterToField.keySet());
            String tripleEv = null;
            List<String[]> triple = null;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (m.desc.indexOf("(DDD") < 0) continue;
                List<String> seq = new ArrayList<String>();
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if (ci.name.equals(mr.owner) && "(D)V".equals(mr.desc)
                            && setterNames.contains(mr.name)) seq.add(mr.name);
                }
                if (seq.size() >= 3) {
                    List<String> t = seq.subList(0, 3);
                    if (new HashSet<String>(t).size() != 3) continue;
                    if (triple == null) {
                        triple = new ArrayList<String[]>();
                        for (String s : t) triple.add(new String[]{setterToField.get(s), s});
                        tripleEv = m.name + m.desc + " -> " + t;
                    } else {
                        // консенсус: все кандидаты — одна тройка в том же порядке
                        boolean same = triple.size() == 3;
                        for (int i = 0; same && i < 3; i++) same = triple.get(i)[1].equals(t.get(i));
                        if (!same) { triple = null; tripleEv = null; break; }
                    }
                }
            }
            if (triple != null) {
                candCls.add(ci.name);
                candEv.put(ci.name, tripleEv);
                candTriple.put(ci.name, triple);
            }
        }
        if (candCls.size() == 1) {
            String cls = candCls.get(0);
            ClassInfo ci = idx.get(cls);
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = cls;
            h.auto = true;
            h.evidence = candEv.get(cls);
            String[] roles = {"posX", "posY", "posZ"};
            List<String[]> triple = candTriple.get(cls);
            for (int i = 0; i < 3; i++) {
                String field = triple.get(i)[0];
                String setter = triple.get(i)[1];
                String getter = RoleUtil.singleDReader(ci, field);
                RoleUtil.addMember(h, cls, "field", field, "D", roles[i] + ".field", "setpos-order");
                RoleUtil.addMember(h, cls, "method", setter, "(D)V", roles[i] + ".setter", "setpos-order");
                if (getter != null) RoleUtil.addMember(h, cls, "method", getter, "()D", roles[i] + ".getter", "setpos-order");
                else ctx.review.add("entityPos:" + cls + ": нет единственного ()D-ридера поля " + field);
            }
            ctx.hits.add(h);
        } else {
            ctx.review.add("entityPos: кандидатов=" + candCls.size() + " " + candCls + " (нужен 1)");
        }
    }
}
