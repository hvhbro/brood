package modules.impl;

import events.EventBus;
import events.EventBus.TickEvent;
import modules.api.Module;
import utils.etc.GameContext;
import utils.etc.Log;

/**
 * NoRecoil: выжигание накопителей отдачи серверного канала gun:recoil.
 *
 * КАНАЛ (дизасм rustme.ilillIiliI.IlIiIIlIil — обработчик в сетевом хендлере):
 *   пакет = float yawKick, float pitchKick, byte mode.
 *   mode==1  -> кик копится в статик-накопителях ilillIiliI.IliiiiIIl/iliiiiIIl,
 *               каждый кадр главный цикл (lIliIiiIiI.llilllIiII @1256 -> ilillIiliI.llIiIIlIil)
 *               переносит порцию (кламп ±15*dt) в yaw/pitch игрока или камеры;
 *   mode==0  -> мгновенно: при активном free-look кик идёт в офсеты камеры
 *               lIillIiliI.illllliIl/lIlllliIl, иначе ПРЯМО в setYaw/setPitch игрока.
 *
 * СТРАТЕГИЯ (INPUT-паттерн, ZNANIA 11): наш цикл 1мс против кадра ~16мс — обнуляем
 * накопители раньше, чем кадр их прочитает. Это легитимная запись ВХОДА: игра только
 * читает накопители, кроме них их пишет только сетевой хендлер.
 * Флоаты фасада жжём ТОЛЬКО при выключенном free-look: при активном free-look SP сам
 * пишет туда ориентацию камеры (мышь) — обнулять = ломать free look. Флаги читаем
 * напрямую из статик-полей iIlllliIl:Z / IIiiiiIIl (геттеры lIlliIlIil/liIliIlIil).
 *
 * НЕ покрывает mode==0 без free-look (прямая запись в игрока в момент пакета) —
 * если лог молчит, а отдача чувствуется, сервер шлёт mode==0: следующий шаг —
 * дельта-компенсация поворота (отдельный механизм).
 *
 * Клавиша N (GLFW 78). Накопители — чисто клиентские статики, сервер не видит разницы.
 */
public final class NoRecoil extends Module {

    public NoRecoil() {
        super("NoRecoil");
        setState(true);
        EventBus.subscribe(TickEvent.class, new EventBus.Listener<TickEvent>() {
            @Override
            public void onEvent(TickEvent event) {
                try {
                    if (isState()) onTick(event);
                } catch (Throwable t) {
                    Log.error("NoRecoil", "onTick exception", t);
                }
            }
        });
        Log.info("NoRecoil", "registered (toggle: menu bind)");
    }

    @Override
    public void onTick(TickEvent event) {
        GameContext ctx = GameContext.get();
        if (ctx.kickYawField == null) {
            // ленивый резолв с троттлингом (сам троттлит до раза в 2с)
            ctx.resolveNoRecoil();
            return;
        }
        try {
            burn();
        } catch (Throwable t) {
            Log.error("NoRecoil", "burn exception", t);
        }
    }

    /** Выжигает накопители отдачи (+ офсеты фасада при выключенном free-look). */
    private void burn() throws Exception {
        GameContext ctx = GameContext.get();

        // 1) накопители gun:recoil (mode==1): game writes -> reads per frame, мы перехватываем между тиками
        float kickYaw = ctx.kickYawField.getFloat(null);
        float kickPitch = ctx.kickPitchField.getFloat(null);
        if (kickYaw != 0f) ctx.kickYawField.setFloat(null, 0f);
        if (kickPitch != 0f) ctx.kickPitchField.setFloat(null, 0f);

        // 2) офсеты фасада: только при выключенном free-look (иначе там живая камера)
        boolean freeLook = ctx.freeLookFlagAField.getBoolean(null)
            || ctx.freeLookFlagBField.getBoolean(null);
        if (!freeLook) {
            float offYaw = ctx.camYawOffsetField.getFloat(null);
            float offPitch = ctx.camPitchOffsetField.getFloat(null);
            if (offYaw != 0f) ctx.camYawOffsetField.setFloat(null, 0f);
            if (offPitch != 0f) ctx.camPitchOffsetField.setFloat(null, 0f);
        }
    }
}
