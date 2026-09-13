package asmmapper;

import java.util.ArrayList;
import java.util.List;

/**
 * gui: база Gui — static drawRect (IIIII)V (сигнатура ванильного Gui);
 * ingame — наследник базы с (F)V-методом рендера оверлея.
 * Подмена инстанса GuiIngame — точка входа HUD allied-рендера.
 */
public final class GuiRole implements Role {
    public String name() { return "gui"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> bases = new ArrayList<String>();
        String drawRect = null;
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (m.isStatic && "(IIIII)V".equals(m.desc)) {
                    bases.add(ci.name);
                    drawRect = m.name;
                    break;
                }
            }
        }
        if (bases.size() != 1) {
            // тайбрейк: настоящая база Gui — та, у которой есть наследник с (F)V
            List<String> withFChild = new ArrayList<String>();
            for (String b : bases) {
                for (ClassInfo ci : idx.all().values()) {
                    if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
                    if (!b.equals(ci.superName)) continue;
                    for (ClassInfo.MethodInfo m : ci.methods) {
                        if (!m.isStatic && "(F)V".equals(m.desc)) {
                            withFChild.add(b);
                            break;
                        }
                    }
                    if (withFChild.contains(b)) break;
                }
            }
            if (withFChild.size() == 1) bases = withFChild;
            else {
                ctx.review.add("gui: base-кандидатов=" + bases.size() + " " + bases + " (нужен 1)");
                return;
            }
        }
        String base = bases.get(0);
        RoleHit hb = new RoleHit();
        hb.role = "guiBase";
        hb.cls = base;
        hb.auto = true;
        hb.evidence = "static drawRect (IIIII)V";
        RoleUtil.addMember(hb, base, "method", drawRect, "(IIIII)V", "drawRect", "vanilla-desc");
        ctx.hits.add(hb);
        List<String> ingames = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            if (!base.equals(ci.superName)) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!m.isStatic && "(F)V".equals(m.desc)) { ingames.add(ci.name); break; }
            }
        }
        if (ingames.size() == 1) {
            String ig = ingames.get(0);
            RoleHit h = new RoleHit();
            h.role = "guiIngame";
            h.cls = ig;
            h.auto = true;
            h.evidence = "наследник " + base + " + (F)V рендера";
            for (ClassInfo.MethodInfo m : idx.get(ig).methods) {
                if (!m.isStatic && "(F)V".equals(m.desc)) {
                    RoleUtil.addMember(h, ig, "method", m.name, "(F)V",
                            "renderGameOverlay", "subclass-F");
                    break;
                }
            }
            ctx.hits.add(h);
        } else {
            ctx.review.add("guiIngame: наследников " + base + " с (F)V: " + ingames.size()
                    + " " + ingames);
        }
    }
}
