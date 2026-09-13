package asmmapper;

import java.util.ArrayList;
import java.util.List;

/** Один результат роли: класс + вытащенные из методов члены + evidence. */
public final class RoleHit {
    public static final class Member {
        public String cls = "";
        public String kind = ""; // field|method
        public String name = "";
        public String desc = "";
        public String role = "";
        public String via = "";
    }

    public String role = "";
    public String cls = "";
    public final List<Member> members = new ArrayList<Member>();
    public String evidence = "";
    public boolean auto; // true = уверенный единичный матч
}
