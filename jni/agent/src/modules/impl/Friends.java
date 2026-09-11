package modules.impl;

import modules.api.Module;
import utils.etc.Log;

/**
 * Friends — вкладка-менеджер списка друзей (не боевой модуль: toggle
 * заблокирован, в HUD не показывается). Панель вкладки рисует
 * CheatMenuScreen кастомно (поле ввода + список): см. drawFriendsPanel.
 * Эффекты списка — в utils.etc.Friends: аим-скип, dormant-скип, зелёный ESP.
 */
public final class Friends extends Module {

    public Friends() {
        super("Friends", "Friends");
        Log.info("Friends", "tab registered");
    }

    /** Менеджер не тогглится (клик по строке не должен зажигать HUD-плашку). */
    @Override
    public void toggle() {
        // no-op: вкладка всегда доступна, состояние не используется
    }
}
