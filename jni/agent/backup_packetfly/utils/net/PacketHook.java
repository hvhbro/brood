package utils.net;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import utils.etc.GameContext;
import utils.etc.Log;

/**
 * Netty-перехват ИСХОДЯЩИХ пакетов (PacketFly, фаза 1-2).
 *
 * Путь исходящих (дизасм-проверено):
 *   liIililiiI.iiIlililiI() [onUpdateWalkingPlayer] строит C03-семейство и зовёт
 *   netHandler.llIlIllilI(packet) -> IllIlIIIiI.iIIIlllilI(packet) [NetworkManager]
 *   -> channel.writeAndFlush(packet) -> пайплайн: packet_handler -> encoder -> ...
 *
 * Мы встаём addBefore("encoder", "rustme_out", ...) — outbound идёт от хвоста к голове,
 * значит наш хендлер видит Packet-объект ДО кодирования в байты. Мутируем только
 * C03-семейство (rustme.iliiilIIiI + наследники IIiiilIIiI=C06 / iIiiilIIiI=C04 /
 * lIiiilIIiI=C05 / сам iliiilIIiI=C0B-ground-only), остальное пропускаем как есть.
 *
 * Поля C03-базы (public, дизасм write-методов):
 *   x=IIiIiIilI:D  y=llliiIilI:D  z=liiIiIilI:D
 *   yaw=liiIliilI:F  pitch=iIiIliilI:F
 *   onGround=IiliIiilI:Z  moving=lIIIlllII:Z  rotating=iIlliiilI:Z
 *
 * Фаза 1 (доказательство): лог «intercepted ...» по первому пакету каждого типа
 * + спуф onGround=true (даёт партиклы ходьбы под игроком у наблюдателей и гасит
 * серверный floating-check).
 * Фаза 2: дельты packetDx/Dy/Dz добавляются к x/y/z пакета (ставит модуль PacketFly).
 *
 * Ping-контроль сервера (iIIlilIIiI/IiiiilIIiI) и astraea-каналы НЕ ТРОГАЕМ —
 * они не проходят через мутацию (не C03).
 *
 * Команда (spoofGround/delta) пишется с CheatMain-потока, читается на netty-потоке —
 * всё volatile. Установка идемпотентна, переживает смену мира/сервера (по channel).
 */
public final class PacketHook {

    // --- команда от PacketFly (volatile: кросс-потоково) ---
    public static volatile boolean spoofGround = false;
    public static volatile double packetDx = 0.0;
    public static volatile double packetDy = 0.0;
    public static volatile double packetDz = 0.0;

    private static volatile boolean installed;
    private static Object installedChannel;

    // C03-база и её public-поля (резолвятся один раз)
    private static Class baseC03;
    private static Field fX, fY, fZ, fYaw, fPitch, fGround;
    private static final Set<String> loggedTypes = new HashSet<String>();
    private static long lastErrLog;

    private PacketHook() {}

    public static boolean isInstalled() {
        return installed;
    }

    /** Идемпотентная установка хука. Безопасно звать каждый тик. */
    public static synchronized boolean ensureInstalled(GameContext ctx) {
        try {
            if (installed && ctx.player != null && installedChannel == channelOf(ctx)) {
                return true;
            }
        } catch (Throwable t) {
            installed = false; // канал сменился/умер — переустановим
        }
        return install(ctx);
    }

