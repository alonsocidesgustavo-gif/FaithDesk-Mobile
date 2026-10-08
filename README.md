# FaithDesk Mobile

Projeto dividido em três partes:

- `android/` — aplicativo Android
- `server/` — servidor de sinalização Socket.IO
- `windows-client/` — cliente Windows/Electron

## Estrutura

```text
FaithDesk-Mobile/
├── android/
│   ├── build.gradle
│   ├── settings.gradle
│   └── app/
├── server/
├── windows-client/
├── build.gradle
├── gradle.properties
├── settings.gradle
├── .gitignore
└── README.md
```

## Android

Abra a pasta raiz no Android Studio. O módulo do aplicativo é `android/app`.

Requisitos:
- Android Studio recente
- JDK 17
- Android SDK 34

O aplicativo usa MediaProjection para compartilhamento de tela e AccessibilityService para ações de controle, sempre com ativação/permissão do usuário.

### URL do servidor

Antes de testar, configure a URL do servidor em:

`android/app/src/main/java/com/faithdesk/mobile/network/SignalingManager.kt`

Troque `https://seu-servidor-faithdesk.com` pela URL real do seu servidor.

Para emulador Android local, use `http://10.0.2.2:3000`.

## Servidor

```bash
cd server
npm install
npm start
```

Teste:

`http://localhost:3000/health`

## Windows

```bash
cd windows-client
npm install
npm start
```

Para gerar instalador:

```bash
npm run build
```

## GitHub

Envie o conteúdo desta pasta para o repositório. Não envie `node_modules`, `build` ou `dist`; o `.gitignore` já cobre esses diretórios.

> O Gradle Wrapper binário não foi incluído porque o arquivo original não o possuía. O projeto, porém, já está estruturado como um projeto Gradle/Android Studio válido. No Android Studio, a sincronização do Gradle cria/usa a infraestrutura necessária.
