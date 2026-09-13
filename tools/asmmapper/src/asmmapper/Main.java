package asmmapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ASM-автомаппер (способ Антона):
 *  1) грузим дамп классов через ASM,
 *  2) строим достижимость от main (фильтр фейков-дублей),
 *  3) ищем МЕТОДЫ по поведению, из методов тащим ПОЛЯ,
 *  4) пишем classmap/membermap/review (старого дампа и classmap не надо).
 *
 * Запуск:
 *   java -cp "lib/asm-9.7.jar;lib/asm-tree-9.7.jar;bin" asmmapper.Main
 *     --dump ..\..\newdump\minecraft --main ru.meproject.Main --out out
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String dump = null;
        String mainCls = "ru.meproject.Main";
        String out = "out";
        String diag = null;
        String oldDump = null;
        boolean selftest = false;
        for (int i = 0; i < args.length; i++) {
            if ("--dump".equals(args[i]) && i + 1 < args.length) dump = args[++i];
            else if ("--main".equals(args[i]) && i + 1 < args.length) mainCls = args[++i];
            else if ("--out".equals(args[i]) && i + 1 < args.length) out = args[++i];
            else if ("--diag".equals(args[i]) && i + 1 < args.length) diag = args[++i];
            else if ("--old-dump".equals(args[i]) && i + 1 < args.length) oldDump = args[++i];
            else if ("--selftest".equals(args[i])) selftest = true;
        }
        if (dump == null) {
            System.out.println("usage: Main --dump <dir> [--main ru.meproject.Main] [--out out] [--diag ...] [--selftest --old-dump <dir>]");
            System.exit(2);
        }
        if (selftest) {
            boolean ok = runSelftest(dump, oldDump == null ? "dump" : oldDump, mainCls);
            System.exit(ok ? 0 : 1);
        }
        StringBuilder log = new StringBuilder();
        DumpIndex idx = DumpIndex.load(new File(dump), log);
        System.out.print(log.toString());
        System.out.println("indexed classes: " + idx.all().size() + ", broken: " + idx.broken());
        if (diag != null) {
            runDiag(idx, diag);
            return;
        }

        Reach reach = Reach.build(idx, mainCls);
        System.out.println("reachable: " + reach.reachable.size()
                + ", fakeSuspects(rustme/): " + reach.fakeSuspects.size());

        Roles roles = new Roles(idx, reach.reachable);
        roles.runAll();
        long auto = 0;
        for (RoleHit h : roles.hits) if (h.auto) auto++;
        System.out.println("role hits: " + roles.hits.size() + " (auto=" + auto
                + "), review=" + roles.review.size());

        File outDir = new File(out);
        outDir.mkdirs();
        writeClassmap(new File(outDir, "classmap.json"), roles);
        writeMembermap(new File(outDir, "membermap.json"), roles);
        writeReview(new File(outDir, "review.json"), roles);
        writeReach(new File(outDir, "reachable.json"), reach);
        writeReport(new File(outDir, "report.txt"), dump, mainCls, idx, reach, roles);
        System.out.println("wrote " + outDir.getPath());
    }

    private static void writeClassmap(File f, Roles roles) throws Exception {
        StringBuilder b = new StringBuilder("{\n");
        boolean first = true;
        List<RoleHit> sorted = new ArrayList<RoleHit>(roles.hits);
        Collections.sort(sorted, new java.util.Comparator<RoleHit>() {
            public int compare(RoleHit a, RoleHit b) {
                int c = a.role.compareTo(b.role);
                return c != 0 ? c : a.cls.compareTo(b.cls);
            }
        });
        for (RoleHit h : sorted) {
            if (!h.auto) continue;
            if (!first) b.append(",\n");
            first = false;
            b.append("  ").append(Json.str(h.role)).append(": ").append(Json.str(h.cls));
        }
        b.append(first ? "}\n" : "\n}\n");
        write(f, b.toString());
    }

    private static void writeMembermap(File f, Roles roles) throws Exception {
        StringBuilder b = new StringBuilder("[\n");
        boolean first = true;
        for (RoleHit h : roles.hits) {
            if (!h.auto) continue;
            for (RoleHit.Member m : h.members) {
                if (!first) b.append(",\n");
                first = false;
                b.append("  {\"role\":").append(Json.str(m.role))
                 .append(",\"class\":").append(Json.str(m.cls))
                 .append(",\"kind\":").append(Json.str(m.kind))
                 .append(",\"name\":").append(Json.str(m.name))
                 .append(",\"desc\":").append(Json.str(m.desc))
                 .append(",\"via\":").append(Json.str(m.via)).append("}");
            }
        }
        b.append(first ? "]\n" : "\n]\n");
        write(f, b.toString());
    }

    private static void writeReview(File f, Roles roles) throws Exception {
        StringBuilder b = new StringBuilder("[\n");
        for (int i = 0; i < roles.review.size(); i++) {
            if (i > 0) b.append(",\n");
            b.append("  ").append(Json.str(roles.review.get(i)));
        }
        b.append(roles.review.isEmpty() ? "]\n" : "\n]\n");
        write(f, b.toString());
    }

    private static void writeReach(File f, Reach reach) throws Exception {
        List<String> r = new ArrayList<String>(reach.reachable);
        List<String> fk = new ArrayList<String>(reach.fakeSuspects);
        Collections.sort(r);
        Collections.sort(fk);
        StringBuilder b = new StringBuilder("{\"reachable\":");
        b.append(Json.list(r)).append(",\"fakeSuspects\":").append(Json.list(fk)).append("}\n");
        write(f, b.toString());
    }

    private static void writeReport(File f, String dump, String mainCls,
                                    DumpIndex idx, Reach reach, Roles roles) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append("dump=").append(dump).append("\n");
        b.append("main=").append(mainCls).append("\n");
        b.append("classes=").append(idx.all().size()).append(" broken=").append(idx.broken()).append("\n");
        b.append("reachable=").append(reach.reachable.size())
         .append(" fakeSuspects=").append(reach.fakeSuspects.size()).append("\n");
        b.append("hits=").append(roles.hits.size()).append(" review=").append(roles.review.size()).append("\n");
        for (RoleHit h : roles.hits) {
            b.append((h.auto ? "AUTO " : "INFO ") + h.role + " -> " + h.cls
                    + " // " + h.evidence + "\n");
            for (RoleHit.Member m : h.members) {
                b.append("    " + m.kind + " " + m.name + m.desc + " [" + m.role + "]\n");
            }
        }
        for (String s : roles.review) b.append("REVIEW " + s + "\n");
        write(f, b.toString());
    }

    private static void write(File f, String s) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try { w.write(s); } finally { w.close(); }
    }

    /**
     * Регрессия: A) голдены на новом дампе (точные имена/состав),
     * B) структурные инварианты на старом дампе (без имён — проверка что роли
     * не переподогнаны под один билд).
     */
    private static boolean runSelftest(String newDir, String oldDir, String mainCls) throws Exception {
        boolean ok = true;
        System.out.println("== A: goldens on " + newDir);
        Roles r = pipeline(newDir, mainCls);
        ok &= check(roleCls(r, "entityPos"), "rustme/liiIiiIIiI", "A entityPos class");
        ok &= check(roleCls(r, "scaledRes"), "rustme/lililIliiI", "A scaledRes class");
        ok &= check(roleCls(r, "travel"), "rustme/iilIiiIIiI", "A travel class");
        ok &= check(roleCls(r, "world"), "rustme/IllIIllIiI", "A world class");
        ok &= check(roleCls(r, "minecraft"), "rustme/iliiIilIiI", "A minecraft class");
        ok &= check(roleCls(r, "gameSettings"), "rustme/iiillIliiI", "A gameSettings class");
        ok &= check(membersOf(r, "entityPos"), 9, "A entityPos members");
        ok &= check(membersOf(r, "scaledRes"), 6, "A scaledRes members");
        ok &= check(hasMemberRole(r, "travel", "speed.field"), true, "A travel speed.field");
        ok &= check(hasMemberRole(r, "travel", "speed.setter"), true, "A travel speed.setter");
        ok &= check(roleCls(r, "playerHierarchy"), "rustme/lliiiIIIiI", "A wrapper class");
        ok &= check(hasMemberRole(r, "playerHierarchy", "localPlayer"), true, "A localPlayer");
        ok &= check(roleCls(r, "fontRenderer"), "rustme/liiiIIliiI", "A fontRenderer class");
        ok &= check(roleCls(r, "guiBase"), "rustme/iIiiIIliiI", "A guiBase class");
        ok &= check(roleCls(r, "guiIngame"), "rustme/liliIIliiI", "A guiIngame class");
        ok &= check(roleCls(r, "keyBinding"), "rustme/iIiIIIlliI", "A keyBinding class");
        ok &= check(roleCls(r, "entityMotion"), "rustme/liiIiiIIiI", "A entityMotion class");
        ok &= check(hasMemberRole(r, "entityMotion", "motionY.field"), true, "A motionY.field");
        ok &= check(roleCls(r, "movementSpeedAttr"), "rustme/liIIiIIIiI", "A attr holder class");
        ok &= check(hasMemberRole(r, "movementSpeedAttr", "getValue"), true, "A attr getValue");
        ok &= check(hasMemberRole(r, "movementSpeedAttr", "setBaseValue"), true, "A attr setBaseValue");
        ok &= check(hasMemberRole(r, "movementSpeedAttr", "getAttributeInstance"), true, "A getAttributeInstance");
        ok &= check(roleCls(r, "renderItem"), "rustme/iiIliIiIiI", "A renderItem class");
        ok &= check(hasMemberRole(r, "renderItem", "renderItemGui"), true, "A renderItemGui");
        ok &= check(hasMemberRole(r, "renderItem", "itemStack"), true, "A itemStack class");
        ok &= check(roleCls(r, "equipSlot"), "rustme/liIllilliI", "A equipSlot class");
        ok &= check(roleCls(r, "itemStack"), "rustme/ilIiilIIiI", "A itemStack explicit");
        ok &= check(hasMemberRole(r, "itemStack", "isEmpty"), true, "A stack isEmpty");
        ok &= check(hasMemberRole(r, "itemStack", "getItem"), true, "A stack getItem");
        ok &= check(hasMemberRole(r, "itemStack", "getTag"), true, "A stack getTag");
        ok &= check(roleCls(r, "cameraFacade"), "rustme/lIlIIliliI", "A cameraFacade class");
        ok &= check(hasMemberRole(r, "cameraFacade", "freeLookEnabled"), true, "A freeLookEnabled");
        ok &= check(roleCls(r, "inventory"), "rustme/liliIIIIiI", "A inventory class");
        ok &= check(hasMemberRole(r, "inventory", "getStackInSlot"), true, "A getStackInSlot");
        ok &= check(hasMemberRole(r, "inventory", "currentItem"), true, "A currentItem");
        ok &= check(roleCls(r, "sprint"), "rustme/iilIiiIIiI", "A sprint class");
        ok &= check(hasMemberRole(r, "sprint", "isSneaking"), true, "A isSneaking");
        ok &= check(hasMemberRole(r, "sprint", "setSprint"), true, "A setSprint");
        ok &= check(roleCls(r, "movementInput"), "rustme/ilIiiIIIiI", "A movementInput class");
        ok &= check(roleCls(r, "noSlow"), "rustme/iilIiiIIiI", "A noSlow class");
        ok &= check(hasMemberRole(r, "noSlow", "potion"), true, "A noSlow potion");
        ok &= check(hasMemberRole(r, "noSlow", "riding"), true, "A noSlow riding");
        System.out.println("== B: invariants on " + oldDir);
        Roles o = pipeline(oldDir, mainCls);
        ok &= check(autoRoles(o).contains("entityPos"), true, "B entityPos AUTO");
        ok &= check(autoRoles(o).contains("scaledRes"), true, "B scaledRes AUTO");
        ok &= check(autoRoles(o).contains("travel"), true, "B travel AUTO");
        ok &= check(autoRoles(o).contains("world"), true, "B world AUTO");
        ok &= check(autoRoles(o).contains("minecraft"), true, "B minecraft AUTO");
        ok &= check(autoRoles(o).contains("gameSettings"), true, "B gameSettings AUTO");
        ok &= check(linkWorldIsEpCtorParam(o), true, "B world==EP ctor param");
        ok &= check(linkMcHoldsWorld(o), true, "B minecraft holds world");
        ok &= check(autoRoles(o).contains("fontRenderer"), true, "B fontRenderer AUTO");
        ok &= check(autoRoles(o).contains("guiIngame"), true, "B guiIngame AUTO");
        ok &= check(autoRoles(o).contains("sprint"), true, "B sprint AUTO");
        ok &= check(autoRoles(o).contains("movementInput"), true, "B movementInput AUTO");
        ok &= check(autoRoles(o).contains("noSlow"), true, "B noSlow AUTO");
        ok &= check(autoRoles(o).contains("renderItem"), true, "B renderItem AUTO");
        ok &= check(autoRoles(o).contains("itemStack"), true, "B itemStack AUTO");
        ok &= check(autoRoles(o).contains("cameraFacade"), true, "B cameraFacade AUTO");
        ok &= check(autoRoles(o).contains("inventory"), true, "B inventory AUTO");
        ok &= check(autoRoles(o).contains("equipSlot"), true, "B equipSlot AUTO");
        System.out.println(ok ? "SELFTEST OK" : "SELFTEST FAIL");
        return ok;
    }

    private static Roles pipeline(String dir, String mainCls) throws Exception {
        DumpIndex idx = DumpIndex.load(new java.io.File(dir), null);
        Reach reach = Reach.build(idx, mainCls);
        Roles roles = new Roles(idx, reach.reachable);
        roles.runAll();
        return roles;
    }

    private static String roleCls(Roles r, String role) {
        RoleHit h = r.byRole(role);
        return h == null ? null : h.cls;
    }

    private static int membersOf(Roles r, String role) {
        RoleHit h = r.byRole(role);
        return h == null ? -1 : h.members.size();
    }

    private static boolean hasMemberRole(Roles r, String role, String memberRole) {
        RoleHit h = r.byRole(role);
        if (h == null) return false;
        for (RoleHit.Member m : h.members) {
            if (memberRole.equals(m.role)) return true;
        }
        return false;
    }

    private static java.util.Set<String> autoRoles(Roles r) {
        java.util.Set<String> s = new java.util.HashSet<String>();
        for (RoleHit h : r.hits) if (h.auto) s.add(h.role);
        return s;
    }

    private static boolean linkWorldIsEpCtorParam(Roles r) {
        RoleHit ep = r.byRole("travel");
        RoleHit w = r.byRole("world");
        if (ep == null || w == null) return false;
        ClassInfo epc = r.index().get(ep.cls);
        if (epc == null) return false;
        for (ClassInfo.MethodInfo m : epc.methods) {
            if (!"<init>".equals(m.name)) continue;
            if (DumpIndex.classesInDesc(m.desc).contains(w.cls)) return true;
        }
        return false;
    }

    private static boolean linkMcHoldsWorld(Roles r) {
        RoleHit mc = r.byRole("minecraft");
        RoleHit w = r.byRole("world");
        if (mc == null || w == null) return false;
        ClassInfo mcc = r.index().get(mc.cls);
        if (mcc == null) return false;
        for (ClassInfo.FieldInfo f : mcc.fields) {
            if (("L" + w.cls + ";").equals(f.desc)) return true;
        }
        return false;
    }

    private static boolean check(Object got, Object want, String label) {
        boolean pass = want == null ? got == null : want.equals(got);
        System.out.println((pass ? "PASS " : "FAIL ") + label + " (got=" + got + " want=" + want + ")");
        return pass;
    }

    /** Диагностика гипотез: class <name> | fff | c320 | runnable */
    private static void runDiag(DumpIndex idx, String diag) {        String[] parts = diag.split(" ", 2);
        String mode = parts[0];
        if ("class".equals(mode) && parts.length > 1) {
            String name = parts[1].replace('.', '/');
            ClassInfo ci = idx.get(name);
            if (ci == null) { System.out.println("no such class: " + name); return; }
            System.out.println("class " + ci.name + " super=" + ci.superName
                    + " ifaces=" + ci.interfaces + " fields=" + ci.fields.size()
                    + " methods=" + ci.methods.size());
            for (ClassInfo.MethodInfo m : ci.methods) {
                System.out.println("  " + (m.isStatic ? "static " : "") + m.name + m.desc);
            }
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!m.constFloats.isEmpty() || !m.constInts.isEmpty())
                    System.out.println("  const " + m.name + m.desc
                            + " floats=" + m.constFloats + " ints=" + m.constInts);
            }
        } else if ("fff".equals(mode)) {
            for (ClassInfo ci : idx.all().values()) {
                if (!ci.name.startsWith("rustme/")) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    if ("(FFF)V".equals(m.desc))
                        System.out.println(ci.name + "." + m.name + " floats=" + m.constFloats);
                }
            }
        } else if ("c320".equals(mode)) {
            for (ClassInfo ci : idx.all().values()) {
                if (!ci.name.startsWith("rustme/")) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    if (!"<init>".equals(m.name)) continue;
                    boolean has320 = m.events.contains("C:320");
                    boolean has240 = m.events.contains("C:240");
                    if (has320 || has240)
                        System.out.println(ci.name + " <init> 320=" + has320 + " 240=" + has240
                                + " ev=" + m.events);
                }
            }
        } else if ("runnable".equals(mode)) {
            for (ClassInfo ci : idx.all().values()) {
                if (!ci.name.startsWith("rustme/")) continue;
                if (ci.interfaces.contains("java/lang/Runnable"))
                    System.out.println(ci.name + " fields=" + ci.fields.size()
                            + " methods=" + ci.methods.size() + " super=" + ci.superName);
            }
        } else if ("writes".equals(mode) && parts.length > 1) {
            String name = parts[1].replace('.', '/');
            ClassInfo ci = idx.get(name);
            if (ci == null) { System.out.println("no such class: " + name); return; }
            java.util.Map<String, Integer> w = new java.util.HashMap<String, Integer>();
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"<init>".equals(m.name)) continue;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == org.objectweb.asm.Opcodes.PUTFIELD
                            || fr.opcode == org.objectweb.asm.Opcodes.PUTSTATIC)
                            && "I".equals(fr.desc) && ci.name.equals(fr.owner)) {
                        Integer c = w.get(fr.name);
                        w.put(fr.name, c == null ? 1 : c + 1);
                    }
                }
            }
            System.out.println("writes(<init>,I) " + name + " = " + w);
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"()I".equals(m.desc)) continue;
                StringBuilder sb = new StringBuilder();
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                            || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                            && "I".equals(fr.desc) && ci.name.equals(fr.owner))
                        sb.append(fr.name).append(' ');
                }
                if (sb.length() > 0) System.out.println("  ()I " + m.name + " reads " + sb);
            }
        } else if ("clinit".equals(mode) && parts.length > 1) {
            String name = parts[1].replace('.', '/');
            ClassInfo ci = idx.get(name);
            if (ci == null) { System.out.println("no such class: " + name); return; }
            System.out.println("fields of " + name + ":");
            for (ClassInfo.FieldInfo f : ci.fields)
                System.out.println("  " + (f.isStatic ? "static " : "") + f.name + " " + f.desc);
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"<clinit>".equals(m.name)) continue;
                System.out.println("doubles=" + m.constDoubles);
                StringBuilder sb = new StringBuilder();
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if (fr.opcode == org.objectweb.asm.Opcodes.PUTSTATIC)
                        sb.append(fr.name).append(' ');
                }
                System.out.println("putstatics=" + sb);
            }
        } else if ("dcalls".equals(mode) && parts.length > 1) {
            String[] pp = parts[1].split(" ", 2);
            ClassInfo ci = idx.get(pp[0].replace('.', '/'));
            if (ci == null) { System.out.println("no such class"); return; }
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (pp.length > 1 && !pp[1].equals(m.name)) continue;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if ("()D".equals(mr.desc)) System.out.println(m.name + " -> " + mr.owner + "." + mr.name);
                }
            }
        } else if ("fconst".equals(mode) && parts.length > 1) {
            String[] pp = parts[1].split(" ", 3);
            float want = Float.parseFloat(pp[0]);
            for (String cls : pp[1].split(",")) {
                ClassInfo ci = idx.get(cls.replace('.', '/'));
                if (ci == null) { System.out.println("no " + cls); continue; }
                for (ClassInfo.MethodInfo m : ci.methods) {
                    for (Float f : m.constFloats) {
                        if (Math.abs(f - want) < 1e-6F) {
                            System.out.println(cls + "." + m.name + m.desc);
                            break;
                        }
                    }
                }
            }
        } else if ("zreads".equals(mode) && parts.length > 1) {
            String[] pp = parts[1].split(" ", 3);
            ClassInfo ci = idx.get(pp[0].replace('.', '/'));
            if (ci == null) { System.out.println("no such class"); return; }
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!pp[1].equals(m.name)) continue;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if ("()Z".equals(mr.desc)) System.out.println("  call " + mr.owner + "." + mr.name);
                }
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ("Z".equals(fr.desc) && (fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                            || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC))
                        System.out.println("  get " + fr.owner + "/" + fr.name);
                }
            }
        } else if ("itemsubs".equals(mode)) {
            String base = "rustme/iIiilIIIiI";
            RoleHit ri = null;
            // try to find item base from renderItem role if available
            for (ClassInfo ci : idx.all().values()) {
                if (!ci.name.startsWith("rustme/")) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    if (m.desc.contains("Lrustme/iIiilIIIiI;")) { base = "rustme/iIiilIIIiI"; break; }
                }
            }
            System.out.println("item base guess " + base);
            for (ClassInfo ci : idx.all().values()) {
                if (base.equals(ci.superName)) {
                    System.out.println(ci.name + " fields=" + ci.fields.size() + " methods=" + ci.methods.size());
                    for (ClassInfo.MethodInfo m : ci.methods) {
                        if (m.desc.contains("Lrustme/") && m.desc.contains(";")) {
                            // print methods with object params
                        }
                    }
                }
            }
        } else if ("newed".equals(mode) && parts.length > 1) {
            String want = parts[1].replace('.', '/');
            for (ClassInfo ci : idx.all().values()) {
                if (!ci.name.startsWith("rustme/")) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    if (m.news.contains(want)) System.out.println(ci.name + "." + m.name + m.desc);
                }
            }
        } else if ("dconst".equals(mode) && parts.length > 1) {
            double want = Double.parseDouble(parts[1]);
            int shown = 0;
            for (ClassInfo ci : idx.all().values()) {
                if (!ci.name.startsWith("rustme/")) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    for (Double d : m.constDoubles) {
                        if (Math.abs(d - want) < 1e-9) {
                            if (shown++ < 25)
                                System.out.println(ci.name + "." + m.name + m.desc);
                            break;
                        }
                    }
                }
            }
            System.out.println("total methods with " + want + ": " + shown);
        } else if ("jump".equals(mode) && parts.length > 1) {
            String name = parts[1].replace('.', '/');
            ClassInfo ci = idx.get(name);
            if (ci == null) { System.out.println("no such class: " + name); return; }
            for (ClassInfo.MethodInfo m : ci.methods) {
                boolean hasJump = false;
                for (Double d : m.constDoubles) {
                    if (Math.abs(d - 0.42D) < 1e-9) { hasJump = true; break; }
                }
                if (!hasJump) continue;
                StringBuilder sb = new StringBuilder();
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == org.objectweb.asm.Opcodes.PUTFIELD
                            || fr.opcode == org.objectweb.asm.Opcodes.PUTSTATIC)
                            && "D".equals(fr.desc))
                        sb.append(fr.owner).append('/').append(fr.name).append(' ');
                }
                System.out.println(m.name + m.desc + " Dputs=" + sb);
            }
            // иерархия-кандидаты: суперцепочки
            for (String cand : new String[]{"rustme/IiIiiIIIiI", "rustme/IilliIliiI",
                    "rustme/lilliIliiI", "rustme/lliiiIIIiI"}) {
                StringBuilder chain = new StringBuilder();
                java.util.Set<String> seen = new java.util.HashSet<String>();
                String cur = cand;
                while (cur != null && seen.add(cur)) {
                    chain.append(cur).append(" <- ");
                    ClassInfo c2 = idx.get(cur);
                    cur = c2 == null ? null : c2.superName;
                    if (cur != null && cur.startsWith("java/")) { chain.append(cur); break; }
                }
                System.out.println(chain);
            }
        } else if ("callers".equals(mode) && parts.length > 1) {
            String[] pp = parts[1].split(" ", 3);
            String owner = pp[0].replace('.', '/');
            String mname = pp[1];
            for (ClassInfo ci : idx.all().values()) {
                if (!ci.name.startsWith("rustme/")) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    for (ClassInfo.MethodRef mr : m.methodRefs) {
                        if (owner.equals(mr.owner) && mname.equals(mr.name)
                                && (pp.length < 3 || pp[2].equals(mr.desc)))
                            System.out.println(ci.name + "." + m.name + m.desc);
                    }
                }
            }
        } else if ("mresolve".equals(mode) && parts.length > 1) {
            String[] pp = parts[1].split(" ", 4);
            String owner = pp[0].replace('.', '/');
            String mname = pp[1];
            String mdesc = pp.length > 2 ? pp[2] : null;
            java.util.Set<String> seen = new java.util.HashSet<String>();
            String cur = owner;
            while (cur != null && seen.add(cur)) {
                ClassInfo ci = idx.get(cur);
                if (ci == null) break;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    if (!mname.equals(m.name)) continue;
                    if (mdesc != null && !mdesc.equals(m.desc)) continue;
                    StringBuilder sb = new StringBuilder();
                    for (ClassInfo.FieldRef fr : m.fieldRefs) {
                        if (fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                                || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                            sb.append("get:").append(fr.owner).append('/').append(fr.name).append(fr.desc).append(' ');
                    }
                    StringBuilder cb = new StringBuilder();
                    for (ClassInfo.MethodRef mr : m.methodRefs)
                        cb.append(mr.owner).append('.').append(mr.name).append(mr.desc).append(' ');
                    System.out.println("decl " + cur + "." + m.name + m.desc + " [" + sb + "] calls=[" + cb + "] ints=" + m.constInts);
                }
                cur = ci.superName;
            }
        } else if ("writesof".equals(mode) && parts.length > 1) {
            String[] pp = parts[1].split(" ", 2);
            ClassInfo ci = idx.get(pp[0].replace('.', '/'));
            if (ci == null) { System.out.println("no such class"); return; }
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (pp.length > 1 && !pp[1].equals(m.name)) continue;
                StringBuilder sb = new StringBuilder();
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if (fr.opcode == org.objectweb.asm.Opcodes.PUTFIELD
                            || fr.opcode == org.objectweb.asm.Opcodes.PUTSTATIC)
                        sb.append("put:").append(fr.owner).append('/').append(fr.name).append(fr.desc).append(' ');
                    else
                        sb.append("get:").append(fr.owner).append('/').append(fr.name).append(fr.desc).append(' ');
                }
                StringBuilder cb = new StringBuilder();
                for (ClassInfo.MethodRef mr : m.methodRefs)
                    cb.append(mr.owner).append('.').append(mr.name).append(mr.desc).append(' ');
                System.out.println(m.name + m.desc + " [" + sb + "] calls=[" + cb + "] floats=" + m.constFloats + " ints=" + m.constInts);
            }
        } else if ("fieldreaders".equals(mode) && parts.length > 1) {
            String[] pp = parts[1].split(" ", 2);
            for (ClassInfo ci : idx.all().values()) {
                if (!ci.name.startsWith("rustme/")) continue;
                for (ClassInfo.MethodInfo m : ci.methods) {
                    for (ClassInfo.FieldRef fr : m.fieldRefs) {
                        if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                                || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                                && (pp[0].equals(fr.name) || pp[0].equals(fr.owner + "/" + fr.name)))
                            System.out.println(ci.name + "." + m.name + m.desc + " gets " + fr.owner + "/" + fr.name);
                    }
                }
            }
        } else if ("ddd".equals(mode) && parts.length > 1) {
            String name = parts[1].replace('.', '/');
            ClassInfo ci = idx.get(name);
            if (ci == null) { System.out.println("no such class: " + name); return; }
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (m.desc.indexOf("(DDD") < 0) continue;
                StringBuilder sb = new StringBuilder();
                for (ClassInfo.MethodRef mr : m.methodRefs) sb.append(mr.name).append(mr.desc).append(' ');
                System.out.println(m.name + m.desc + " calls=" + sb);
            }
        } else if ("runrefs".equals(mode) && parts.length > 1) {            String name = parts[1].replace('.', '/');
            ClassInfo ci = idx.get(name);
            if (ci == null) { System.out.println("no such class: " + name); return; }
            java.util.Map<String, Integer> owners = new java.util.TreeMap<String, Integer>();
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"run".equals(m.name) || !"()V".equals(m.desc)) continue;
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if (!mr.owner.startsWith("rustme/")) continue;
                    Integer c = owners.get(mr.owner);
                    owners.put(mr.owner, c == null ? 1 : c + 1);
                }
            }
            System.out.println("run()V refs of " + name + ": " + owners);
            for (String cand : new String[]{"rustme/iliiIilIiI", "rustme/iIiiiIiIiI"}) {
                ClassInfo c2 = idx.get(cand);
                if (c2 == null) { System.out.println(cand + ": MISSING"); continue; }
                System.out.println(cand + " super=" + c2.superName + " fields=" + c2.fields.size()
                        + " methods=" + c2.methods.size());
                for (ClassInfo.MethodInfo m : c2.methods) {
                    if ("<init>".equals(m.name)) System.out.println("  <init>" + m.desc);
                }
            }
        } else if ("ctors".equals(mode) && parts.length > 1) {            String name = parts[1].replace('.', '/');
            ClassInfo ci = idx.get(name);
            if (ci == null) { System.out.println("no such class: " + name); return; }
            System.out.println("class " + ci.name + " super=" + ci.superName);
            for (ClassInfo.MethodInfo m : ci.methods) {
                if ("<init>".equals(m.name)) System.out.println("  <init>" + m.desc);
            }
        } else if ("sr".equals(mode) && parts.length > 1) {            String name = parts[1].replace('.', '/');
            ClassInfo ci = idx.get(name);
            if (ci == null) { System.out.println("no such class: " + name); return; }
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"<init>".equals(m.name)) continue;
                System.out.println("events(" + m.desc + ")=" + m.events);
            }
        } else if ("trav".equals(mode) && parts.length > 1) {            String name = parts[1].replace('.', '/');
            ClassInfo ci = idx.get(name);
            if (ci == null) { System.out.println("no such class: " + name); return; }
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"(FFF)V".equals(m.desc)) continue;
                boolean hasK = false;
                for (Float f : m.constFloats) {
                    if (Math.abs(f - 0.16277136F) < 1e-6F) { hasK = true; break; }
                }
                if (!hasK) continue;
                System.out.println(m.name + m.desc);
                for (ClassInfo.MethodRef mr : m.methodRefs) {
                    if (ci.name.equals(mr.owner) && ("()F".equals(mr.desc) || "(F)V".equals(mr.desc))) {
                        // ищем декларацию вверх по иерархии
                        String decl = null;
                        String flds = "";
                        String cur = ci.name;
                        java.util.Set<String> seen = new java.util.HashSet<String>();
                        while (cur != null && seen.add(cur)) {
                            ClassInfo c2 = idx.get(cur);
                            if (c2 == null) break;
                            for (ClassInfo.MethodInfo mm : c2.methods) {
                                if (mr.name.equals(mm.name) && mr.desc.equals(mm.desc)) {
                                    decl = cur;
                                    StringBuilder sb = new StringBuilder();
                                    for (ClassInfo.FieldRef fr : mm.fieldRefs) {
                                        if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                                                || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                                                && "F".equals(fr.desc))
                                            sb.append(fr.owner).append('/').append(fr.name).append(' ');
                                    }
                                    flds = sb.toString();
                                    break;
                                }
                            }
                            if (decl != null) break;
                            cur = c2.superName;
                        }
                        System.out.println("  calls " + mr.name + mr.desc + " decl=" + decl + " Freads=" + flds);
                    }
                }
                java.util.Set<String> freads = new java.util.HashSet<String>();
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                            || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                            && "F".equals(fr.desc) && ci.name.equals(fr.owner))
                        freads.add(fr.name);
                }
                for (String fld : freads) {
                    int n = 0;
                    String g = null;
                    for (ClassInfo.MethodInfo m2 : ci.methods) {
                        if (!"()F".equals(m2.desc)) continue;
                        for (ClassInfo.FieldRef fr : m2.fieldRefs) {
                            if ((fr.opcode == org.objectweb.asm.Opcodes.GETFIELD
                                    || fr.opcode == org.objectweb.asm.Opcodes.GETSTATIC)
                                    && "F".equals(fr.desc) && ci.name.equals(fr.owner)
                                    && fld.equals(fr.name)) { n++; g = m2.name; }
                        }
                    }
                    int wn = 0;
                    String ws = null;
                    for (ClassInfo.MethodInfo m2 : ci.methods) {
                        if (!"(F)V".equals(m2.desc)) continue;
                        for (ClassInfo.FieldRef fr : m2.fieldRefs) {
                            if ((fr.opcode == org.objectweb.asm.Opcodes.PUTFIELD
                                    || fr.opcode == org.objectweb.asm.Opcodes.PUTSTATIC)
                                    && "F".equals(fr.desc) && ci.name.equals(fr.owner)
                                    && fld.equals(fr.name)) { wn++; ws = m2.name; }
                        }
                    }
                    System.out.println("  F-field " + fld + " readers=" + n + " " + g
                            + " writers=" + wn + " " + ws);
                }
            }
        } else {
            System.out.println("unknown diag: " + diag);
        }
    }
}
