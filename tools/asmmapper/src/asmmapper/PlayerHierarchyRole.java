package asmmapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * playerHierarchy: wrapper и локальный игрок связями, без имён.
 * World.playerEntities — List<Lwrapper;> (generic-сигнатура поля);
 * wrapper обязан быть наследником EntityPlayer (travel-роль);
 * локальный игрок — единственный живой лист под wrapper.
 * Corroboration: tick-метод, зовущий speed-сеттер, живёт в этой цепочке.
 */
public final class PlayerHierarchyRole implements Role {
    public String name() { return "playerHierarchy"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit w = ctx.byRole("world");
        RoleHit ep = ctx.byRole("travel");
        if (w == null || ep == null) {
            ctx.review.add("playerHierarchy: нет world/travel — не от чего строить линк");
            return;
        }
        ClassInfo wc = idx.get(w.cls);
        Set<String> listElems = new HashSet<String>();
        String playersField = null;
        for (ClassInfo.FieldInfo f : wc.fields) {
            if (!"Ljava/util/List;".equals(f.desc) || f.generic.length() == 0) continue;
            String elem = listElement(f.generic);
            if (elem != null && elem.startsWith("rustme/")) {
                listElems.add(elem);
                playersField = f.name;
            }
        }
        String wrapper = null;
        for (String elem : listElems) {
            if (RoleUtil.isSubclassOf(idx, elem, ep.cls) || elem.equals(ep.cls)) {
                if (wrapper == null) wrapper = elem;
                else {
                    ctx.review.add("playerHierarchy: два List-элемента из Entity-иерархии: "
                            + listElems);
                    return;
                }
            }
        }
        if (wrapper == null) {
            ctx.review.add("playerHierarchy: в World нет List<L EntityPlayer-наследник>>: " + listElems);
            return;
        }
        // цепочка wrapper -> ... -> EntityPlayer вверх и листья вниз
        List<String> chainUp = new ArrayList<String>();
        String cur = wrapper;
        Set<String> seen = new HashSet<String>();
        while (cur != null && seen.add(cur) && !cur.equals(ep.cls)) {
            chainUp.add(cur);
            ClassInfo ci = idx.get(cur);
            cur = ci == null ? null : ci.superName;
        }
        chainUp.add(ep.cls);
        // листья: живые классы, чей транзитивный родитель — wrapper, без живых детей
        List<String> subs = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            if (RoleUtil.isSubclassOf(idx, ci.name, wrapper)) subs.add(ci.name);
        }
        Set<String> hasLiveChild = new HashSet<String>();
        for (String s : subs) {
            ClassInfo ci = idx.get(s);
            if (ci != null && subs.contains(ci.superName)) hasLiveChild.add(ci.superName);
        }
        List<String> leaves = new ArrayList<String>();
        for (String s : subs) if (!hasLiveChild.contains(s)) leaves.add(s);
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = wrapper;
        h.auto = true;
        h.evidence = "World." + playersField + "=List<" + wrapper + "> + наследник " + ep.cls
                + " + цепочка " + chainUp + " + листья " + leaves;
        RoleUtil.addMember(h, w.cls, "field", playersField, "Ljava/util/List;",
                "playersField", "world-list-generic");
        RoleUtil.addMember(h, wrapper, "class", wrapper, "L" + wrapper + ";",
                "wrapper", "world-list-generic");
        if (leaves.size() == 1) {
            RoleUtil.addMember(h, leaves.get(0), "class", leaves.get(0),
                    "L" + leaves.get(0) + ";", "localPlayer", "unique-leaf");
        } else {
            // тайбрейк: локальный игрок строится с GameSettings (нужны настройки
            // клиента); остальные листья — ctor(World[, GameProfile]).
            RoleHit gs = ctx.byRole("gameSettings");
            List<String> withGs = new ArrayList<String>();
            if (gs != null) {
                for (String leaf : leaves) {
                    ClassInfo ci = idx.get(leaf);
                    if (ci == null) continue;
                    for (ClassInfo.MethodInfo m : ci.methods) {
                        if (!"<init>".equals(m.name)) continue;
                        if (DumpIndex.classesInDesc(m.desc).contains(gs.cls)) {
                            withGs.add(leaf);
                            break;
                        }
                    }
                }
            }
            if (withGs.size() == 1) {
                h.evidence += " + localPlayer=" + withGs.get(0) + " (ctor с GameSettings)";
                RoleUtil.addMember(h, withGs.get(0), "class", withGs.get(0),
                        "L" + withGs.get(0) + ";", "localPlayer", "ctor-settings");
            } else {
                h.auto = false;
                ctx.review.add("playerHierarchy: листьев=" + leaves.size() + " " + leaves
                        + " с GameSettings=" + withGs + " — localPlayer на проверку");
            }
        }
        ctx.hits.add(h);
    }

    /** Generic List<Lxxx;> -> xxx или null (терпим к хвостам сигнатуры). */
    static String listElement(String generic) {
        int i = generic.indexOf("List<L");
        if (i < 0) return null;
        int s = i + "List<L".length();
        int j = s;
        while (j < generic.length()) {
            char c = generic.charAt(j);
            if (c == ';' || c == '>' || c == '<') break;
            j++;
        }
        if (j <= s) return null;
        return generic.substring(s, j);
    }
}
