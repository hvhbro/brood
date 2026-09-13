package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * net: путь отправки ввода — sender из movePackets-роли + источник инстанса:
 * поле SP-цепочки типа sender'а либо static no-arg геттер. Netty-детали
 * (pipeline/addLast) стабильны, маппим только путь + метод.
 */
public final class NetRole implements Role {
    public String name() { return "net"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit ph = ctx.byRole("playerHierarchy");
        RoleHit pk = ctx.byRole("movePackets");
        if (ph == null || pk == null) {
            ctx.review.add("net: нет playerHierarchy/movePackets — не от чего строить линк");
            return;
        }
        String senderCls = null;
        String senderMeth = null;
        String senderDesc = null;
        for (RoleHit.Member m : pk.members) {
            if ("sendMoveInput".equals(m.role)) {
                senderCls = m.cls;
                senderMeth = m.name;
                senderDesc = m.desc;
            }
        }
        if (senderCls == null) {
            ctx.review.add("net: нет sendMoveInput — не от чего строить линк");
            return;
        }
        // цепочка SP: сам + наследники + предки
        List<String> chain = new ArrayList<String>();
        String localPlayer = null;
        for (RoleHit.Member m : ph.members) {
            if ("localPlayer".equals(m.role)) localPlayer = m.cls;
        }
        if (localPlayer != null) {
            chain.add(localPlayer);
            for (ClassInfo ci : idx.all().values()) {
                if (RoleUtil.isSubclassOf(idx, ci.name, localPlayer)) chain.add(ci.name);
            }
            java.util.Set<String> seen = new java.util.HashSet<String>();
            String cur = localPlayer;
            while (cur != null && seen.add(cur)) {
                if (!chain.contains(cur)) chain.add(cur);
                ClassInfo ci = idx.get(cur);
                cur = ci == null ? null : ci.superName;
                if (cur != null && cur.startsWith("java/")) break;
            }
        }
        List<String> fieldSrc = new ArrayList<String>();
        for (String owner : chain) {
            ClassInfo ci = idx.get(owner);
            if (ci == null) continue;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (("L" + senderCls + ";").equals(f.desc)) fieldSrc.add(owner + "#" + f.name);
            }
        }
        // синглтон-поле: static поле типа sender'а в любом живом классе
        // (читается рефлексией get(null) — GetStaticFieldID не нужен)
        List<String> singletonSrc = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.FieldInfo f : ci.fields) {
                if (f.isStatic && ("L" + senderCls + ";").equals(f.desc))
                    singletonSrc.add(ci.name + "#" + f.name);
            }
        }
        List<String> staticSrc = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!m.isStatic) continue;
                int rp = m.desc.indexOf(')');
                if (rp < 0) continue;
                if ("()".equals(m.desc.substring(0, rp + 1))
                        && ("L" + senderCls + ";").equals(m.desc.substring(rp + 1)))
                    staticSrc.add(ci.name + "#" + m.name);
            }
        }
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = senderCls;
        h.auto = true;
        h.evidence = "sendMoveInput + fieldSrc=" + fieldSrc + " staticSrc=" + staticSrc
                + " singletonSrc=" + singletonSrc;
        RoleUtil.addMember(h, senderCls, "method", senderMeth, senderDesc,
                "sendMoveInput", "movePackets");
        if (fieldSrc.size() == 1 && staticSrc.isEmpty() && singletonSrc.isEmpty()) {
            String[] p = fieldSrc.get(0).split("#");
            RoleUtil.addMember(h, p[0], "field", p[1], "L" + senderCls + ";",
                    "connection", "chain-field");
        } else if (staticSrc.size() == 1 && fieldSrc.isEmpty() && singletonSrc.isEmpty()) {
            String[] p = staticSrc.get(0).split("#");
            RoleUtil.addMember(h, p[0], "method", p[1], "()L" + senderCls + ";",
                    "connectionProvider", "static-noarg");
        } else if (singletonSrc.size() == 1 && fieldSrc.isEmpty() && staticSrc.isEmpty()) {
            String[] p = singletonSrc.get(0).split("#");
            RoleUtil.addMember(h, p[0], "field", p[1], "L" + senderCls + ";",
                    "connectionSingleton", "static-field");
        } else {
            h.auto = false;
            ctx.review.add("net:" + senderCls + ": fieldSrc=" + fieldSrc + " staticSrc=" + staticSrc
                    + " singletonSrc=" + singletonSrc);
        }
        ctx.hits.add(h);
    }
}
