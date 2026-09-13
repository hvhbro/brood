package io.netty.channel;

/**
 * Стаб для компиляции агента (реальный класс приходит из classpath игры).
 * Тип параметра write() в ChannelOutboundHandlerAdapter.
 */
public interface ChannelHandlerContext {
    /** Дальше по outbound-цепочке (к энкодеру). Сигнатура 1:1 netty 4.1. */
    ChannelFuture write(Object msg, ChannelPromise promise);
}
