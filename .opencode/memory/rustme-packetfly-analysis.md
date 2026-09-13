---
name: rustme-packetfly-analysis
description: PacketFly-анализ (09-08) — C03-семейство пакетов, конвейер
  отправки, ping-контроль, план реализации через netty-хук
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e956625e-4cc0-4829-b444-3cbb0dcab14f
---

Анализ сети для PacketFly (2026-09-08, дизасм-проверено, javap-дампы в tools/javap_sp_full.txt, javap_nethandler.txt, javap_netmgr.txt, javap_packet_enum.txt). Связано с [[rustme-network-c2s]].

**onUpdateWalkingPlayer = `liIililiiI.iiIlililiI()V`** (вызывается в конце `iIllililiI(int)` = onUpdate, ветка !riding; при riding вместо неё C05+iiiiilIIiI(FFZZ)=C0B-аналог). Логика точная копия ванильной 1.12.2:
- sneak сменился → `IllllIIIiI(entity, illllIIIiI-enum)` = CPacketEntityAction (enum-значения: iililllII=sneak ON, IililllII=sneak OFF, IIIilllII=sprint ON, lililllII=sprint OFF, llIilllII=START_FALL_FLYING шлётся при падении с элитрой)
- sprint сменился → то же
- moved/rotated флаги: d²>9.0E-4 или positionUpdateTicks>=20 → moving; yaw/pitch delta ≠0 → rotating
- riding+moving → C06 с y=-999 и motionX/Z в x/z; moving+rotating → `IIiiilIIiI(x, bb.minY, z, yaw, pitch, onGround)`; moving → `iIiiilIIiI(x, bb.minY, z, onGround)`; rotating → `lIiiilIIiI(yaw, pitch, onGround)`; только onGround сменился → `iliiilIIiI(onGround)`
- lastReported-поля SP: lliliiil:D=lastX, lIlIiiil:D=lastY, Ililiiil:D=lastZ, Iiiliiil:F=lastYaw, iIIliiil:F=lastPitch, lllIiiil:I=positionUpdateTicks, IIiliiil:Z=lastOnGround, lilIiiil:Z=lastSneak, lIIliiil:Z=lastSprint

**C03-семейство (базовый класс `rustme/iliiilIIiI`, поля public!)**: IIiIiIilI:D=x, llliiIilI:D=y, liiIiIilI:D=z, liiIliilI:F=yaw, iIiIliilI:F=pitch, IiliIiilI:Z=onGround (wire: writeByte), lIIIlllII:Z=moving, iIlliiilI:Z=rotating. Классы: IIiiilIIiI=C06 PosRot(DDDFFZ), iIiiilIIiI=C04 Pos(DDDZ), lIiiilIIiI=C05 Rot(FFZ), iliiilIIiI=база(Z). Wire: x,y,z,yaw,pitch,onGround(byte) — ванильный 1.12.2.

**Конвейер отправки**: `iliilIliiI.llIlIllilI(packet)` → `IllIlIIIiI.iIIIlllilI(packet)` (NetworkManager; `lillIllilI()` — геттер) → канал открыт? `liillllilI`: `iIIilIIIiI.IliiIllilI(packet)` (протокол-enum → BiMap<Integer,Class> ставит packet id) + `channel.writeAndFlush` (в eventLoop) : очередь illIlIIIiI под ReentrantReadWriteLock до channelActive. Пайплайн ванильный: имена "decoder"/"encoder" (видны в ilIIlllilI — addBefore шифровальных хендлеров).

**S08-аналог ОТСУТСТВУЕТ**: `iIIlilIIiI` (S2C) содержит ТОЛЬКО long id = ping-контроль сервера; обработчик `IilllIlliI` МГНОВЕННО отвечает `new IiiiilIIiI(packet.getId())` (C2S, тоже только long) — НЕ ТРОГАТЬ/НЕ ГАСИТЬ, иначе рассинхрон/кик. Серверная коррекция позиции идёт через entity-телепорт: `llIllIlliI(IlIiIlIIiI)` → поиск сущности по id (может быть и сам игрок) → `IIlIIliIiI.lIiiIIiIil(DDDFFIZ)` (setPositionAndRotation2). Также сервер шлёт capabilities-пакет = сам класс `lillilIIiI` (PlayerCapabilities: llllIiilI=flying, iiiiliilI=walkSpeed, iIiiliilI=flySpeed; read: байт флагов + 2 float).

**astraea-каналы = только касты**: CustomPayload-обработчик (диспетчер case0-9) astraea-init/astraea: входящий → `ru.rustme.NativeAPI.handlePacket(nick, byte[])` → ответ уходит каналом astraea-response. Исходящие C03 через NativeAPI НЕ идут. (Case 3 = gun:recoil, case 6 = MC|StopSound и пр. — vanilla-каналы в том же диспетчере.)

**Партиклы «ходьбы» у наблюдателей (наблюдение юзера о клиенте друга)**: частицы бега/ходьбы спавнит КЛИЕНТ наблюдателя в travel() для чужой сущности из её onGround+горизонтальной скорости; onGround приходит с сервера из НАШЕГО C03. Спуф onGround=true в пакете при полёте → у других «пыль ходьбы» в воздухе. Медленный подъём = motionY-запись между тиками (механика доказана JumpTest, [[rustme-jni-dll]] ZNANIA 11.1): travel вычитает гравитацию 0.08 из записанного значения.

