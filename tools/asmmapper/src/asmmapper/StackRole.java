package asmmapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

/**
 * itemStack: isEmpty/getItem/tag по поведению.
 * isEmpty: ()Z стака, зовомый из renderGui (пустой стак скипается сам).
 * tag: no-arg метод стака -> T, у T есть (String)->T (getCompoundTag).
 * getItem: оставшийся no-arg объектный метод (единственный — AUTO).
 */
public final class StackRole implements Role {
    public String name() { return "itemStack"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit ri = ctx.byRole("renderItem");
        if (ri == null) {
            ctx.review.add("itemStack: нет renderItem — не от чего строить линк");
            return;
        }
        String stack = null;
        String renderM = null;
        String renderC = null;
        for (RoleHit.Member m : ri.members) {
            if ("itemStack".equals(m.role)) stack = m.cls;
            if ("renderItemGui".equals(m.role)) { renderM = m.name; renderC = m.cls; }
        }
        if (stack == null || renderM == null) return;
        ClassInfo sc = idx.get(stack);
        if (sc == null) return;
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = stack;
        h.auto = true;
        StringBuilder ev = new StringBuilder();
        // isEmpty: ()Z внутри renderGui ИЛИ его stack-делегата (renderGui часто
        // делегирует внутреннему методу); декларация — в иерархии стака
        // (owner вызова врёт наследником, резолвим вверх)
        List<String> emptyCands = new ArrayList<String>();
        ClassInfo rc = idx.get(renderC);
        List<String> renderSet = new ArrayList<String>();
        if (rc != null) {
            for (ClassInfo.MethodInfo m : rc.methods) {
                if (!renderM.equals(m.name)) continue;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if (renderC.equals(mr.owner)) {
                        String k = mr.name + mr.desc;
                        if (!renderSet.contains(k)) renderSet.add(k);
                    }
                }
            }
        }
        if (rc != null) {
            for (ClassInfo.MethodInfo m : rc.methods) {
                boolean relevant = renderM.equals(m.name) || renderSet.contains(m.name + m.desc);
                if (!relevant) continue;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if (!"()Z".equals(mr.desc)) continue;
                    String decl = RoleUtil.resolveMethod(idx, mr.owner, mr.name, mr.desc);
                    if (decl == null || !stack.equals(decl)) continue;
                    if (!emptyCands.contains(mr.name)) emptyCands.add(mr.name);
                }
            }
        }
        if (emptyCands.size() == 1) {
            RoleUtil.addMember(h, stack, "method", emptyCands.get(0), "()Z",
                    "isEmpty", "renderGui-check");
            ev.append("isEmpty=").append(emptyCands.get(0)).append("; ");
        } else {
            h.auto = false;
            ctx.review.add("itemStack:" + stack + ": isEmpty-кандидатов=" + emptyCands);
        }
        // tag: no-arg -> T, у T есть (String)->T, И в стеке есть (T)V,
        // И в стеке есть поле типа T, И T — только single-<init>-параметр
        // (compound), а не item/block с перегрузками (T),(T,I),(T,II)
        // перегрузки <init> по первому параметру: compound — только single,
        // item/block — с (T),(T,I),(T,II)
        Map<String, Set<String>> initShapes = new java.util.HashMap<String, Set<String>>();
        for (ClassInfo.MethodInfo m : sc.methods) {
            if (!"<init>".equals(m.name)) continue;
            int rp = m.desc.indexOf(')');
            if (rp < 0) continue;
            List<String> params = new ArrayList<String>(
                    DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
            if (params.isEmpty() || !params.get(0).startsWith("rustme/")) continue;
            String first = params.get(0);
            String shape = "" + RoleUtil.countParams(m.desc);
            if (!initShapes.containsKey(first)) initShapes.put(first, new HashSet<String>());
            initShapes.get(first).add(shape);
        }
        List<String> tagCands = new ArrayList<String>();
        Set<String> setTypes = new java.util.HashSet<String>();
        Set<String> fieldTypes = new java.util.HashSet<String>();
        for (ClassInfo.MethodInfo m : sc.methods) {
            int rp = m.desc.indexOf(')');
            if (rp < 0) continue;
            List<String> params = new ArrayList<String>(
                    DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
            if (params.size() == 1 && params.get(0).startsWith("rustme/"))
                setTypes.add(params.get(0));
        }
        for (ClassInfo.FieldInfo f : sc.fields) {
            if (f.desc.startsWith("L") && f.desc.endsWith(";"))
                fieldTypes.add(f.desc.substring(1, f.desc.length() - 1));
        }
        for (ClassInfo.MethodInfo m : sc.methods) {
            int rp = m.desc.indexOf(')');
            if (rp < 0 || !"()".equals(m.desc.substring(0, rp + 1))) continue;
            String ret = m.desc.substring(rp + 1);
            if (!ret.startsWith("L") || !ret.endsWith(";")) continue;
            String t = ret.substring(1, ret.length() - 1);
            if (!t.startsWith("rustme/") || !ctx.isLive(t)) continue;
            ClassInfo tc = idx.get(t);
            if (tc == null) continue;
            for (ClassInfo.MethodInfo mm : tc.methods) {
                int rp2 = mm.desc.indexOf(')');
                if (rp2 < 0) continue;
                if ("(Ljava/lang/String;)".equals(mm.desc.substring(0, rp2 + 1))
                        && ("L" + t + ";").equals(mm.desc.substring(rp2 + 1))) {
                    Set<String> sh = initShapes.get(t);
                    if (setTypes.contains(t) && fieldTypes.contains(t)
                            && sh != null && sh.size() == 1 && sh.contains("1")) {
                        tagCands.add(m.name + "#" + t + "#" + mm.name);
                    }
                    break;
                }
            }
        }
        if (tagCands.size() == 1) {
            String[] p = tagCands.get(0).split("#");
            RoleUtil.addMember(h, stack, "method", p[0],
                    "()L" + p[1] + ";", "getTag", "compound-shape");
            RoleUtil.addMember(h, p[1], "method", p[2],
                    "(Ljava/lang/String;)L" + p[1] + ";", "getCompound", "compound-shape");
            ev.append("tag=").append(tagCands.get(0)).append("; ");
        } else {
            h.auto = false;
            ctx.review.add("itemStack:" + stack + ": tag-кандидатов=" + tagCands);
        }
        // getItem: no-arg объектный (кроме tag), чей тип — item-форма:
        // первый параметр <init> с перегрузками (T),(T,I),(T,II)
        Set<String> itemTypes = new java.util.HashSet<String>();
        for (Map.Entry<String, Set<String>> e : initShapes.entrySet()) {
            if (e.getValue().size() == 3 && e.getValue().contains("1")) itemTypes.add(e.getKey());
        }
        List<String> itemCands = new ArrayList<String>();
        for (ClassInfo.MethodInfo m : sc.methods) {
            int rp = m.desc.indexOf(')');
            if (rp < 0 || !"()".equals(m.desc.substring(0, rp + 1))) continue;
            String ret = m.desc.substring(rp + 1);
            if (!ret.startsWith("L") || !ret.endsWith(";")) continue;
            String t = ret.substring(1, ret.length() - 1);
            if (!itemTypes.contains(t)) continue;
            boolean isTag = false;
            for (String tc : tagCands) {
                if (tc.startsWith(m.name + "#")) { isTag = true; break; }
            }
            if (isTag) continue;
            String cand = m.name + m.desc;
            if (!itemCands.contains(cand)) itemCands.add(cand);
        }
        if (itemCands.size() == 1) {
            String mm = itemCands.get(0);
            RoleUtil.addMember(h, stack, "method", mm.substring(0, mm.indexOf('(')),
                    mm.substring(mm.indexOf('(')), "getItem", "last-noarg-object");
            ev.append("getItem=").append(mm).append("; ");
        } else {
            h.auto = false;
            ctx.review.add("itemStack:" + stack + ": getItem-кандидатов=" + itemCands);
        }
        h.evidence = ev.toString();
        ctx.hits.add(h);
    }
}
