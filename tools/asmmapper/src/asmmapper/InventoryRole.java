package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * inventory: держатель слотов игрока.
 * Держатель — поле EP-цепочки, чей тип содержит stack-контейнер
 * ([Lstack; или generic со stack-элементом).
 * Из типов — тот, чей (I)Lstack зовётся из метода с константой 9
 * (хотбар рендерит 9 слотов); там же зовётся ()I currentItem.
 * Поле слотов — контейнер, читаемый в getStackInSlot.
 */
public final class InventoryRole implements Role {
    public String name() { return "inventory"; }

    private static boolean isStackContainer(ClassInfo.FieldInfo f, String stack) {
        if (("[L" + stack + ";").equals(f.desc)) return true;
        // любой generic с элементом-стаком: List<X>, Map<K,X>, NonNullList<X>...
        return f.generic.length() > 0 && f.generic.contains("L" + stack + ";");
    }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit ph = ctx.byRole("playerHierarchy");
        RoleHit ri = ctx.byRole("renderItem");
        if (ph == null || ri == null) {
            ctx.review.add("inventory: нет playerHierarchy/renderItem — не от чего строить линк");
            return;
        }
        String stack = null;
        for (RoleHit.Member m : ri.members) {
            if ("itemStack".equals(m.role)) stack = m.cls;
        }
        if (stack == null) return;
        // цепочка игрока: travel-класс + наследники + предки до корня
        List<String> chain = new ArrayList<String>();
        RoleHit trav = ctx.byRole("travel");
        if (trav != null) {
            chain.add(trav.cls);
            for (ClassInfo ci : idx.all().values()) {
                if (RoleUtil.isSubclassOf(idx, ci.name, trav.cls)) chain.add(ci.name);
            }
            Set<String> seen = new HashSet<String>();
            String cur = trav.cls;
            while (cur != null && seen.add(cur)) {
                if (!chain.contains(cur)) chain.add(cur);
                ClassInfo ci = idx.get(cur);
                cur = ci == null ? null : ci.superName;
                if (cur != null && cur.startsWith("java/")) break;
            }
        }
        // держатели: поле цепочки, чей тип содержит stack-контейнер
        List<String> holders = new ArrayList<String>();
        for (String owner : chain) {
            ClassInfo ci = idx.get(owner);
            if (ci == null) continue;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (!f.desc.startsWith("L") || !f.desc.endsWith(";")) continue;
                String t = f.desc.substring(1, f.desc.length() - 1);
                if (!t.startsWith("rustme/")) continue;
                ClassInfo tc = idx.get(t);
                if (tc == null) continue;
                for (ClassInfo.FieldInfo ff : tc.fields) {
                    if (isStackContainer(ff, stack)) {
                        holders.add(owner + "#" + f.name + "#" + t);
                        break;
                    }
                }
            }
        }
        // из типов-держателей — тот, чей (I)Lstack зовётся из метода с 9;
        // там же зовётся ()I с чтением int-поля типа (currentItem)
        Set<String> types = new HashSet<String>();
        for (String hld : holders) types.add(hld.split("#")[2]);
        List<String> full = new ArrayList<String>();
        Map<String, String> getSlotOf = new HashMap<String, String>();
        Map<String, String> curOf = new HashMap<String, String>();
        for (String t : types) {
            List<String> slotCalls = new ArrayList<String>();
            List<String> curCalls = new ArrayList<String>();
            for (ClassInfo ci : idx.all().values()) {
                if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    boolean has9 = false;
                    for (Integer c : m.constInts) {
                        if (c == 9) { has9 = true; break; }
                    }
                    if (!has9) continue;
                    for (ClassInfo.MethodRef mr : m.methodRefs) {
                        int rp = mr.desc.indexOf(')');
                        if (rp < 0) continue;
                        if ("(I)".equals(mr.desc.substring(0, rp + 1))
                                && ("L" + stack + ";").equals(mr.desc.substring(rp + 1))) {
                            String decl = RoleUtil.resolveMethod(idx, mr.owner, mr.name, mr.desc);
                            if (decl == null
                                    || !(decl.equals(t) || RoleUtil.isSubclassOf(idx, decl, t)))
                                continue;
                            String k = decl + "#" + mr.name;
                            if (!slotCalls.contains(k)) slotCalls.add(k);
                        }
                        if ("()I".equals(mr.desc)) {
                            String decl = RoleUtil.resolveMethod(idx, mr.owner, mr.name, mr.desc);
                            if (decl == null || !t.equals(decl)) continue;
                            ClassInfo dc = idx.get(decl);
                            boolean readsInt = false;
                            for (ClassInfo.MethodInfo mm : dc.methods) {
                                if (!mr.name.equals(mm.name) || !"()I".equals(mm.desc)) continue;
                                for (ClassInfo.FieldRef fr : mm.fieldRefs) {
                                    if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                                            || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                                            && "I".equals(fr.desc) && t.equals(fr.owner))
                                        readsInt = true;
                                }
                            }
                            if (readsInt) {
                                String k = decl + "#" + mr.name;
                                if (!curCalls.contains(k)) curCalls.add(k);
                            }
                        }
                    }
                }
            }
            if (slotCalls.size() != 1 || curCalls.size() != 1) continue;
            full.add(t);
            getSlotOf.put(t, slotCalls.get(0));
            curOf.put(t, curCalls.get(0));
        }
        if (full.size() != 1) {
            ctx.review.add("inventory: full-profile=" + full.size() + " " + full
                    + " holders=" + holders.size());
            return;
        }
        String inv = full.get(0);
        List<String> invHolders = new ArrayList<String>();
        for (String hld : holders) {
            if (hld.endsWith("#" + inv)) invHolders.add(hld);
        }
        if (invHolders.size() != 1) {
            ctx.review.add("inventory:" + inv + ": holders=" + invHolders);
            return;
        }
        // поле слотов: контейнер типа, читаемый в getStackInSlot
        String[] gp = getSlotOf.get(inv).split("#");
        String slotField = null;
        String slotDesc = "";
        ClassInfo gdc = idx.get(gp[0]);
        if (gdc != null) {
            for (ClassInfo.MethodInfo m : gdc.methods) {
                if (!gp[1].equals(m.name)) continue;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if (fr.opcode != org.objectweb.asm.Opcodes.GETFIELD
                            && fr.opcode != org.objectweb.asm.Opcodes.GETSTATIC) continue;
                    ClassInfo oc = idx.get(fr.owner);
                    if (oc == null) continue;
                    for (ClassInfo.FieldInfo f : oc.fields) {
                        if (f.name.equals(fr.name) && isStackContainer(f, stack)) {
                            slotField = fr.owner + "#" + fr.name;
                            slotDesc = f.desc;
                        }
                    }
                }
            }
        }
        // поле currentItem: int, читаемый в getCurrentItem
        String[] qp = curOf.get(inv).split("#");
        String curField = null;
        ClassInfo cdc = idx.get(qp[0]);
        if (cdc != null) {
            for (ClassInfo.MethodInfo m : cdc.methods) {
                if (!qp[1].equals(m.name) || !"()I".equals(m.desc)) continue;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                            || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                            && "I".equals(fr.desc) && inv.equals(fr.owner)) {
                        curField = fr.name;
                        break;
                    }
                }
            }
        }
        String[] hp = invHolders.get(0).split("#");
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = inv;
        h.auto = true;
        h.evidence = "hotbar-9 + держатель " + invHolders.get(0);
        RoleUtil.addMember(h, hp[0], "field", hp[1], "L" + inv + ";",
                "inventory", "chain-holder");
        RoleUtil.addMember(h, gp[0], "method", gp[1], "(I)L" + stack + ";",
                "getStackInSlot", "hotbar-9");
        if (slotField != null) {
            String[] sf = slotField.split("#");
            RoleUtil.addMember(h, sf[0], "field", sf[1], slotDesc,
                    "slots", "slot-reader-field");
        }
        RoleUtil.addMember(h, qp[0], "method", qp[1], "()I",
                "getCurrentItem", "hotbar-9");
        if (curField != null) {
            RoleUtil.addMember(h, inv, "field", curField, "I",
                    "currentItem", "hotbar-9");
        } else {
            h.auto = false;
            ctx.review.add("inventory:" + inv + ": currentItem-поле не различимо");
        }
        ctx.hits.add(h);
    }
}
