package asmmapper;

/**
 * Одна роль по способу Антона: ищет МЕТОД по поведению, из метода тащит ПОЛЯ.
 * Новая роль = новый класс с этим интерфейсом + одна строка в Roles.runAll().
 */
public interface Role {
    /** Имя роли (ключ в classmap.json). */
    String name();

    /** Поиск на одном дампе (old не нужен), только по живым классам ctx. */
    void detect(Ctx ctx);
}
