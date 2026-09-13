package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * fontRenderer: метод drawString (String,FFIZ)I — дескриптор уникален для
 * ванильного рендера шрифта. Corroboration: getStringWidth (String)I в том же
 * классе + константа FONT_HEIGHT 9.
 */
public final class FontRendererRole implements Role {
    public String name() { return "fontRenderer"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> cands = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if ("(Ljava/lang/String;FFIZ)I".equals(m.desc)) { cands.add(ci.name); break; }
            }
        }
        if (cands.size() != 1) {
            ctx.review.add("fontRenderer: кандидатов=" + cands.size() + " " + cands + " (нужен 1)");
            return;
        }
        String cls = cands.get(0);
        ClassInfo ci = idx.get(cls);
        String draw = null;
        String width = null;
        for (ClassInfo.MethodInfo m : ci.methods) {
            if ("(Ljava/lang/String;FFIZ)I".equals(m.desc) && draw == null) draw = m.name;
            if ("(Ljava/lang/String;)I".equals(m.desc) && width == null) width = m.name;
        }
        boolean has9 = false;
        for (ClassInfo.MethodInfo m : ci.methods) {
            if (m.constInts.contains(9)) { has9 = true; break; }
        }
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = cls;
        h.auto = true;
        h.evidence = "drawString(String,FFIZ)I + getStringWidth=" + (width != null)
                + " + const9=" + has9;
        RoleUtil.addMember(h, cls, "method", draw, "(Ljava/lang/String;FFIZ)I",
                "drawString", "vanilla-desc");
        if (width != null)
            RoleUtil.addMember(h, cls, "method", width, "(Ljava/lang/String;)I",
                    "getStringWidth", "vanilla-desc");
        else {
            h.auto = false;
            ctx.review.add("fontRenderer:" + cls + ": нет (String)I — на проверку");
        }
        ctx.hits.add(h);
    }
}
