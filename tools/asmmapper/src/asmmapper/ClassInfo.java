package asmmapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Плоские данные одного класса, снятые через ASM (без хранения ASM-нод). */
public final class ClassInfo {
    public static final class FieldInfo {
        public String name = "";
        public String desc = "";
        public String generic = ""; // generic-сигнатура (напр. List<Lxxx;>), может быть пустой
        public boolean isStatic;
    }

    public static final class FieldRef {
        public int opcode; // GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC
        public String owner = "";
        public String name = "";
        public String desc = "";
    }

    public static final class MethodRef {
        public String owner = "";
        public String name = "";
        public String desc = "";
    }

    public static final class MethodInfo {
        public String name = "";
        public String desc = "";
        public boolean isStatic;
        public final List<FieldRef> fieldRefs = new ArrayList<FieldRef>();
        public final List<MethodRef> methodRefs = new ArrayList<MethodRef>();
        public final List<String> ldcStrings = new ArrayList<String>();
        public final List<Integer> ldcInts = new ArrayList<Integer>();
        /** int-константы в порядке инструкций (ICONST/BIPUSH/SIPUSH/LDC). */
        public final List<Integer> constInts = new ArrayList<Integer>();
        /** float-константы в порядке инструкций (FCONST/LDC). */
        public final List<Float> constFloats = new ArrayList<Float>();
        /** Создаваемые типы (NEW) в порядке инструкций — для связки пост/событие. */
        public final List<String> news = new ArrayList<String>();
        /** LDC class literals (Type) — для поиска register(Class) */
        public final List<String> ldcClasses = new ArrayList<String>();
        /** double-константы в порядке инструкций (DCONST/LDC, напр. прыжок 0.42). */
        public final List<Double> constDoubles = new ArrayList<Double>();
        /** CHECKCAST/INSTANCEOF цели (internal name) */
        public final List<String> checkcasts = new ArrayList<String>();
        public final List<String> instanceofs = new ArrayList<String>();
        /**
         * Упорядоченные события для позиционных привязок:
         * "G:owner/name" = GETFIELD/GETSTATIC I своего класса,
         * "C:value" = int-константа.
         */
        public final List<String> events = new ArrayList<String>();
    }

    public String name = "";      // internal form: rustme/xxx
    public String superName = "";
    public final List<String> interfaces = new ArrayList<String>();
    public final List<FieldInfo> fields = new ArrayList<FieldInfo>();
    public final List<MethodInfo> methods = new ArrayList<MethodInfo>();
    public final Set<String> strings = new HashSet<String>();

    /** Все имена классов, на которые ссылается этот класс (рёбра графа). */
    public Set<String> refs() {
        Set<String> out = new HashSet<String>();
        if (superName != null && superName.length() > 0) out.add(superName);
        out.addAll(interfaces);
        for (FieldInfo f : fields) out.addAll(DumpIndex.classesInDesc(f.desc));
        for (MethodInfo m : methods) {
            out.addAll(DumpIndex.classesInDesc(m.desc));
            for (FieldRef fr : m.fieldRefs) if (fr.owner.length() > 0) out.add(fr.owner);
            for (MethodRef mr : m.methodRefs) if (mr.owner.length() > 0) out.add(mr.owner);
            for (String s : m.ldcStrings) {
                String asCls = DumpIndex.stringToClass(s);
                if (asCls != null) out.add(asCls);
            }
        }
        return out;
    }
}
