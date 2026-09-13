package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * renderItem: GameSettings отдаёт RenderItem (синглтон-подобный геттер без
 * аргументов), у него метод render (stack,int,int)V — иконка 16x16 в GUI.
 * Первый параметр метода — класс ItemStack (побочный выход для item-ролей).
 * renderGui в начале проверяет stack.isEmpty() — второй выход (stackRole).
 */
public final class RenderItemRole implements Role {
    public String name() { return "renderItem"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        RoleHit gs = ctx.byRole("gameSettings");
        if (gs == null) {
            ctx.review.add("renderItem: нет gameSettings — не от чего строить линк");
            return;
        }
        ClassInfo gsc = idx.get(gs.cls);
        if (gsc == null) return;
        // кандидаты: no-arg методы GS, возвращающие живой rustme/-объект
        List<String> cands = new ArrayList<String>();
        for (ClassInfo.MethodInfo m : gsc.methods) {
            int rp = m.desc.indexOf(')');
            if (rp < 0) continue;
            if (!"()".equals(m.desc.substring(0, rp + 1))) continue;
            String ret = m.desc.substring(rp + 1);
            if (!ret.startsWith("L") || !ret.endsWith(";")) continue;
            String rc = ret.substring(1, ret.length() - 1);
            if (rc.startsWith("rustme/") && ctx.isLive(rc)) cands.add(rc + "#" + m.name);
        }
        // из них — у кого есть метод (Lstack;II)V
        List<String> hits = new ArrayList<String>();
        for (String cand : cands) {
            String rc = cand.substring(0, cand.indexOf('#'));
            ClassInfo ci = idx.get(rc);
            if (ci == null) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                int rp = m.desc.indexOf(')');
                if (rp < 0) continue;
                java.util.List<String> params = new ArrayList<String>(
                        DumpIndex.classesInDesc(m.desc.substring(0, rp + 1)));
                String tail = m.desc.substring(0, rp + 1);
                if (params.size() == 1 && tail.endsWith("II)") && "V".equals(m.desc.substring(rp + 1))) {
                    hits.add(cand + "#" + m.name + "#" + params.get(0));
                }
            }
        }
        if (hits.size() > 1) {
            // тайбрейк 1: у настоящего ItemStack есть static EMPTY себя
            // (второй кандидат — MapData-подобный: только Logger-static).
            List<String> withEmpty = new ArrayList<String>();
            for (String hit : hits) {
                String[] p = hit.split("#");
                ClassInfo ci = idx.get(p[3]);
                if (ci == null) continue;
                for (ClassInfo.FieldInfo f : ci.fields) {
                    if (f.isStatic && ("L" + p[3] + ";").equals(f.desc)) {
                        withEmpty.add(hit);
                        break;
                    }
                }
            }
            if (withEmpty.size() == 1) hits = withEmpty;
            else if (!withEmpty.isEmpty() && withEmpty.size() < hits.size()) {
                ctx.review.add("renderItem: тайбрейк static-self сузил " + hits.size()
                        + "->" + withEmpty.size());
                hits = withEmpty;
            }
        }
        if (hits.size() > 1) {
            // тайбрейк: renderItemOverlays рисует текст через FontRenderer
            // (количество), а нужен IntoGUI без текста. Имена шрифта — из роли.
            RoleHit font = ctx.byRole("fontRenderer");
            String fcls = font == null ? null : font.cls;
            String fdraw = null;
            if (font != null) {
                for (RoleHit.Member mm : font.members) {
                    if ("drawString".equals(mm.role)) fdraw = mm.name;
                }
            }
            if (fcls != null && fdraw != null) {
                final String fdrawF = fdraw;
                List<String> noText = new ArrayList<String>();
                for (String hit : hits) {
                    String[] p = hit.split("#");
                    ClassInfo ci = idx.get(p[0]);
                    boolean callsFont = false;
                    if (ci != null) {
                        for (ClassInfo.MethodInfo m : ci.methods) {
                            if (!p[2].equals(m.name)) continue;
                            for (ClassInfo.MethodRef mr : m.methodRefs) {
                                if (fcls.equals(mr.owner) && (fdrawF + "(Ljava/lang/String;FFIZ)I")
                                        .equals(mr.name + mr.desc)) {
                                    callsFont = true;
                                    break;
                                }
                            }
                            break;
                        }
                    }
                    if (!callsFont) noText.add(hit);
                }
                if (noText.size() == 1) hits = noText;
                else if (!noText.isEmpty() && noText.size() < hits.size()) {
                    ctx.review.add("renderItem: тайбрейк no-text сузил " + hits.size()
                            + "->" + noText.size());
                    hits = noText;
                }
            }
        }
        if (hits.size() == 1) {
            String[] p = hits.get(0).split("#");
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = p[0];
            h.auto = true;
            h.evidence = "GS." + p[1] + "() + render " + p[2] + "(stack,II)V, stack=" + p[3];
            RoleUtil.addMember(h, gs.cls, "method", p[1], "()L" + p[0] + ";",
                    "getRenderItem", "gs-noarg");
            RoleUtil.addMember(h, p[0], "method", p[2], "(L" + p[3] + ";II)V",
                    "renderItemGui", "stack-II-V");
            RoleUtil.addMember(h, p[3], "class", shortName(p[3]), "L" + p[3] + ";",
                    "itemStack", "render-param");
            ctx.hits.add(h);
        } else {
            ctx.review.add("renderItem: кандидатов=" + hits.size() + " " + hits);
        }
    }

    private static String shortName(String internal) {
        int i = internal.lastIndexOf('/');
        return i >= 0 ? internal.substring(i + 1) : internal;
    }
}
