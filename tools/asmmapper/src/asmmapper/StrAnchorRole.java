package asmmapper;

import java.util.ArrayList;
import java.util.List;

/** strAnchor: класс по ldc-строке (параметризуется ролью и подстрокой). */
public final class StrAnchorRole implements Role {
    private final String role;
    private final String needle;

    public StrAnchorRole(String role, String needle) {
        this.role = role;
        this.needle = needle;
    }

    public String name() { return role; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> found = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name)) continue;
            for (String s : ci.strings) {
                if (s != null && s.indexOf(needle) >= 0) { found.add(ci.name); break; }
            }
        }
        if (found.size() == 1) {
            RoleHit h = new RoleHit();
            h.role = role;
            h.cls = found.get(0);
            h.auto = true;
            h.evidence = "ldc \"" + needle + "\"";
            ctx.hits.add(h);
        } else {
            ctx.review.add(role + ": ldc \"" + needle + "\" в классах=" + found.size() + " " + found);
        }
    }
}
