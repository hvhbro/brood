package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** scaledRes: ванильный scale-цикл в <init> (дивиденд, scale, 1, 320|240). */
public final class ScaledResRole implements Role {
    public String name() { return "scaledRes"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        List<String> candCls = new ArrayList<String>();
        Map<String, String> candEv = new HashMap<String, String>();
        Map<String, Map<String, String[]>> candMap = new HashMap<String, Map<String, String[]>>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            Map<String, Integer> writes = new HashMap<String, Integer>();
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"<init>".equals(m.name)) continue;
                for (ClassInfo.FieldRef fr : m.fieldRefs) {
                    if ((fr.opcode == org.objectweb.asm.Opcodes.PUTFIELD
                            || fr.opcode == org.objectweb.asm.Opcodes.PUTSTATIC)
                            && "I".equals(fr.desc) && ci.name.equals(fr.owner)) {
                        Integer c = writes.get(fr.name);
                        writes.put(fr.name, c == null ? 1 : c + 1);
                    }
                }
            }
            String scaleWritesTop = null;
            int scaleWritesMax = 0;
            for (Map.Entry<String, Integer> e : writes.entrySet()) {
                if (e.getValue().intValue() > scaleWritesMax) {
                    scaleWritesMax = e.getValue().intValue();
                    scaleWritesTop = e.getKey();
                }
            }
            // 320/240 по упорядоченным событиям. Ванильный scale-цикл:
            //   getfield DIVIDEND, getfield SCALE, const 1, const 320|240
            // (арифметика в событиях невидима) => дивиденд = G на i-3,
            // делитель-scale = G на i-2 (общий для 320 и 240).
            String width = null;
            String height = null;
            String scaleByConst = null;
            boolean scaleOk = true;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!"<init>".equals(m.name)) continue;
                List<String> ev = m.events;
                for (int i = 3; i < ev.size(); i++) {
                    String e = ev.get(i);
                    if (!e.equals("C:320") && !e.equals("C:240")) continue;
                    if (!ev.get(i - 1).startsWith("C:")) continue;
                    String g2 = ev.get(i - 2);
                    String g1 = ev.get(i - 3);
                    if (!g2.startsWith("G:") || !g1.startsWith("G:")) continue;
                    String div = g1.substring(2);
                    div = div.substring(div.lastIndexOf('/') + 1);
                    String scl = g2.substring(2);
                    scl = scl.substring(scl.lastIndexOf('/') + 1);
                    if (scaleByConst == null) scaleByConst = scl;
                    else if (!scaleByConst.equals(scl)) scaleOk = false;
                    if (e.equals("C:320") && width == null) width = div;
                    if (e.equals("C:240") && height == null) height = div;
                }
            }
            if (width == null || height == null || width.equals(height)
                    || !scaleOk || scaleByConst == null) continue;
            if (width.equals(scaleByConst) || height.equals(scaleByConst)) continue;
            // scale = делитель из константной привязки; corroboration: максимум записей
            String scale = scaleByConst;
            if (!scale.equals(scaleWritesTop)) continue;
            String gs = RoleUtil.singleIReader(ci, scale);
            String gw = RoleUtil.singleIReader(ci, width);
            String gh = RoleUtil.singleIReader(ci, height);
            if (gs == null || gw == null || gh == null) continue;
            Map<String, String[]> map = new HashMap<String, String[]>();
            map.put("SCALE", new String[]{scale, gs});
            map.put("WIDTH", new String[]{width, gw});
            map.put("HEIGHT", new String[]{height, gh});
            candCls.add(ci.name);
            candEv.put(ci.name, "scale=" + scale + " w=" + width + "(320) h=" + height + "(240)");
            candMap.put(ci.name, map);
        }
        if (candCls.size() == 1) {
            String cls = candCls.get(0);
            RoleHit h = new RoleHit();
            h.role = name();
            h.cls = cls;
            h.auto = true;
            h.evidence = candEv.get(cls);
            Map<String, String[]> map = candMap.get(cls);
            for (String r : new String[]{"SCALE", "WIDTH", "HEIGHT"}) {
                RoleUtil.addMember(h, cls, "field", map.get(r)[0], "I", r + ".field", "scaledres");
                RoleUtil.addMember(h, cls, "method", map.get(r)[1], "()I", r + ".getter", "scaledres");
            }
            ctx.hits.add(h);
        } else {
            ctx.review.add("scaledRes: кандидатов=" + candCls.size() + " " + candCls + " (нужен 1)");
        }
    }
}
