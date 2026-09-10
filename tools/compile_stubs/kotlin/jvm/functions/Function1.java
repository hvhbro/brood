package kotlin.jvm.functions;

/** Стаб для компиляции агента (реальный класс из kotlin-стдб игры). */
public interface Function1<P1, R> {
    R invoke(P1 p1);
}
