package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * movementSpeedAttr: атрибут скорости без строк (в дампе шифрованы).
 * Признак A (константы): RangedAttribute(null, name, 0.25, 0.0, 1024.0) —
 * в <clinit> холдера лежат 0.25D и 1024.0D, рядом PUTSTATIC базового поля.
 * Признак B (поток): tick-метод, зовущий speed-сеттер (travel-роль),
 * ссылается GETSTATIC на это базовое поле (база едет в getAttributeInstance).
 * A+B на одном поле — AUTO. Дальше по типу базы: getAttributeInstance в
 * EntityPlayer -> класс инстанса -> getValue (считающий, с модификаторами)
 * vs getBaseValue (тривиальный геттер) + setBase (D)V.
 */
public final class MovementSpeedRole implements Role {
    public String name() { return "movementSpeedAttr"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit travel = ctx.byRole("travel");
        if (travel == null) {
            ctx.review.add("movementSpeedAttr: нет travel-роли — не от чего строить линк");
            return;
        }
        String setter = null;
        for (RoleHit.Member m : travel.members) {
            if ("speed.setter".equals(m.role)) setter = m.name;
        }
        if (setter == null) {
            ctx.review.add("movementSpeedAttr: в travel нет speed.setter — не от чего строить линк");
            return;
        }
        String playerCls = travel.cls;
        // tick-методы: зовут playerCls.setter(F)V (owner проверяем — одноимённые
        // (F)V в других классах не в счёт; деск вызывающего любой, кроме init)
        List<String> tickMethods = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if (!(setter + "(F)V").equals(mr.name + mr.desc)) continue;
                    // owner — compile-time тип receiver'а (часто наследник);
                    // декларация обязана быть в EntityPlayer
                    String decl = RoleUtil.resolveMethod(idx, mr.owner, mr.name, mr.desc);
                    if (playerCls.equals(decl)) {
                        tickMethods.add(ci.name + "." + m.name + m.desc);
                        break;
                    }
                }
            }
        }
        // A: холдер атрибутов: в <clinit> идут тройки (base, min, max) double,
        // movementSpeed = (0.7, 0.0, 1024.0) — ванильный дефолт, уникален.
        // k-я тройка -> k-й PUTSTATIC атрибутного типа (мажоритарный тип
        // putstatic-полей <clinit>, логгеры и прочее отсекаются типом).
        Map<String, String> baseEv = new HashMap<String, String>();
        Set<String> bases = new HashSet<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"<clinit>".equals(m.name)) continue;
                // мажоритарный тип putstatic = тип атрибутов
                Map<String, Integer> typeCount = new HashMap<String, Integer>();
                List<String> putsInOrder = new ArrayList<String>();
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if (fr.opcode != Opcodes.PUTSTATIC || !ci.name.equals(fr.owner)) continue;
                    if (!fr.desc.startsWith("L")) continue;
                    Integer c = typeCount.get(fr.desc);
                    typeCount.put(fr.desc, c == null ? 1 : c + 1);
                }
                String attrType = null;
                int attrMax = 0;
                for (Map.Entry<String, Integer> e : typeCount.entrySet()) {
                    if (e.getValue() > attrMax) { attrMax = e.getValue(); attrType = e.getKey(); }
                }
                if (attrType == null || attrMax < 3) continue;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if (fr.opcode == Opcodes.PUTSTATIC && ci.name.equals(fr.owner)
                            && attrType.equals(fr.desc)) putsInOrder.add(fr.name);
                }
                // тройки double по порядку; ищем (0.7, 0.0, 1024.0)
                List<Double> ds = m.constDoubles;
                int hitTriple = -1;
                for (int i = 0; i + 2 < ds.size(); i += 3) {
                    if (Math.abs(ds.get(i) - 0.7D) < 1e-6
                            && Math.abs(ds.get(i + 1) - 0.0D) < 1e-9
                            && Math.abs(ds.get(i + 2) - 1024.0D) < 1e-9) {
                        if (hitTriple >= 0) { hitTriple = -2; break; } // дубль — отказ
                        hitTriple = i / 3;
                    }
                }
                if (hitTriple >= 0 && hitTriple < putsInOrder.size()) {
                    String key = ci.name + "#" + putsInOrder.get(hitTriple);
                    bases.add(key);
                    baseEv.put(key, "clinit triple(0.7,0.0,1024.0)#" + hitTriple);
                }
            }
        }
        // B: база из A, на которую tick ссылается GETSTATIC
        Set<String> corroborated = new HashSet<String>();
        for (String tick : tickMethods) {
            int dot = tick.indexOf('.');
            int paren = tick.indexOf('(', dot);
            ClassInfo ci = idx.get(tick.substring(0, dot));
            String mname = tick.substring(dot + 1, paren);
            String mdesc = tick.substring(paren);
            if (ci == null) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!mname.equals(m.name) || !mdesc.equals(m.desc)) continue;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if (fr.opcode != Opcodes.GETSTATIC) continue;
                    String key = fr.owner + "#" + fr.name;
                    if (bases.contains(key)) corroborated.add(key);
                }
            }
        }
        if (corroborated.size() != 1) {
            ctx.review.add("movementSpeedAttr: A=" + bases + " B∩tick=" + corroborated
                    + " tick=" + tickMethods + " (нужна 1 corroborated)");
            return;
        }
        String key = corroborated.iterator().next();
        String holder = key.substring(0, key.indexOf('#'));
        String baseField = key.substring(key.indexOf('#') + 1);
        ClassInfo hc = idx.get(holder);
        String baseDesc = null;
        for (ClassInfo.FieldInfo f : hc.fields) {
            if (baseField.equals(f.name)) baseDesc = f.desc;
        }
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = holder;
        h.auto = true;
        h.evidence = baseEv.get(key) + " + GETSTATIC в " + tickMethods;
        RoleUtil.addMember(h, holder, "field", baseField, baseDesc == null ? "" : baseDesc,
                "base", "const-triple+tickflow");
        // getAttributeInstance в EntityPlayer: нестатический, 1 arg типа базы,
        // возврат — объект (класс инстанса)
        String getAttr = null;
        String getAttrDesc = null;
        String instCls = null;
        ClassInfo pc = idx.get(playerCls);
        if (pc != null && baseDesc != null && baseDesc.startsWith("L")) {
            String baseCls = baseDesc.substring(1, baseDesc.length() - 1);
            for (ClassInfo.MethodInfo m : pc.methods) {
                if (m.isStatic) continue;
                int rp = m.desc.indexOf(')');
                if (rp < 0 || rp + 1 >= m.desc.length() || m.desc.charAt(rp + 1) != 'L') continue;
                List<String> params = new ArrayList<String>(DumpIndex.classesInDesc(
                        m.desc.substring(0, rp + 1)));
                if (params.size() == 1 && params.get(0).equals(baseCls)) {
                    getAttr = m.name;
                    getAttrDesc = m.desc;
                    instCls = m.desc.substring(rp + 2, m.desc.length() - 1);
                    break;
                }
            }
        }
        if (getAttr != null) {
            RoleUtil.addMember(h, playerCls, "method", getAttr, getAttrDesc,
                    "getAttributeInstance", "base-arg");
        } else {
            ctx.review.add("movementSpeedAttr:" + playerCls + ": getAttributeInstance(base) не найден");
        }
        // инстанс: getValue = ()D, который зовут tick-методы скоросеттера;
        // setBase = единственный (D)V класса; getBaseValue = второй тривиальный ()D
        if (instCls != null) {
            ClassInfo ic = idx.get(instCls);
            if (ic != null) {
                Set<String> tickD = new HashSet<String>();
                for (String tick : tickMethods) {
                    int dot = tick.indexOf('.');
                    int paren = tick.indexOf('(', dot);
                    ClassInfo ci = idx.get(tick.substring(0, dot));
                    String mname = tick.substring(dot + 1, paren);
                    String mdesc = tick.substring(paren);
                    if (ci == null) continue;
                    for (ClassInfo.MethodInfo m : ci.methods) {
                        if (!mname.equals(m.name) || !mdesc.equals(m.desc)) continue;
                        for (ClassInfo.MethodRef mr : m.methodRefs) {
                            if ("()D".equals(mr.desc) && instCls.equals(mr.owner))
                                tickD.add(mr.name);
                        }
                    }
                }
                if (tickD.size() == 1) {
                    RoleUtil.addMember(h, instCls, "method", tickD.iterator().next(), "()D",
                            "getValue", "tick-called-D");
                } else {
                    ctx.review.add("movementSpeedAttr:" + instCls + ": tick-()D=" + tickD);
                }
                List<String> dvoids = new ArrayList<String>();
                for (ClassInfo.MethodInfo m : ic.methods) {
                    if ("(D)V".equals(m.desc)) dvoids.add(m.name);
                }
                if (dvoids.size() == 1) {
                    RoleUtil.addMember(h, instCls, "method", dvoids.get(0), "(D)V",
                            "setBaseValue", "sole-D-void");
                } else {
                    ctx.review.add("movementSpeedAttr:" + instCls + ": (D)V=" + dvoids);
                }
                List<String> trivial = new ArrayList<String>();
                for (ClassInfo.MethodInfo m : ic.methods) {
                    if (!"()D".equals(m.desc) || tickD.contains(m.name)) continue;
                    if (m.methodRefs.isEmpty()) trivial.add(m.name);
                }
                if (trivial.size() == 1) {
                    RoleUtil.addMember(h, instCls, "method", trivial.get(0), "()D",
                            "getBaseValue", "other-trivial-D");
                } else if (!trivial.isEmpty()) {
                    ctx.review.add("movementSpeedAttr:" + instCls + ": тривиальные ()D=" + trivial);
                }
            }
        }
        ctx.hits.add(h);
    }
}
