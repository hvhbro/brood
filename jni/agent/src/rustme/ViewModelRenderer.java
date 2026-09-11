package rustme;

import org.lwjglx.opengl.GL11;

/**
 * Подменный ItemRenderer (паттерн CheatIngame): игра хранит инстанс
 * rustme.llilIiiIiI в поле Minecraft.llIlllll (public) и зовёт
 * llIIliliII(player, pitch, swing, hand, swingProgress, stack, equip)
 * для каждого рендера предмета/руки от первого лица.
 *
 * Наш оверрайд оборачивает super в матричные трансформации View model
 * (translate/rotate/scale из модуля ViewModel): наш transform снаружи —
 * применяется ко всему рендеру руки/предмета, pushMatrix/popMatrix не
 * дают утечь в остальной кадр. Когда модуль выключен — прямая делегация
 * без единой лишней GL-операции.
 */
public class ViewModelRenderer extends llilIiiIiI {

    public ViewModelRenderer(iilliIliiI gs) {
        super(gs);
    }

    @Override
    public void llIIliliII(iiIililiiI player, float pitch, float swing,
                           llIIIilIiI hand, float swingProgress,
                           liIIIIIIiI stack, float equipProgress) {
        if (!modules.impl.ViewModel.isTransformActive()) {
            super.llIIliliII(player, pitch, swing, hand, swingProgress, stack, equipProgress);
            return;
        }
        GL11.glPushMatrix();
        try {
            modules.impl.ViewModel.applyTransform();
            super.llIIliliII(player, pitch, swing, hand, swingProgress, stack, equipProgress);
        } finally {
            GL11.glPopMatrix();
        }
    }
}