**План реализации PacketFly (ждёт «делай»)** → **РЕАЛИЗОВАН → ТЕСТ ПРОВАЛЕН (09-08) → ОТЛОЖЕН**:
- **СТАТУС (финал 09-08): юзера ФЛАГАЕТ сервер → код выведен из боевой сборки СОГЛАСОВАННО с юзером** («мы сейчас вручную списки делаем?» — модульный состав payload юзер правит руками, удаляя исходники из jni/agent/src): `jni/agent/backup_packetfly/` (PacketHook.java + PacketFly.java + фиксы внутри — НЕ компилятся из src), регистрация в RustClient удалена, чистая DLL пересобрана (40 классов, без Packet*/нетти-хука). Нетти-стабы io/netty/channel/* ОСТАВЛЕНЫ в game_stubs.jar.
- Что успели проверить в живом тесте юзера (первая сборка): хук установился — «installed: addBefore(encoder, rustme_out)», пайплайн=[timeout, decrypt, splitter, decompress, decoder, encrypt, prepender, compress, rustme_out, encoder, packet_handler, Tail#0]; «intercepted» НИ РАЗУ НЕ ПОЯВИЛОСЬ. Фикс позиции хука на addAfter("packet_handler") сделан.
- **ЛОГ ВТОРОЙ (addAfter) СБОРКИ ПРИСЛАН 09-08 — ПЕРЕХВАТ РАБОТАЕТ**: «installed: addAfter(packet_handler, rustme_out)», pipeline=[timeout, decrypt, splitter, decompress, decoder, encrypt, prepender, compress, encoder, packet_handler, rustme_out, Tail#0], «intercepted» ловил C03-семейство с живыми координатами (iIiiilIIiI=C04 DDD, IIiiilIIiI=C06, iliiilIIiI/lIiiilIIiI нулевые при старте=base/Rot), «auto-dip started (anti floating-check)» запускался. Кика в этом куске лога НЕТ (юзер быстро тогглил V туда-сюда). После теста юзер ВЫВЕЛ PacketFly/PacketHook из src в jni/agent/backup_packetfly и подтвердил намеренность («все норм так и надо») — payload без Packet* (40 классов).
- Автонырок (anti floating-check 80 тиков dY≥−0.03125) тоже в бэкапе: каждые 3с окно 250мс с motionY=0 (net −0.08/тик).
- Гипотезы, почему флагало (для следующего раза, по порядку проверки): (1) тест шёл на старой сборке — перехват не работал вовсе (лог без addAfter/intercepted = так и было); (2) spoofGround=true сам по себе флагается (onGround без контакта); (3) серверная скорость-валидация строже vanilla (8 бл/с слишком много → снижать MOVE_SCALE до 0.2-0.28); (4) вертикаль net +0.08/тик в чистом виде палится (нужен elytra-спуф бита 7 dataWatcher'а).
- `utils/net/PacketHook.java` — outbound netty-хендлер «rustme_out» (ChannelOutboundHandlerAdapter, стаб в game_stubs.jar: io/netty/channel/{ChannelHandler,ChannelHandlerContext,ChannelPromise,ChannelOutboundHandlerAdapter} — jar лаунчера под MD5-хешами, распаковке не поддаётся, стабы сигнатурно совпадают с netty 4.1). install: player.lIlilIIl → lillIllilI() → поле lilIIllII = Channel → pipeline().addBefore("encoder","rustme_out",...) (fallback addLast). Идемпотентен, отслеживает смену канала (installedChannel). intercept: только C03-семейство (isInstance baseC03) — лог первого пакета каждого типа, спуф onGround (volatile spoofGround), дельты packetDx/Dy/Dz (volatile). super.write ОБЯЗАТЕЛЬНО вызывается (forward).
- `modules/impl/PacketFly.java` — toggle V (86), старт OFF. Логика: движение ЛОКАЛЬНО через motion-поля (пакет сам уносит позицию, iiIlililiI читает геттеры), поэтому packetD*=0. Вертикаль: jump→motionY=0.16 (net +0.08), sneak→0 (net -0.08), покой→0.08 (парение). Горизонталь: ванильная moveRelative от yaw (геттер IIiIillIII) с MOVE_SCALE=0.4, клампы 0.5/0.6. jump-поле инпута = iiIiIIiII:Z (дизасм SP @598/@849: ветки плавания 6.0f и fly-toggle); sneak через isSneaking. spoofGround=true → партиклы ходьбы.
- Регистрация в RustClient после Svo. Сборка ОК (45 классов), java/lang/invoke отсутствует. НЕ ИНЖЕКТИТЬ пока rustme.exe запущен (сборка шла при живой игре — LNK не мешал, DLL только пишется в build).
- Риски живого теста: серверная валидность скорости; при кике — снижать MOVE_SCALE, пробовать пакетные дельты вместо локального движения; «резиновость» = сервер отклоняет пакеты.
- **КРИТИЧНО (декодирование сервера, [[rustme-movement-exploits]])**: floating-check сервера — 80 тиков подряд dY≥−0.03125 при !mayfly → КИК. Наш спуск sneak (net dY=−0.08 < −0.03125) СБРАСЫВАЕТ счётчик — ок; но ЧИСТОЕ парение (net dY=0.00) накопит 80 тиков (~4с) и кикнет. MUST для живого теста: каждые ≤79 тиков делать «нырок» (тик sneak: motionY=0 → dY≈−0.08) ИЛИ спуф elytra-бита 7 dataWatcher'а игрока (ветка elytra в floating-check это обходит). Горизонталь 8 бл/с безопасна (порог moved-too-quickly = 100 бл/с).

ГАТЧ: javap на NTFS — классы, differing only case (IIiiilIIiI/iIiiilIIiI, IllllIIIiI/illllIIIiI), надо копировать в ОТДЕЛЬНЫЕ каталоги, иначе второй перезаписывает первого.
