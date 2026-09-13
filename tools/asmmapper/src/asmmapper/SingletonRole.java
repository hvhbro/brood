package asmmapper;

/** singleton: static ()Lself — инфо-список синглтон-геттеров (всегда на проверку). */
public final class SingletonRole implements Role {
    public String name() { return "singleton"; }

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name) || !ci.name.startsWith("rustme/")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (!m.isStatic) continue;
                if (("()L" + ci.name + ";").equals(m.desc)) {
                    RoleHit h = new RoleHit();
                    h.role = name();
                    h.cls = ci.name;
                    h.auto = false; // синглтонов много — всегда на проверку
                    h.evidence = "static " + m.name + m.desc + " fields=" + ci.fields.size()
                            + " methods=" + ci.methods.size();
                    RoleUtil.addMember(h, ci.name, "method", m.name, m.desc, "instance", "singleton");
                    ctx.hits.add(h);
                    ctx.review.add("singleton:" + ci.name + "." + m.name + " — подтвердить глазами (синглтонов много)");
                    break;
                }
            }
        }
    }
}
