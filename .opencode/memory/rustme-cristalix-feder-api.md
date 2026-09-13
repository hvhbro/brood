---
name: rustme-cristalix-feder-api
description: Папка jni/1 — распакованная Java-библиотека feder (Cristalix серверная API от dev.xdark) + BungeeCord-нативщики; для чит-клиента напрямую почти бесполезна, но есть идеи
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_88cf13da-bee6-4c0a-b169-78788a0320ad
---

`C:\Users\Admin\Desktop\rustme\jni\1` — распакованные .class-файлы (212 шт., plain, НЕ protected-формат) — старая серверная утилита/API Cristalix (другой форк MC с кастомным JVM), 2021 год:

- **`dev.xdark.feder.*`** — utility-библиотека xdark'а: `network.Packet` (netty ByteBuf read/write, ProtocolDirection/PacketMode — серверная абстракция протокола), `compression` (JavaZlib/NativeZlib/NativeZstd/PacketCompressor), `ciphering` (JavaCipher/NativeCipher), `text`, `management.Threads` (getAllThreads()/getAllThreadIds() через JVM-internals `JavaLangAccess`/`VMInternals` + `Threads$_ThreadList` — перечисление потоков БЕЗ getAllStackTraces), `internal` (J8/J9JavaLangAccess), array/collection utils.
- **`pw.lach.bungee.NativeCipherImpl`** — JNI AES: `native init(boolean, byte[]):long; free(long); cipher(long,long,long,int)` + **Linux ELF** native-cipher.so / native-zlib.so (на Windows мертвы).
- **`gson`**, MC-хелперы (ChatAllowedCharacters, ChunkBiome/ChunkBiomeRegistry), net.md_5.bungee.jni.zlib.

**Оценка для чит-клиента rustme:** как готовая библиотека — почти бесполезна (пакетный API — серверная сторона Cristalix, интерфейсы несовместимы с клиентским стеком; нативы — Linux ELF; утилиты генерические). Ценность — как шпаргалка техник:
1. `Threads.getAllThreads()` через JavaLangAccess — запасной вариант, если протектор когда-нибудь заблокирует `Thread.getAllStackTraces()` (именно он в агенте ищет правильный лоадер — см. [[rustme-jni-defineclass-bypass]]).
2. Паттерн NativeCipher (JNI-крипто через BungeeCord) — если понадобится крипто в агенте.
3. Классы plain-формата → инжектятся нашей трубой (encode_protected → DefineClass), но необходимости нет.

**Доп. оценка (2026-09-05, юзер показал ещё и C++ SDK от Cristalix-чита — CEntity/StrayCache стиль):** классический JNI-чит-SDK на ванильных именах (`entity_motionX`, `entity_onGround`...) + версионные свитчи FORGE_1_18_1. Копировать НЕ нужно: их путь через `env->GetFieldID` с ванильными именами не работает под протектором rustme (reflection заблокирован), а их C++-центричная архитектура слабее нашей (Java-агент + reflection vs C++ + ручные JNI-вызовы). Взять две идеи: (1) StrayCache-стиль — кешировать резолвнутые Field/Method в статик-полях агента один раз при init, а не резолвить каждый тик; (2) версионность — вынести все обфусцированные имена в один конфиг-блок вверху агента для апдейтов (сейчас размазаны по init). Полный C++-SDK-порт — шаг назад, не делать.
