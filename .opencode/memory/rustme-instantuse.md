---
name: rustme-instantuse
description: InstantUse 09-10 — ИТОГ: duration-форс (обнуление duration как у чита) ОТВЕРГНУТ тестом (zeroing работал — лог forced 0 was 4000000000 — но сервер не принял ранний lguse); по явной просьбе юзера вернута shuse-версия (edge-detect getKeyAction → сразу shuse, anti-spam 300мс) — юзер считает её рабочей; «lguse» в дампе чита 0 вхождений; shuse ВСЁ-ТАКИ шлётся игрой — старая заметка ошибочна; ждёт теста
metadata:
  type: project
---

**СТАТУС 09-10 14:52: duration-форс ПРОТЕСТИРОВАН И ОТКЛОНЁН.** Форс технически срабатывал (лог юзера: `[InstantUse] longUse duration forced 0 (was 4000000000)` — uphint реально кладёт longUse{4s}, kotlin Duration в нс), но юзер не увидел мгновенного использования → сервер НЕ принимает lguse сильно раньше duration (или корутина/стейт сложнее — доп. реверс не окуплен). По явной просьбе юзера («верни до фикса то что открывается интерфейс») вернули shuse-версию: edge-detect getKeyAction (use-клавиша, ПКМ по умолчанию) → сразу shuse (PerformShortUseActionPacketData через helper liliiilliI.IlIIIIIIIl) → сервер мгновенно выполняет короткое использование / открывает UI. anti-spam 300мс. Юзер считает shuse-версию РАБОЧЕЙ — старое «shuse запрещён» больше не действует. Был реализованный duration-форс: OverlayListener.liiiilIliI static IliIIilll → isInstance(IilIililiI) → IillIiIIl → data.longUse → duration.setLong(lu,0) (код в истории чата, не в файлах).

**ПОЛНАЯ ЦЕПЬ ИГРЫ (CFR-декомп 09-10, всё проверено):**
- **OverlayListener** = `rustme.liiiilIliI` (ru.rustme.ui.listener.OverlayListener, Kotlin object). СТАТИК-поля: `IliIIilll` = List\<OverlayUi\> ЖИВЫХ оверлеев (заполняется в ctor синглтона из фабрик), `lliIIilll` = фабрики, `iiIIIilll` = синглтон. HintOverlay (IilIililiI) лежит в списке ПОСТОЯННО (прячется анимацией, не удаляется).
- **uphint** (`rust:misc:uphint` → UpdateHintPacketData{data: HintOverlayData?}) → HintOverlay.iiIIillIil(data) = только СКЛАДЫВАЕТ data в поле `IillIiIIl` (currentData) и гонит анимации. КОРУТИНУ НЕ ЗАПУСКАЕТ.
- **Корутина lIIIililiI (onUseKeyPress$1)** стартует по нажатию use-клавиши (= getKeybindingSettings().getKeyAction(), у юзера ПКМ): delay(startDelay ≤250мс, companion-константа) → data=IillIiIIl; **null → тихий return (uphint не успел — use срывается, как и в ваниле)**; longUse==null → lguse СРАЗУ; longUse!=null → анимация progressValue→1.0 ЗА duration → delay(duration) → hintLongUseActive=false → **lguse**.
- **lguse-пакет ПУСТОЙ** (PerformLongUseActionPacketData — ctor без полей). Контент не при чём — только МОМЕНТ и серверный стейт.
- **ГЕЙТ sendLongUseActionPacket (iliIillIil):** if (gs.IiliiiIl != null) return — при открытом экране (включая наше меню RSHIFT) игра lguse НЕ шлёт.
- **ИСПРАВЛЕНИЕ старой заметки: shuse ШЛЁТСЯ игрой** — HintOverlay.llIIillIil() зовёт helper с PerformShortUseActionPacketData (из startAwait при !hasManyOptions && longUse==null и на key-release при progressValue==0). Раньше писали «отправителей нет» — CP-скан не увидел, потому что канал лежит в статик-холдере ilIilIlIII (iliilIilll=shuse, IIiilIilll=lguse), а send-методы ссылаются на ПОЛЕ, не на литерал.
- Сериализация payload: int/строки=varint, long=8б LE, float=4б LE, nullable=presence-байт, enum=readInt 4б ([[rustme-network-c2s]]).

**КАК РАБОТАЕТ ЧИТ (chtdump, словарь резолвера в .rdata + 13 плейнтекст-имен классов):**
- Ключи: `instantuse.HintManager/HintOverlay/overlayListStaticField/overlayDataField/HintOverlayData/HintOverlayLongUseData/longUseField/durationField`.
- Маппинг: HintManager=liiiilIliI (имя `rustme.liiiilIliI` лежит в дампе чита ПЛАЙНТЕКСТОМ), overlayListStaticField=статик `IliIIilll`, overlayDataField=`IillIiIIl`, longUseField=HintOverlayData.longUse, durationField=HintOverlayLongUseData.duration; HintOverlayData/LongUseData — named-классы (loadClass по имени).
- **В_dump чита СТРОКИ «lguse»/«rust:misc:*» РАВНО 0 ВХОЖДЕНИЙ** во всех секциях → чит не может слать payload-пакет (канала не знает). Его network.* ключи = ванильные C03-хуки. → Единственный механизм: каждый тик читать overlay-список, и если data.longUse!=null и duration!=0 — **писать duration=0** → корутина игры делает delay(0) и шлёт lguse сама (правильный момент, правильный стейт, правильные гейты).
- Это «писать ВХОД» по ZNANIA 11: reflection-запись final instance long в Java 8 валидна (setAccessible+setLong).

**РАБОЧИЙ ПЛАН (ждёт «делай»):** переписать InstantUse: резолв {liiiilIliI.IliIIilll static; IilIililiI.IillIiIIl; named HintOverlayData.longUse; HintOverlayLongUseData.duration}; в тике: перебор снапшота списка → isInstance(IilIililiI) → data=IillIiIIl; data!=null → lu=data.longUse; lu!=null && durF.getLong(lu)!=0 → durF.setLong(lu,0)+лог один раз на объект (guard по identity, uphint кладёт НОВЫЙ объект на каждое использование — перезапись сама обновится). Никаких сетевых хуков/подписок не нужно. Альтернатива (не выбрана): подписка на uphint + свой lguse — стейт сервера тогда активен, но игра-стейт рассинхронен и путь не подтверждён тестом чита.

**Проваленные подходы (НЕ повторять):** (1) v1: lguse на using true→false; (2) гейты по флагам прицела/usingItem — для hint-предметов vanilla usingItem не ставится (GameContext: «еда/лук; ганы могут не ставить»); (3) v2: lguse сразу при using + ретрай 300мс; (4) duration-форс (см. выше) — zeroing работал, сервер не принял. shuse-версия — ПО ТОМУ, ЧТО ЮЗЕР СЧИТАЕТ ЕЁ РАБОЧЕЙ (открывала интерфейс) — вернута по явной просьбе; нюанс: для НЕинтерактивных предметов shuse = просто короткое использование.

Связано: [[rustme-chtdump-competitor]], [[rustme-network-c2s]], [[rustme-ads-slowdown]], [[rustme-next-features]], [[rustme-phase-gating]].
