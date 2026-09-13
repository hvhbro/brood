---
name: rustme-no-git-push
description: Git rustme — пушить ТОЛЬКО по явной просьбе юзера; remote=github hvhbro/rml-broodcheat; dump/ в репо, kimiko/rockstar-client-src/chtdump/decomp в gitignore, jar>100МБ не едет
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_e956625e-4cc0-4829-b444-3cbb0dcab14f
---

Юзер (09-09/10): «пока что на гит не надо ничего выкладывать». 09-10 юзер САМ попросил «комитни и запуши» — правило intact: git-действия только по явной команде.

**РЕПОЗИТОРИЙ (09-10, первый пуш):** origin = https://github.com/hvhbro/rml-broodcheat.git (main). Итоговая история: 34c029f7 (initial) → a7ec5a57 (коммит друга «Tracers fix + GUI Class215» — пришёл с remote, рабочий код мы уже мержили вручную 09-09, [[rustme-menu-tracers-merge]]) → 1f6db945 (наш: меню-элементы/модули/фиксы, 82 файла).

**ЧТО В РЕПОЗИТОРИИ / ЧТО ИГНОРИТСЯ (юзер определял пошагово, финал):**
- В РЕПОЗИТОРИИ: **dump/** — уже был трекаем с initial коммита (я ошибочно решил, что нет; untracked был только dump/classes/minecraft/Astraea.log — добавлен); jni/ (вкл. собранные dll/obj — трекаются исторически), tools/, .gitignore.
- В .gitignore: **kimiko/ и rockstar-client-src/** (референс-клиенты — юзер явно запретил пушить), chtdump/, tools/decomp/, tools/javap_caps|lwjglx|srv|wipe, tools/jt_*, tools/mrgchk, tools/_*.txt, DashboardPlayerUiData, jni/agent/build/, jni/dll/build_out.txt, scan_db.json, game_cp/game_stubs.jar, *.dmp.
- **dump/minecraft_FULL_DEOBF.jar (145МБ) НЕ едет** — жёсткий лимит GitHub 100МБ/файл (правило стояло в .gitignore заранее; остальной dump/ максимум 22.8МБ).

**ГАТЧИ ПУША:**
- remote может быть ВПЕРЕДИ локала (друг запушил независимо) → перед пушем fetch; наш случай: `git rebase -X theirs origin/main` — в rebase «theirs» = НАШ коммит (рабочее дерево уже содержало смёрженный результат друга), конфликты в нашу пользу, пуш прошёл.
- git add -A без .gitignore затянул 88МБ kimiko + 38МБ rockstar (3551 файл, макс. файл 19.8МБ); убираются `git rm -r --cached kimiko rockstar-client-src` + amend (коммит ещё не запушен — amend легален).

**Why:** рабочий репозиторий; юзер сам решает, что попадает в историю и что уезжает на GitHub.

**How to apply:** после правок/сборок не делать git add/commit/push самовольно; при явном «запушь» — сверить .gitignore-политику выше, fetch + rebase перед пушем. Связано с [[rustme-phase-gating]], [[rustme-menu-tracers-merge]].
