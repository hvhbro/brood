---
name: rustme-norecoil
description: NoRecoil РАБОТАЕТ (09-08, клавиша N, подтверждено юзером на Песочнице
  — отдача жжётся) — отдача приходит S2C-каналом gun:recoil (float yaw, float
  pitch, byte mode); модуль жжёт статик-накопители ilillIiliI каждый 1мс;
  mode==0-прямой путь НЕ покрыт (дельта-компенсация — следующий шаг); burn-логи
  удалены при чистке диагностики 22:38 (по логу путь больше не виден)
metadata:
  node_type: memory
  type: project
  originSessionId: sess_263b8b84-091f-4070-acbf-d5e0e3bdbf5c
---

**NoRecoil — реализован 09-08** (юзер выбрал вариант «выжигание накопителей»). Модуль `jni/agent/src/modules/impl/NoRecoil.java` (toggle N=GLFW 78, стартует ON, анонимный Listener). Поля — ленивый `GameContext.resolveNoRecoil()` (троттлинг 2с, поля kickYawField/kickPitchField/camYawOffsetField/camPitchOffsetField/freeLookFlagAField/freeLookFlagBField). Регистрация в RustClient. Сборка: 31 класс, DLL 2.1МБ, round-trip OK, java/lang/invoke=0.

**Полный путь отдачи (дизасм, проверено):**
- Канал **S2C `gun:recoil`**: диспетчер пакетов `iliilIliiI.IlliillliI(IlIIilIIiI)` (lookupswitch по имени канала) → синглтон `ilillIiliI.lliiiiIIl.IlIiIIlIil(packet)`. Формат: float yawKick, float pitchKick, byte mode.
- **mode==1**: кик копится в статик-накопителях `ilillIiliI.IliiiiIIl:F` (yaw) / `iliiiiIIl:F` (pitch). Дрейн по КАДРУ: `lIliIiiIiI.llilllIiII` @1256 → `ilillIiliI.llIiIIlIil()`: порция = kick, кламп ±15·dt (dt из getFpsLimit настроек мода), добавляется к yaw/pitch игрока или фасада; статик уменьшается на порцию.
- **mode==0**: мгновенно. При активном free-look (статик-флаги `lIillIiliI.iIlllliIl:Z` / `IIiiiiIIl:Z`) кик идёт в офсеты камеры `lIillIiliI.illllliIl:F` (yaw) / `lIlllliIl:F` (pitch). БЕЗ free-look — ПРЯМО в игрока: `liIililiiI.lIIIlIlIII(yaw+kickYaw)` / `.illiIIlIII(pitch+kickPitch)` (сеттеры yaw/pitch из ZNANIA 14.1).
- Канал `gun:recoil:animation` → `llIIlIiliI.iIIilliIl.IlIiiIlIil(F)` — анимация МОДЕЛИ ОРУЖИЯ в руках (viewmodel `lliIlIiliI` вращает от анимируемой величины), НЕ камера.

**Стратегия (INPUT-паттерн, ZNANIA 11):** наш цикл 1мс против кадра ~16мс — обнуляем накопители раньше, чем кадр прочитает. Игра накопители только ЧИТАЕТ (пишет лишь сетевой хендлер) — самое безопасное место записи из всех найденных.

**КРИТИЧНАЯ ГРАБЛЯ free-look:** флоаты фасада `illllliIl/lIlllliIl` при активном free-look — ЖИВАЯ камера (SP сам туда пишет при вращении мыши: liIililiiI ссылается на фасад; `llilIiiIiI.liIIliliII(F)` каждый кадр вызывает `iliiIIlIil()` и читает оба флага). Жечь всегда = сломать free look. Поэтому офсеты фасада обнуляются ТОЛЬКО когда оба флага false.

**Диагностика:** burn-логи (`burned smooth recoil` / `burned facade offsets`) УДАЛЕНЫ при чистке диагностики 09-08 22:38 — они спамили на каждый выстрел. Юзер подтвердил работу: отдача жжётся (в логе сессии были и smooth-ветка, и facade-ветка → mode==1 и mode==0+free-look покрыты). Функциональность не тронута: чистые `if (x != 0) set(0)`. Если отдача когда-нибудь снова «пробьёт» — единственный путь mode==0 без free-look (прямая запись в игрока в момент пакета, выжиганием не берётся) → следующий шаг: дельта-компенсация поворота (кик = большой одномоментный скачок pitch/yaw vs плавные мышиные дельты; порог + квантование).

Карта статиков фасада `lIillIiliI` (пригодится): illllliIl=yaw-офсет (геттер liIiIIlIil, сеттер IlIliIlIil(F)), lIlllliIl=pitch-офсет (геттер llIliIlIil, сеттер iIlliIlIil(F)), вторая группа флоатов IIlllliIl/iiiiiiIIl/iIiiiiIIl/IllllliIl; `reset()` применяет illllliIl→setYaw, lIlllliIl→setPitch, iiiiiiIIl→SP.lIlIiIliI/IlIliIliI и зануляет iIiiiiIIl+IIlllliIl. Free-look тогглится с сервера (FreeLookTogglePacketData → lIIliIlIil).

Связано: [[rustme-next-features]], [[rustme-ads-slowdown]], [[rustme-network-c2s]].
