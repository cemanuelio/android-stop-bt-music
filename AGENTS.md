# AGENTS.md

Projeto em pt-BR: comentários, textos de UI/erro e docs em português; identificadores de código em inglês.

## Build

- **Nunca rode Gradle localmente** — a máquina local OOM. Use `./build-remote.sh [task...]` (rsync p/ `batman@pc.lan` + Gradle lá).
- O script **não traz o APK de volta**:
  ```bash
  rsync -az batman@pc.lan:~/dev/stop-bl-music/app/build/outputs/apk/debug/app-debug.apk \
    app/build/outputs/apk/debug/
  ```
- Não há testes de unidade; a verificação é no dispositivo.

## Dispositivo / adb

- adb fora do PATH: `/home/batman/Android/Sdk/platform-tools/adb`, sempre com `-s e0fa1512`.
- Log do app: `adb -s e0fa1512 logcat -d -s StopBL`.
- O HyperOS 3 mata apps em background: confiabilidade real exige isenção de bateria + auto-start + lock nos recentes (passo a passo no README). Verificar whitelist: `adb -s e0fa1512 shell dumpsys deviceidle whitelist | grep stopbl`.

## CI / release

- Workflow só roda em **tag `v*`** (push) ou `workflow_dispatch` (que não cria release). Publicar: `git tag v1.0.0 && git push origin v1.0.0`.
- O workflow instala SDK próprio em `/home/runner/android-sdk`; `ANDROID_HOME` e `ANDROID_SDK_ROOT` precisam ser iguais (a imagem pré-definindo `SDK_ROOT` outro → AGP aborta). `env:` no YAML **não** expande shell/expressões — use caminho absoluto fixo.
- `gh run rerun` reusa o workflow do commit ORIGINAL do run; para testar fix no workflow, re-aponte a tag pro commit corrigido e re-push.
- Mantenha o `README.md` sincronizado com mudanças no workflow.

## Arquitetura / API 36

- A pausa é via **acessibilidade** (`GLOBAL_ACTION_MEDIA_PLAY_PAUSE`) com loop de retry/confirm. O caminho `MediaController` é morto no A16 (`MANAGE_MEDIA_ACCESS` é permissão de sistema) — não refatore de volta pra lá.
- Gotchas API 36 já verificados com `javap`: `BluetoothProfile` é interface (`closeProfileProxy`); `MediaController.release()` não existe mais; o XML de acessibilidade precisa de `typeAllMask`.
- APK é multidex: código do app espalha em `classes2.dex`/`classes3.dex` — ao inspecionar, grep todos os `classes*.dex`.
- `local.properties` é git-ignored; CI depende do `ANDROID_HOME` definido no workflow.
