package asmmapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * movePackets: кастомный протокол движения форка (ванильного C03 нет!).
 * ru.rustme.network.movement.* — стабильные имена и геттеры
 * (MoveInput: strafe/forward/yaw/pitch/pos/sequence...).
 * Маппим отправителя: метод с параметром MoveInputPacketData (путь
 * подмены ввода для AimBot/Strafe) + источник его инстанса.
 */
public final class PacketRole implements Role {
    public String name() { return "movePackets"; }

    static final String MOVE_PKG = "ru/rustme/network/movement/";
    static final String INPUT = MOVE_PKG + "MoveInputPacketData";
    static final String IMPULSE = MOVE_PKG + "MoveImpulsePacketData";
    static final String STATE = MOVE_PKG + "MoveStateData";

    public void detect(Ctx ctx) {
        DumpIndex idx = ctx.index();
        if (idx.get(INPUT) == null || idx.get(IMPULSE) == null || idx.get(STATE) == null) {
            ctx.review.add("movePackets: нет стабильных Move*PacketData в дампе");
            return;
        }
        ClassInfo inputCi = idx.get(INPUT);
        List<String> ifaces = new ArrayList<String>();
        if (inputCi != null) {
            for (String itf : inputCi.interfaces) {
                if (itf.contains("PayloadPacketData") || itf.contains("MoveFullOutcome")
                        || itf.contains("PacketData")) ifaces.add(itf);
            }
        }
        // отправитель: метод, создающий (NEW) MoveInputPacketData.
        // Это тик движения (берёт localPlayer) — путь подмены ввода.
        // Сериализатор самого пакета (receive-путь) не в счёт.
        List<String> senders = new ArrayList<String>();
        List<String> anyMention = new ArrayList<String>();
        for (ClassInfo ci : idx.all().values()) {
            if (!ctx.isLive(ci.name)) continue;
            if (ci.name.equals(INPUT) || ci.name.startsWith(INPUT + "$")) continue;
            for (ClassInfo.MethodInfo m : ci.methods) {
                if (m.news.contains(INPUT)) {
                    senders.add(ci.name + "." + m.name + m.desc);
                }
            }
        }
        RoleHit h = new RoleHit();
        h.role = name();
        h.cls = INPUT;
        h.auto = true;
        h.evidence = "стабильный протокол + отправители=" + senders;
        RoleUtil.addMember(h, INPUT, "class", "MoveInputPacketData",
                "L" + INPUT + ";", "moveInput", "stable-name");
        RoleUtil.addMember(h, IMPULSE, "class", "MoveImpulsePacketData",
                "L" + IMPULSE + ";", "moveImpulse", "stable-name");
        RoleUtil.addMember(h, STATE, "class", "MoveStateData",
                "L" + STATE + ";", "moveState", "stable-name");
        if (senders.size() == 1) {
            String s = senders.get(0);
            String oc = s.substring(0, s.indexOf('.'));
            String rest = s.substring(s.indexOf('.') + 1);
            RoleUtil.addMember(h, oc, "method", rest.substring(0, rest.indexOf('(')),
                    rest.substring(rest.indexOf('(')), "sendMoveInput", "news-input");
        } else {
            h.auto = false;
            ctx.review.add("movePackets: отправителей=" + senders.size() + " " + senders);
        }
        ctx.hits.add(h);
    }

    private static String shortName(String internal) {
        int i = internal.lastIndexOf('/');
        return i >= 0 ? internal.substring(i + 1) : internal;
    }
}
