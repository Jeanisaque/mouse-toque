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
  acontece, o painel recebe um aviso na hora.
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
