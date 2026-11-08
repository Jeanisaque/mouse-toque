# Proteção Família

Antifurto para o celular dos seus filhos (e o seu). Dois apps:

- **`dispositivo`** — instala no celular monitorado (ícone de engrenagem, nome visível
  "Proteção Família"). Manda localização, avisa se desligar/trocar o chip, toca alarme
  remoto, trava a tela, e é protegido por PIN (pra desligar alarme, mudar configurações
  ou remover a proteção).
- **`painel`** — instala no seu celular. Mostra no mapa onde cada celular da família
  está, histórico do caminho percorrido, e os botões de ação (alarme, travar, localizar
  agora).

Os dois se falam através do **Firebase** (gratuito, do Google) — sem precisar manter
servidor próprio no ar.

## O que isso realmente faz (e o que não faz)

- ✅ Localização em tempo real + histórico, enquanto o celular estiver ligado e com
  internet (Wi-Fi ou dados).
- ✅ **Última localização salva automaticamente** no instante em que o celular está
  sendo desligado, ou quando a bateria fica crítica — é o dado mais útil pra passar
  pra polícia num roubo ("foi desligado perto da Rua X, às 14h32").
- ✅ Alarme remoto (som alto, ignora modo silencioso) e travar a tela remotamente.
- ✅ Aviso de melhor esforço se trocarem o chip (SIM) do aparelho.
- ✅ Dificulta a remoção do app (exige PIN) — mas qualquer app de administrador de
  dispositivo pode ser desativado pelas Configurações do Android; quando isso
  acontece, o painel recebe um aviso na hora. Dá para subir pra um nível mais forte
  (veja "Proteção máxima" abaixo).
- ✅ **Apagar tudo remotamente** (como uma formatação de fábrica), em último caso —
  só funciona com a "proteção máxima" ativa. Protegido por 3 confirmações no painel
  (incluindo digitar "APAGAR") e por 15 segundos de contagem na tela do celular, onde
  ainda dá pra cancelar com o PIN, caso o comando tenha sido disparado por engano.
- ❌ **Não existe jeito de rastrear um celular genuinamente desligado (sem bateria/sem
  energia).** Nenhum app de terceiros consegue — isso só existe nativamente em
  alguns iPhones recentes (rede "Localizar") e em alguns Android recentes
  ("Rede Encontre Meu Dispositivo"), feito pela Apple/Google no nível do sistema.
  **Ative isso também, de graça, como camada extra** (veja abaixo).
- ⚠️ A detecção de troca de chip é "melhor esforço": o Android 10+ restringe esse
  tipo de informação, então pode não funcionar em todos os aparelhos.

### Ative também o "Encontre Meu Dispositivo" nativo (gratuito, da Google)

No celular do seu filho: Configurações → Google → "Encontre Meu Dispositivo" → ativar,
e também ativar "Permitir localização via rede Bluetooth" (em aparelhos que têm essa
opção). Isso funciona em paralelo com o nosso app e cobre parte do cenário "desligado".

## Proteção máxima (Device Owner) — opcional, mas recomendado

O app já funciona sem isso (nível "Device Admin"). Mas dá pra subir pra um nível mais
forte, chamado **Device Owner** — o mesmo usado por celulares de empresa — que:

- Bloqueia o "Apagar todos os dados"/formatar de fábrica pelas Configurações.
- Bloqueia a desinstalação do app pelas Configurações (nem a opção aparece).
- Bloqueia o Modo Seguro (um jeito comum de burlar apps assim).
- É o que permite o botão **"Apagar tudo"** (apagamento remoto de verdade) funcionar
  no painel.

**O preço disso**: só dá pra ativar num celular **zerado**, antes de configurar
qualquer conta Google — ou seja, exige formatar o celular do seu filho (ou usar um
celular novo) e fazer esse passo ANTES de ele configurar o aparelho normalmente.
Depois de ativado, ele continua usando o celular normalmente, só que com essas
travas extras.

