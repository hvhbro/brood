package asmmapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Общий контекст прогона: индекс, живые классы, выхлоп. Передаётся ролям. */
public final class Ctx {
    private final DumpIndex idx;
    private final Set<String> live; // достижимые (null = все)
    public final List<RoleHit> hits = new ArrayList<RoleHit>();
    public final List<String> review = new ArrayList<String>();

    public Ctx(DumpIndex idx, Set<String> live) {
        this.idx = idx;
        this.live = live;
    }

    public DumpIndex index() { return idx; }

    public boolean isLive(String cls) {
        return live == null || live.contains(cls);
    }

    public RoleHit byRole(String role) {
        for (RoleHit h : hits) {
            if (h.auto && role.equals(h.role)) return h;
        }
        return null;
    }
}
