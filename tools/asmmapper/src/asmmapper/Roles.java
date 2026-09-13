package asmmapper;

import java.util.List;
import java.util.Set;

/**
 * Тонкий оркестратор ролей (способ Антона: метод -> поля, структура от main).
 * Порядок запуска = цепочка зависимостей: travel -> world -> minecraft+gameSettings.
 * Новая роль = новый класс с интерфейсом Role + одна строка в runAll().
 */
public final class Roles {
    private final Ctx ctx;

    /** Совместимость: выхлоп живёт в ctx, поля — те же ссылки. */
    public final List<RoleHit> hits;
    public final List<String> review;

    public Roles(DumpIndex idx, Set<String> live) {
        this.ctx = new Ctx(idx, live);
        this.hits = ctx.hits;
        this.review = ctx.review;
    }

    public RoleHit byRole(String role) { return ctx.byRole(role); }

    public DumpIndex index() { return ctx.index(); }

    public void runAll() {
        Role[] roles = {
            new EntityPosRole(),
            new EntityMotionRole(),
            new ScaledResRole(),
            new TravelRole(),
            new MovementSpeedRole(),
            new WorldRole(),
            new McGsPairRole(),
            new PlayerHierarchyRole(),
            new SprintRole(),
            new MovementInputRole(),
            new NoSlowRole(),
            new FontRendererRole(),
            new RenderItemRole(),
            new EquipSlotRole(),
            new StackRole(),
            new InventoryRole(),
            new FacadeRole(),
            new GuiRole(),
            new KeyBindingRole(),
            new Vec3Role(),
            new RaytraceRole(),
            new PacketRole(),
            new NetRole(),
            new AttackBusRole(),
            new AttackEventRole(),
            new TablistRole(),
            new UsingItemRole(),
            new WorldTimeRole(),
            new ResourceRole(),
            new TextureRole(),
            new OverlayRegRole(),
            new IconRole(),
            new KillRole(),
            new GunItemRole(),
            new WindowRole(),
            new StrafeModRole(),
            new SingletonRole(),
        };
        for (Role r : roles) r.detect(ctx);
    }
}
