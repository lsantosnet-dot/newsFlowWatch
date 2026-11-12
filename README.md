# NewsFlow Watch

Versão do [NewsFlow](https://github.com/lsantosnet-dot/newsFlow) para relógios
Wear OS (feita para o Galaxy Watch). É um app só de leitura: escolhe um grupo,
mostra os artigos não lidos, lê o resumo na tela ou em voz alta. Criar e editar
grupos, fontes e critérios continua sendo feito no app do celular.

## O que faz

| Tela | O que tem |
|---|---|
| **Grupos** | Os grupos existentes, com a contagem de não lidos. A escolha fica só no relógio: não troca o grupo ativo do celular. |
| **Lista** | Não lidos do grupo, por relevância e depois data (mesma ordem do celular), de 8 em 8. Botão **Ouvir tudo**. |
| **Artigo** | Resumo completo, **Ouvir**, **Guardar p/ celular** (favorito) e **Marcar como lido**. |
| **Ouvir** | Leitura em voz alta com o motor de voz do relógio: pausar, pular, voltar, volume. Continua com a tela apagada e responde aos botões do fone Bluetooth. |
| **Configuração** | O ID do projeto Firebase. É a única configuração. |

Ouvir um artigo até o fim marca como lido, igual ao modo podcast do celular.
Ler o resumo não marca sozinho. Como no celular, o pipeline apaga lidos não
favoritados no ciclo seguinte; favoritar protege o artigo.

## Como conversa com o Firestore

Pela API REST, sem SDK do Firebase e **sem chave**. As regras do NewsFlow
liberam leitura pública e, em `articles`, só a alteração de `read`, `read_at`
e `favorite`, que são exatamente as três escritas do relógio. Por isso basta o
**ID do projeto**, que não é segredo e não fica no código:

- no build, vem de `-PfirebaseProjectId=...` ou da variável de ambiente
  `FIREBASE_PROJECT_ID`;
- no relógio, pode ser digitado ou ditado em **Configuração**, o que vale mais
  que o do build.

O teclado de alguns relógios não oferece números nem hífen. Nesse caso, defina
o ID pelo computador, com o relógio conectado por `adb`:

```bash
adb shell am start -n com.lsantosnet.newsflow.watch/.ui.MainActivity --es project_id SEU-PROJETO
```

Nada precisa mudar no pipeline, nas regras ou nos índices. A lista usa o índice
`(profile_id, read, relevance_score desc, curated_at desc)` que o NewsFlow já publica.

## Gerar o APK

**Pelo GitHub Actions** (não precisa de Android Studio):

1. Opcional: em *Settings → Secrets and variables → Actions → Variables*, crie
   `FIREBASE_PROJECT_ID` com o ID do seu projeto. Sem ela, o app pede o ID no relógio.
2. O workflow **Gerar APK do relógio** roda a cada push e também manualmente
   (*Actions → Gerar APK do relógio → Run workflow*).
3. Baixe o artefato `newsflow-watch-apk` da execução.

**Local**, com o Android SDK instalado:

```bash
./gradlew testDebugUnitTest assembleRelease -PfirebaseProjectId=SEU-PROJETO
```

O APK sai em `app/build/outputs/apk/release/app-release.apk`.

## Instalar no relógio

1. No relógio: *Configurações → Sobre o relógio → Informações do software* e
   toque 7 vezes em *Versão do software* para liberar as opções do desenvolvedor.
2. *Configurações → Opções do desenvolvedor*: ligue **Depuração ADB** e
   **Depuração sem fio**. Relógio e computador na mesma rede Wi‑Fi.
3. Em *Depuração sem fio → Parear novo dispositivo*, o relógio mostra um IP,
   uma porta e um código:
   ```bash
   adb pair IP:PORTA_DO_PAREAMENTO
   ```
4. Conecte usando o IP e a porta da tela principal de *Depuração sem fio*:
   ```bash
   adb connect IP:PORTA
   ```
5. Instale:
   ```bash
   adb install -r app-release.apk
   ```

Todo APK deste repositório é assinado com a mesma chave (`app/sideload.keystore`),
então uma versão nova instala por cima da anterior. Essa chave existe só para
instalação manual de um app pessoal; não serve para publicar na Play Store.

## Estrutura

```
app/src/main/java/com/lsantosnet/newsflow/watch
  ├── data/FirestoreJson.kt    # Monta consultas e interpreta respostas da API REST
  ├── data/FirestoreApi.kt     # Chamadas HTTP (perfis, não lidos, marcar lido, favoritar)
  ├── data/NewsRepository.kt   # Estado compartilhado entre telas e leitura em voz alta
  ├── data/Labels.kt           # Textos curtos e o roteiro da narração
  ├── tts/ReaderService.kt     # Fila de leitura: TTS, tela apagada, sessão de mídia
  ├── ui/MainActivity.kt       # Navegação e tema
  ├── ui/Screens.kt            # Grupos, Lista, Artigo, Ouvir
  └── ui/SettingsScreen.kt     # ID do projeto
app/src/test/.../FirestoreJsonTest.kt   # Testes de JVM do formato das consultas e respostas
```

## Limites conhecidos

- **Voz em português** depende do motor de TTS instalado no relógio. Se não
  houver, o app avisa ao tentar ouvir.
- **Sem cache offline**: precisa de Wi‑Fi, LTE ou do celular por perto.
- **Sem Tile nem complicação** por enquanto.
- Requer Wear OS 3 ou mais novo (API 30).
