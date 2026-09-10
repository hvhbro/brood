package io.netty.channel;

/**
 * Стаб для компиляции агента (реальный класс приходит из classpath игры).
 * Сигнатура write() должна ТОЧНО совпадать с netty 4.1:
 *   public void write(ChannelHandlerContext, Object, ChannelPromise) throws Exception
 * В рантайме наш хендлер наследует РЕАЛЬНЫЙ адаптер — super.write форвардит пакет
 * дальше по пайплайну (в encoder).
 */
public class ChannelOutboundHandlerAdapter implements ChannelHandler {

    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    }

    public void flush(ChannelHandlerContext ctx) throws Exception {
    }
}
