package asmmapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * cameraFacade: система free-look камеры (точка NoRecoil).
 * Якорь — static хендлер с параметром FreeLook*PacketData (пакеты
 * ru.rustme.network НЕ обфусцированы): он пишет Z-флаг enable.
 * reset()V: читает F-состояние, пушит в (F)V-сеттеры localPlayer
 * (ротация!) и ОБНУЛЯЕТ собственную static F-пару 0.0F — это и есть
 * аккумуляторы (кик), их и зероти NoRecoil.
 * yaw/pitch внутри пары — рантайм-проба оси в игре.
 * Бонус: static геттер localPlayer, зовомый из reset.
 */
public final class FacadeRole implements Role {
    public String name() { return "cameraFacade"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> facades = new ArrayList<String>();
        java.util.Map<String, String> handlerOf = new java.util.HashMap<String, String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!m.isStatic) continue;
                for (String p : DumpIndex.classesInDesc(m.desc)) {
                    if (p.contains("FreeLook")) {
                        facades.add(ci.name);
                        handlerOf.put(ci.name, m.name + m.desc);
                    }
                }
            }
        }
        if (facades.size() != 1) {
            ctx.review.add("cameraFacade: кандидатов=" + facades.size() + " " + facades);
            return;
        }
        String fc = facades.get(0);
        ClassInfo ci = idx.get(fc);
        // reset: ()V с 0.0F, пишущий РОВНО 2 static F (обнуление пары;
        // применяющие методы пишут больше) — пара уникальна
        List<String> resets = new ArrayList<String>();
        Set<String> accums = new HashSet<String>();
        Set<String> pairKeys = new HashSet<String>();
        for (ClassInfo.MethodInfo m : ci.methods) {
            if (!"()V".equals(m.desc)) continue;
            boolean hasZero = false;
            for (Float f : m.constFloats) {
                if (Math.abs(f) < 1e-9F) { hasZero = true; break; }
            }
            if (!hasZero) continue;
            Set<String> w = new HashSet<String>();
            for (ClassInfo.FieldRef fr : m.fieldRefs) {
                if ((fr.opcode == Opcodes.PUTSTATIC || fr.opcode == Opcodes.PUTFIELD)
                        && "F".equals(fr.desc) && fc.equals(fr.owner)) w.add(fr.name);
            }
            if (w.size() == 2) {
                resets.add(m.name);
                List<String> sorted = new ArrayList<String>(w);
                java.util.Collections.sort(sorted);
                pairKeys.add(sorted.toString());
                if (accums.isEmpty()) accums = w;
            }
        }
        if (pairKeys.size() != 1) accums = new HashSet<String>();
        // enable-флаг: static Z, писаемый FreeLook-хендлером
        Set<String> enabledFlags = new HashSet<String>();
        for (String hkey : handlerOf.keySet()) {
            if (!hkey.equals(fc)) continue;
            String h = handlerOf.get(fc);
            String hm = h.substring(0, h.indexOf('('));
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!hm.equals(m.name)) continue;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == Opcodes.PUTSTATIC || fr.opcode == Opcodes.PUTFIELD)
                            && "Z".equals(fr.desc) && fc.equals(fr.owner))
                        enabledFlags.add(fr.name);
                }
            }
        }
        // localPlayer-геттер: static no-arg -> localPlayer (зовётся из reset)
        RoleHit ph = ctx.byRole("playerHierarchy");
        String localGetter = null;
        String localGetterDesc = null;
        if (ph != null) {
            for (RoleHit.Member mm : ph.members) {
                if (!"localPlayer".equals(mm.role)) continue;
                String lp = mm.cls;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    if (!m.isStatic) continue;
                    int rp = m.desc.indexOf(')');
                    if (rp < 0 || !"()".equals(m.desc.substring(0, rp + 1))) continue;
                    if (("L" + lp + ";").equals(m.desc.substring(rp + 1))) {
                        localGetter = m.name;
                        localGetterDesc = m.desc;
                    }
                }
            }
        }
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = fc;
        h.auto = true;
        h.evidence = "freeLookHandler=" + handlerOf.get(fc) + " resets=" + resets
                + " accums=" + accums + " enable=" + enabledFlags
                + " localGetter=" + localGetter;
        int i = 0;
        for (String a : accums) {
            RoleUtil.addMember(h, fc, "field", a, "F", "kick" + (i == 0 ? "A" : "B"),
                    "reset-zeroed");
            i++;
        }
        for (String f : enabledFlags) {
            RoleUtil.addMember(h, fc, "field", f, "Z", "freeLookEnabled", "packet-handler");
        }
        if (!resets.isEmpty()) {
            RoleUtil.addMember(h, fc, "method", resets.get(0), "()V", "reset", "zeroes-accums");
        }
        if (localGetter != null) {
            RoleUtil.addMember(h, fc, "method", localGetter, localGetterDesc,
                    "getLocalPlayer", "reset-calls");
        }
        if (accums.size() != 2 || enabledFlags.size() != 1) {
            h.auto = false;
            ctx.review.add("cameraFacade:" + fc + ": accums=" + accums.size()
                    + " enable=" + enabledFlags.size() + " — на проверку");
        } else {
            ctx.review.add("cameraFacade:" + fc + ": yaw/pitch внутри пары — рантайм-проба оси");
        }
        ctx.hits.add(h);
    }
}
