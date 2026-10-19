# Stop BT Music

App Android que **pausa automaticamente a música quando um dispositivo Bluetooth de áudio se desconecta** (fones, som do carro, caixa de som).

Restaura o comportamento antigo (Android 15 e antes): antes, perder o A2DP pausava o player. No Android 16, a música continua tocando nos alto-falantes do celular mesmo com o Bluetooth fora. Este app devolve esse comportamento, funcionando com **Spotify, YouTube Music e qualquer outro player** — não depende de nenhum app específico.

## Como funciona

- `MonitorService` (foreground service) faz polling de `BluetoothA2dp.getConnectedDevices()` a cada 1500ms.
- Ao detectar a transição de "conectado → nenhum dispositivo", espera 600ms (tolerância para reconexões rápidas) e, se ainda estiver vazio, pausa os players.
- A pausa é feita enviando a **tecla de mídia global** (`GLOBAL_ACTION_MEDIA_PLAY_PAUSE`) via um serviço de acessibilidade mínimo (`StopBLAccessibilityService`).
- Como a tecla é um *toggle*, o serviço **confirma após cada envio**: espera 900ms, e se o áudio ainda estiver ativo (tecla perdida na troca de rota de áudio) reenvia, até 2 tentativas. Se o A2DP reconectou no meio, cancela.
- O serviço de acessibilidade é configurado no mínimo: `canRetrieveWindowContent="false"`, não lê a tela, não intercepta toques — só envia a tecla.

> Por que acessibilidade? No Android 16, `MediaController` de outros apps exige `MANAGE_MEDIA_ACCESS` (permissão de sistema, sem como pedir em app sideloaded). A tecla de mídia global é o único caminho público que funciona em qualquer player.

## Requisitos

- Android 16 (API 36) — o `minSdk` é 36 por causa das novas APIs de Bluetooth/MediaSession.
- Gradle 9.5.1, AGP 8.13.2, Kotlin 2.4.20, JDK 11+, Android SDK platform 36.

## Build

O build roda em outra máquina (a local fica sem memória). O script faz rsync do código para `batman@pc.lan` e roda o Gradle lá:

```bash
./build-remote.sh
```

O APK gerado em `app/build/outputs/apk/debug/app-debug.apk` precisa ser buscado de volta (o script não faz isso):

```bash
mkdir -p app/build/outputs/apk/debug
rsync -az batman@pc.lan:~/dev/stop-bl-music/app/build/outputs/apk/debug/app-debug.apk \
  app/build/outputs/apk/debug/
```

Alternativa: com o repo no GitHub, o workflow `.github/workflows/build.yml` roda ao empurrar uma tag `v*` — builda o `assembleDebug`, publica o APK como artefato do build e cria uma release do GitHub com o APK anexado.

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## Instalação

```bash
adb -s <SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

## Passo a passo para habilitar

1. **Abrir o app e conceder as permissões** — notificações e Bluetooth (diálogos do sistema). O monitoramento inicia sozinho.

2. **Habilitar a acessibilidade** (obrigatório):
   - No app, toque em **"Configurações de acessibilidade"**.
   - Em "Serviços instalados", ative **Stop BT Music**.
   - O app mostra `Acessibilidade: habilitada ✓` quando estiver ligada.

3. **Testar** (com música tocando nos fones BT): toque em **"Teste: enviar tecla play/pause"** — o áudio deve pausar. Repetir deve retomar.

4. **Isentar de otimização de bateria**: toque em **"Isentar de otimização de bateria"** e aceite o diálogo.

5. **Confiabilidade no HyperOS** (o sistema mata apps em background agressivamente):
   - **Auto-start**: Configurações → Apps → Gerenciar apps → *Início automático* → habilite o Stop BT Music.
   - **Lock nos recentes**: abra o app, vá em Recentes e trave o app (long-press no card → cadeado) para a limpeza de memória não matá-lo.

## Comportamento pós-boot

- `BootReceiver` escuta `BOOT_COMPLETED` e reinicia o `MonitorService` via `startForegroundService`, **desde que o monitoramento estivesse ligado** antes do reboot (o estado fica em `SharedPreferences`).
- O serviço de acessibilidade persiste entre reboots — a partir do passo 2, não precisa re-habilitar.
- Enquanto ligado, o app mostra a notificação persistente `Monitorando…` (foreground service) e o app inicia sozinho se você abri-lo com o monitoramento já ativo.

## Tela do app

| Elemento | O que faz |
| --- | --- |
| **Iniciar / Parar monitoramento** | Liga/desliga o serviço (e salva o estado para o boot) |
| **Teste: enviar tecla play/pause** | Envia a tecla uma vez, sem confirmação (só para validar a acessibilidade) |
| **Configurações de acessibilidade** | Abre a tela do sistema |
| **Isentar de otimização de bateria** | Abre o diálogo `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| `Último evento: …` | Último resultado da cadeia de pausa (ex.: `Pausa via tecla de mídia`) |
| `Pausas enviadas: N` | Contador de pausas executadas pela detecção automática |

## Troubleshooting

- **Acessibilidade "desabilitada"**: o serviço não está ativo — repita o passo 2. Sem ela, o app não consegue pausar nada (mostra `Habilite a acessibilidade para pausar`).
- **`Tecla de mídia não pausou`**: a tecla foi enviada 3× e o áudio continuou ativo. Raro — verifique logcat (abaixo).
- **Nada acontece ao desconectar o BT**: confirme que o monitoramento está rodando (notificação `Monitorando…`), que a música estava de fato no A2DP, e veja o logcat:

```bash
adb -s <SERIAL> logcat -d -s StopBL
```

Linha do tempo esperada em uma desconexão:

```
A2DP conectado: 1 dispositivo
A2DP vazio — aguardando grace period
A2DP ainda vazio após grace — pausando
Tecla play/pause enviada (tentativa 1)
Pausa via tecla de mídia
```

## Estrutura

```
app/src/main/java/dev/sovietlab/stopbl/
├── MainActivity.kt                    # UI, permissões, start/stop
├── MonitorService.kt                  # FGS, polling A2DP, cadeia de pausa
├── StopBLAccessibilityService.kt      # tecla de mídia global + isMediaActive
└── BootReceiver.kt                    # reinicia o serviço no BOOT_COMPLETED
```
