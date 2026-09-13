package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * keyBinding (попытка поведением — строки в дампе шифрованы):
 * tick-код цепочки игрока зовёт чужой ()Z-метод (isKeyDown);
 * класс-владелец с int-полем (код клавиши) и Z-полем (pressed).
 * Единственный такой — AUTO, иначе честный REVIEW.
 */
public final class KeyBindingRole implements Role {
    public String name() { return "keyBinding"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit ph = ctx.byRole("playerHierarchy");
        if (ph == null) {
            ctx.review.add("keyBinding: нет playerHierarchy — не от чего строить линк");
            return;
        }
        // классы цепочки: wrapper + его наследники
        Set<String> chain = new HashSet<String>();
        chain.add(ph.cls);
        for (ClassInfo ci : idx.all().values()) {
            if (RoleUtil.isSubclassOf(idx, ci.name, ph.cls)) chain.add(ci.name);
        }
        // чужие ()Z, зовомые из ()V тиков цепочки; классы Entity-иерархии —
        // не бинды (флаги sneaking/sprinting живут в корне сущностей)
        RoleHit ep = ctx.byRole("travel");
        Map<String, String> extCalls = new HashMap<String, String>();
        for (String c : chain) {
            ClassInfo ci = idx.get(c);
            if (ci == null) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"()V".equals(m.desc)) continue;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if (!"()Z".equals(mr.desc)) continue;
                    if (chain.contains(mr.owner) || !mr.owner.startsWith("rustme/")) continue;
                    if (!ctx.isLive(mr.owner)) continue;
                    if (ep != null && (RoleUtil.isSubclassOf(idx, mr.owner, ep.cls)
                            || RoleUtil.isSubclassOf(idx, ep.cls, mr.owner)
                            || mr.owner.equals(ep.cls))) continue;
                    extCalls.put(mr.owner + "#" + mr.name, mr.owner);
                }
            }
        }
        List<String> cands = new ArrayList<String>();
        Map<String, String> pressedOf = new HashMap<String, String>();
        Map<String, String> readerOf = new HashMap<String, String>();
        for (String owner : new HashSet<String>(extCalls.values())) {
            ClassInfo ci = idx.get(owner);
            if (ci == null) continue;
            boolean hasInt = false;
            boolean hasZ = false;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (!f.isStatic && "I".equals(f.desc)) hasInt = true;
                if (!f.isStatic && "Z".equals(f.desc)) hasZ = true;
            }
            if (!hasInt || !hasZ) continue;
            // Z-поле, читаемое вызванным ()Z-методом
            for (String key : extCalls.keySet()) {
                if (!key.startsWith(owner + "#")) continue;
                String mname = key.substring(key.indexOf('#') + 1);
                String decl = RoleUtil.resolveMethod(idx, owner, mname, "()Z");
                if (decl == null) continue;
                ClassInfo dc = idx.get(decl);
                Set<String> zf = new HashSet<String>();
                for (ClassInfo.MethodInfo mm : dc.methods) {
                    if (!mname.equals(mm.name) || !"()Z".equals(mm.desc)) continue;
                    for (ClassInfo.FieldRef fr : mm.fieldRefs) {
                        if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                                || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                                && "Z".equals(fr.desc)) zf.add(fr.name);
                    }
                }
                if (zf.size() == 1) {
                    cands.add(owner);
                    pressedOf.put(owner, zf.iterator().next());
                    readerOf.put(owner, mname);
                    break;
                }
            }
        }
        if (cands.size() == 1) {
            String cls = cands.get(0);
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = cls;
            h.auto = true;
            h.evidence = "()Z из тиков цепочки + int/Z поля";
            RoleUtil.addMember(h, cls, "field", pressedOf.get(cls), "Z", "pressed", "tick-iskeydown");
            RoleUtil.addMember(h, cls, "method", readerOf.get(cls), "()Z", "isKeyDown", "tick-iskeydown");
            ctx.hits.add(h);
        } else {
            ctx.review.add("keyBinding: кандидатов=" + cands.size() + " " + cands
                    + " (вызовы: " + extCalls.keySet() + ")");
        }
    }
}