    private static synchronized boolean install(GameContext ctx) {
        try {
            if (baseC03 == null) {
                baseC03 = ctx.gameLoader.loadClass("rustme.iliiilIIiI");
                fX = baseC03.getDeclaredField("IIiIiIilI");
                fY = baseC03.getDeclaredField("llliiIilI");
                fZ = baseC03.getDeclaredField("liiIiIilI");
                fYaw = baseC03.getDeclaredField("liiIliilI");
                fPitch = baseC03.getDeclaredField("iIiIliilI");
                fGround = baseC03.getDeclaredField("IiliIiilI");
                Field[] all = new Field[] {fX, fY, fZ, fYaw, fPitch, fGround};
                for (int i = 0; i < all.length; i++) all[i].setAccessible(true);
            }

            Object channel = channelOf(ctx);
            if (channel == null) return false;
            if (installed && installedChannel == channel) return true;

            Object pipeline = channel.getClass().getMethod("pipeline").invoke(channel);
            if (pipeline == null) return false;

            Class handlerIface = ctx.gameLoader.loadClass("io.netty.channel.ChannelHandler");
            Class pipelineCls = pipeline.getClass();
            Method addBefore = pipelineCls.getMethod("addBefore",
                String.class, String.class, handlerIface);
            Method addAfter = pipelineCls.getMethod("addAfter",
                String.class, String.class, handlerIface);
            Method addLast = pipelineCls.getMethod("addLast", String.class, handlerIface);
            boolean ok = false;
            // outbound идёт от ХВОСТА к голове: чтобы увидеть Packet-объект ДО encoder'а,
            // встаём сразу ПОСЛЕ packet_handler (ближе всего к хвосту). addBefore("encoder")
            // ставит нас в списке ПЕРЕД encoder (ближе к голове) = ПОСЛЕ него в outbound —
            // там приходят уже ByteBuf'ы, Packet не видим (первый тест это показал).
            try {
                addAfter.invoke(pipeline, "packet_handler", "rustme_out", new Outbound());
                Log.info("PacketHook", "installed: addAfter(packet_handler, rustme_out)");
                ok = true;
            } catch (Throwable t) {
                Log.info("PacketHook", "addAfter(packet_handler) failed: " + t);
            }
            if (!ok) {
                try {
                    addBefore.invoke(pipeline, "packet_handler", "rustme_out", new Outbound());
                    Log.info("PacketHook", "installed: addBefore(packet_handler, rustme_out)");
                    ok = true;
                } catch (Throwable t) {
                    Log.info("PacketHook", "addBefore(packet_handler) failed: " + t);
                }
            }
            if (!ok) {
                addLast.invoke(pipeline, "rustme_out", new Outbound());
                Log.info("PacketHook", "installed: addLast(rustme_out)");
            }
            try {
                List<?> names = (List<?>) pipelineCls.getMethod("names").invoke(pipeline);
                Log.info("PacketHook", "pipeline: " + names);
            } catch (Throwable ignore) {}


            installedChannel = channel;
            installed = true;
            return true;
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastErrLog > 10000L) {
                lastErrLog = now;
                Log.error("PacketHook", "install failed", t);
            }
            return false;
        }
    }

    /** player -> netHandler (lIlilIIl) -> lillIllilI() -> NetworkManager.lilIIllII = Channel. */
    private static Object channelOf(GameContext ctx) throws Exception {
        if (ctx.player == null || ctx.localSpClass == null) return null;
        Field nhField = ctx.localSpClass.getDeclaredField("lIlilIIl");
        nhField.setAccessible(true);
        Object netHandler = nhField.get(ctx.player);
        if (netHandler == null) return null;
        Method getManager = netHandler.getClass().getMethod("lillIllilI");
        Object manager = getManager.invoke(netHandler);
        if (manager == null) return null;
        Field chField = manager.getClass().getDeclaredField("lilIIllII");
        chField.setAccessible(true);
        return chField.get(manager);
    }

    /**
     * Хендлер outbound: мутирует C03, затем ОБЯЗАТЕЛЬНО форвардит дальше
     * (super.write -> реальный адаптер -> ctx.write -> encoder). Любой Throwable
     * гасится логом — netty-поток умирать нельзя.
     */
    private static final class Outbound extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext hctx, Object msg, ChannelPromise promise) {
            try {
                intercept(msg);
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                if (now - lastErrLog > 10000L) {
                    lastErrLog = now;
                    Log.error("PacketHook", "intercept failed", t);
                }
            }
            try {
                super.write(hctx, msg, promise);
            } catch (Throwable t) {
                Log.error("PacketHook", "forward failed", t);
            }
        }
    }

    private static void intercept(Object msg) throws Exception {
        if (baseC03 == null || !baseC03.isInstance(msg)) return;

        String type = msg.getClass().getName();
        boolean first;
        synchronized (loggedTypes) {
            first = loggedTypes.add(type);
        }
        if (first) {
            Log.info("PacketHook", "intercepted " + type
                + " x=" + fX.getDouble(msg) + " y=" + fY.getDouble(msg)
                + " z=" + fZ.getDouble(msg) + " ground=" + fGround.getBoolean(msg));
        }

        if (spoofGround) {
            fGround.setBoolean(msg, true);
        }
        double dx = packetDx, dy = packetDy, dz = packetDz;
        if (dx != 0.0) fX.setDouble(msg, fX.getDouble(msg) + dx);
        if (dy != 0.0) fY.setDouble(msg, fY.getDouble(msg) + dy);
        if (dz != 0.0) fZ.setDouble(msg, fZ.getDouble(msg) + dz);
    }
}