⚠️ Dependendo da versão do Android/fabricante, esse recurso pode estar mais restrito
nos anos mais recentes — se o comando abaixo falhar, o app continua funcionando
normal, só sem essas travas extras.

### Como ativar

1. Zere o celular do seu filho (Configurações → Sistema → Opções de redefinição →
   Apagar todos os dados), ou use um celular novo — e **não adicione nenhuma conta**
   ainda na tela de configuração inicial.
2. Ative as Opções do desenvolvedor (tocar 7 vezes em Configurações → Sobre o
   telefone → Número da versão) e, dentro delas, a **Depuração USB**.
3. Conecte esse celular por cabo USB no seu PC. Instale o
   [ADB](https://developer.android.com/tools/releases/platform-tools) no PC, se
   ainda não tiver.
4. No PC, dentro da pasta `dispositivo`, gere e instale o APK:
   ```
   adb install app/build/outputs/apk/debug/app-debug.apk
   adb shell dpm set-device-owner br.com.protecaofamilia.dispositivo/.AdminReceiver
   ```
5. Abra o app no celular e siga a configuração normal (vincular, PIN, permissões).
   Quando terminar, a tela principal mostra **"✓✓ Protegido (proteção máxima)"**.
6. Agora sim, pode adicionar a conta Google e usar o celular normalmente.

## Como configurar (uma vez só)

### 1. Criar o projeto Firebase (gratuito)

1. Acesse [console.firebase.google.com](https://console.firebase.google.com) e crie um
   projeto novo (ex.: "Proteção Família").
2. No projeto, vá em **Build → Firestore Database** → criar banco, modo produção,
   localização mais próxima de você.
3. Em **Build → Authentication → Sign-in method**, ative o provedor **Anônimo**.
4. Em **Configurações do projeto → Suas apps**, adicione **dois apps Android**:
   - Pacote `br.com.protecaofamilia.dispositivo` → baixe o `google-services.json`
     e coloque em `dispositivo/app/google-services.json`.
   - Pacote `br.com.protecaofamilia.painel` → baixe o `google-services.json`
     (outro arquivo, mesmo projeto) e coloque em `painel/app/google-services.json`.

   (Esses arquivos não ficam no Git — veja o `.gitignore` de cada app.)

5. Em **Firestore → Regras**, cole o conteúdo de [`firestore.rules`](firestore.rules).

   **Importante sobre segurança:** não existe um "servidor" validando quem é quem —
   a segurança depende do **código da família** ser longo e secreto (ele já é gerado
   assim, com ~20 caracteres aleatórios). Não poste esse código em lugar público;
   compartilhe só por WhatsApp/SMS direto com quem for usar.

### 2. Gerar os APKs

Cada app é um projeto Gradle independente (como o Touch Cell):

```
cd protecao-familia/dispositivo
./gradlew assembleDebug
# APK em app/build/outputs/apk/debug/app-debug.apk

cd ../painel
./gradlew assembleDebug
```

Pra gerar `.apk` assinado de release, use as mesmas variáveis de ambiente do
Touch Cell (`KEYSTORE_FILE`, `KEYSTORE_PASS`, `VERSION_CODE`, `VERSION_NAME`).

### 3. Usar

1. Instale o **`painel`** no seu celular → toque em **"Criar família"** → ele mostra
   um código (ex.: `AB3X-9KPZ-...`).
2. Instale o **`dispositivo`** no celular do seu filho → cole esse código → dê um
   nome ao aparelho → crie um PIN → conceda as permissões pedidas (localização,
   notificação, telefone) → permita ignorar a otimização de bateria → ative como
   administrador do dispositivo.
3. No painel, o celular já aparece na lista.

## Estrutura

```
protecao-familia/
  dispositivo/   app instalado no celular monitorado
  painel/        app do pai/mãe, com o mapa e os comandos
  firestore.rules
```
