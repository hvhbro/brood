---
name: rustme-svo-skins
description: SVO реализован и работает (09-08, клавиша H): подмена скинов всех игроков на putin.png (вшит байтами через AssetData); цепочка TextureManager→RL svo:putin→перезапись SKIN в снапшотах; гатч InstantiationException на интерфейсе iiIiliiIiI
metadata:
  type: project
---

**SVO (скины) — РЕАЛИЗОВАН И ПРОТЕСТИРОВАН ЮЗЕРОМ 09-08 («отлично теперь все работает»).** Клавиша H (GLFW 72), выключен по умолчанию. Все скины всех игроков → putin.png, при выключении откат к исходным.

**Архитектура (все точки дизасм-проверены):**
- `rustme.SvoTexture` (пакет rustme, как CheatIngame) — реализует игровой интерфейс `iiIiliiIiI` (TextureObject: iIlIllliil(ResourceManager)=loadTexture, iliiIlliil()=glId). PNG из `utils.etc.AssetData.get("putin")` → ImageIO → RGBA ByteBuffer → GL11/glTexImage2D через lwjglx shim, GL_NEAREST/CLAMP. Upload идемпотентен (reloadResources зовёт loadTexture повторно).
- PNG вшит побайтно: pack_agent.py `generate_asset_data()` регенерирует `utils/etc/AssetData.java` из `jni/agent/src/assets/*.png` (Base64-чанки по 60000, тот же паттерн что FontData; добавить картинку = строка в ASSETS_TO_EMBED + ветка в get()). putin.png 2994 байта, byte-MATCH проверен.
- `modules/impl/Svo.java`: резолв — RL("svo","putin") ctor(String,String), TextureManager = `gs.IIiIiilliI()` (lIliliiIiI), регистрация `lliIilliII(rl, texObj)Z` (САМ зовёт texture.loadTexture — GL на главном потоке автоматически), ключ SKIN = `Enum.valueOf(MinecraftProfileTexture$Type, "SKIN")` (authlib загружен в рантайме), карты снапшотов `iiIilIliiI.illllIIl:Map` (public), хендлер = `SP.lIlilIIl` → `iliilIliiI.IiIllIIl` (Map<UUID,snapshot>).
- Подмена/откат ТОЛЬКО через main-thread очередь `GameContext.runOnMainThread()` (выгребается CheatHud.renderFrame каждый кадр) — чтение карт рендером и сетью в одном потоке с мутацией.
- Откат: только если в карте до сих пор НАШ RL по identity (`texMap.get(skinTypeKey) == putinRl`) — если игра сама перезаписала, не трогаем; исходно null → remove ключа. Мёртвые снапшоты чистятся retainAll(live) каждый apply.

**ГЛАВНЫЙ ГАТЧ (пойман живым тестом):** `texObjClass.newInstance()` на ИНТЕРФЕЙСЕ iiIiliiIiI = InstantiationException — инстанцировать НАШ класс `new rustme.SvoTexture()` напрямую (он в том же лоадере/пакете). Симптом в логе: `SVO [ERROR] resolve failed ... InstantiationException: rustme.iiIiliiIiI`.

**Мелочи:** retry регистрации раз в 2с (lastRegisterAttempt) — иначе спам очереди каждый 1мс-тик; RL-домен "svo" — фиктивный, TextureManager не резолвит его через ресурсы (наша текстура уже в его Map). Лимит: рука локального игрока и Tab-головы читают другие пути — не проверялись юзером на важных.

**NPE в живом логе 09-09 (мелочь, не чинилось):** `[SVO][ERROR] apply exception exc=java.lang.NullPointerException: Cannot invoke "Object.getClass()" because "obj" is null` — при очередном apply что-то null (вероятно снапшот с null-полем в карте); свап при этом работал (`skins swapped: +83/+84`). Чинить при следующей правке Svo.java: null-чек перед getClass/identity-обработкой снапшота.

Связано: [[rustme-norecoil]], [[rustme-next-features]], [[rustme-remote-player-info]].
