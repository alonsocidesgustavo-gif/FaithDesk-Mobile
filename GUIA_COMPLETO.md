# FaithDesk Mobile — Guia Completo de Setup

## Visão Geral da Arquitetura

```
┌─────────────────────────────────────────────────────────┐
│                    INTERNET                              │
│                                                         │
│  Android A (HOST)         Android B / PC (CONTROLADOR)  │
│  ┌──────────────┐         ┌──────────────────────────┐  │
│  │ MediaProjection│        │  SurfaceView / <video>  │  │
│  │ WebRTC (sender)│◄──────►│  WebRTC (receiver)      │  │
│  │ AccessibilitySvc│       │  Controles de toque     │  │
│  └──────┬───────┘         └────────────┬─────────────┘  │
│         │                              │                 │
│         └───────┐        ┌─────────────┘                │
│                 ▼        ▼                               │
│          ┌─────────────────────┐                        │
│          │  SERVIDOR NODE.JS   │                        │
│          │  - Sinalização      │                        │
│          │  - Sessões/IDs      │                        │
│          │  - SEM armazenar    │                        │
│          │    tela/dados       │                        │
│          └─────────────────────┘                        │
└─────────────────────────────────────────────────────────┘
```

---

## ETAPA 1: Configurar o Servidor

### Opção A: Deploy na nuvem (Recomendado para redes diferentes)

**Railway (gratuito para começar):**
```bash
# 1. Instale Railway CLI
npm install -g @railway/cli

# 2. Faça login
railway login

# 3. Na pasta /server:
cd server
railway init
railway up
# Anote a URL gerada: https://xxxxx.railway.app
```

**Render.com (alternativa gratuita):**
```bash
# Conecte seu GitHub → New Web Service → Cole a pasta /server
# Build command: npm install
# Start command: node server.js
```

**VPS próprio (Ubuntu):**
```bash
# Instalar Node.js 20
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs

# Clonar e configurar
cd /var/www
git clone <seu-repositório>
cd faithdesk/server
npm install

# Rodar com PM2 (manter ativo)
npm install -g pm2
pm2 start server.js --name faithdesk
pm2 startup
pm2 save

# HTTPS com Nginx + Let's Encrypt (necessário para WebRTC)
sudo apt install nginx certbot python3-certbot-nginx
sudo certbot --nginx -d seu-dominio.com
```

**Com Docker:**
```bash
cd server
docker build -t faithdesk-server .
docker run -d -p 3000:3000 --name faithdesk faithdesk-server
```

### Verificar se funcionou
```
GET https://seu-servidor.com/health
# Retorna: {"status":"ok","devices":0,"sessions":0}
```

---

## ETAPA 2: Configurar o App Android

### 2.1 Dependências necessárias
- **Android Studio** (versão Iguana 2023.2+ ou superior)
- **JDK 17**
- **Dispositivo Android** com Android 8.0+ (API 26)

### 2.2 Ajustar a URL do servidor

Edite o arquivo:
```
android/app/src/main/java/com/faithdesk/mobile/network/SignalingManager.kt
```

Mude a linha:
```kotlin
const val SERVER_URL = "https://seu-servidor-faithdesk.com"
```
Para a URL do seu servidor.

**Para testes locais (emulador):**
```kotlin
const val SERVER_URL = "http://10.0.2.2:3000"
```

**Para testes locais (dispositivo físico na mesma rede):**
```kotlin
const val SERVER_URL = "http://192.168.X.X:3000"  // IP do seu PC
```

### 2.3 Compilar o APK

```bash
# No Android Studio:
Build → Build Bundle(s) / APK(s) → Build APK(s)

# Ou via terminal:
cd android
./gradlew assembleDebug

# APK gerado em:
# android/app/build/outputs/apk/debug/app-debug.apk
```

### 2.4 Instalar no Android

```bash
# Via ADB (com USB):
adb install app/build/outputs/apk/debug/app-debug.apk

# Ou: copie o APK para o celular e instale manualmente
# (Ative "Fontes desconhecidas" nas configurações)
```

---

## ETAPA 3: Configurar o Cliente Windows

### 3.1 Instalar dependências

```bash
cd windows-client
npm install
```

### 3.2 Ajustar URL do servidor

Edite `renderer/index.html`, linha:
```javascript
const SERVER_URL = 'https://seu-servidor-faithdesk.com';
```

### 3.3 Rodar em desenvolvimento

```bash
npm start
```

### 3.4 Gerar instalador .exe

```bash
npm run build
# Instalador em: windows-client/dist/FaithDesk Mobile Setup.exe
```

---

## ETAPA 4: Configurar STUN/TURN

### STUN gratuito (já configurado)
O projeto já usa os servidores STUN públicos do Google e Cloudflare.
Funciona para a maioria dos casos (sem NAT simétrico).

### TURN próprio (para redes corporativas/4G restritivas)

**Coturn (servidor TURN open-source):**
```bash
# Instalar no Ubuntu
sudo apt-get install coturn

# Configurar /etc/turnserver.conf:
listening-port=3478
tls-listening-port=5349
fingerprint
lt-cred-mech
realm=seu-dominio.com
user=faithdesk:senha-forte
cert=/etc/letsencrypt/live/seu-dominio.com/fullcert.pem
pkey=/etc/letsencrypt/live/seu-dominio.com/privkey.pem
log-file=/var/log/turnserver.log
no-cli

# Iniciar
sudo systemctl enable coturn
sudo turnserver -c /etc/turnserver.conf
```

**Adicionar TURN nos clientes:**

No `SignalingManager.kt` (Android):
```kotlin
PeerConnection.IceServer.builder("turn:seu-dominio.com:3478")
    .setUsername("faithdesk")
    .setPassword("senha-forte")
    .createIceServer()
```

No `renderer/index.html` (Windows):
```javascript
const ICE_SERVERS = [
  { urls: 'stun:stun.l.google.com:19302' },
  {
    urls: 'turn:seu-dominio.com:3478',
    username: 'faithdesk',
    credential: 'senha-forte'
  }
];
```

---

## ETAPA 5: Permissões no Android (passo a passo)

### 5.1 Serviço de Acessibilidade (para controle remoto)
1. Abra o app FaithDesk Mobile
2. Toque em **Configurações**
3. Vá em: `Configurações do Android → Acessibilidade`
4. Encontre **FaithDesk — Controle Remoto**
5. Ative o serviço
6. Leia e confirme a permissão

> ⚠️ O serviço só injeta gestos quando uma sessão autorizada estiver ativa.

### 5.2 Captura de tela (MediaProjection)
- Solicitada automaticamente quando uma sessão é aceita
- Aparece um diálogo do Android: "Permitir que FaithDesk grave sua tela?"
- O usuário deve tocar em **Permitir**

### 5.3 Notificações (Android 13+)
- Solicitada automaticamente na primeira abertura

---

## ETAPA 6: Testes

### Teste 1: Mesma rede Wi-Fi
```
1. Inicie o servidor localmente (npm start em /server)
2. Configure SERVER_URL = "http://IP-do-seu-PC:3000"
3. Instale o app nos dois celulares
4. Celular A: anote o ID
5. Celular B: abra "Conectar" → digite o ID do celular A
6. Celular A: aceite a conexão
7. Celular B: deverá ver a tela do celular A em tempo real
```

### Teste 2: Redes diferentes (4G vs Wi-Fi)
```
1. Use o servidor em nuvem (Railway/Render/VPS)
2. Celular A: conecte no Wi-Fi
3. Celular B: use o 4G (desative o Wi-Fi)
4. Repita o processo de conexão
5. O STUN do Google normalmente resolve — se não resolver, adicione TURN
```

### Teste 3: PC → Android
```
1. PC Windows: execute o cliente (npm start)
2. Android: abra o FaithDesk e anote o ID
3. PC: clique em Conectar → digite o ID → Enter
4. Android: aceite a solicitação
5. PC: verá a tela do Android na janela do aplicativo
```

### Depuração de problemas
```bash
# Ver logs do servidor
pm2 logs faithdesk

# Verificar ICE candidates (no Android Studio Logcat)
filter: "WebRTCManager"

# Verificar sinalização
filter: "SignalingManager"

# Se a conexão falhar com STUN:
# → Adicione um servidor TURN
# → Verifique se a porta 3000 do servidor está aberta
# → Verifique o firewall do VPS (libere porta 3000 TCP)
```

---

## ETAPA 7: Estrutura final de arquivos

```
faithdesk/
│
├── server/
│   ├── server.js          ← Servidor de sinalização principal
│   ├── package.json
│   └── Dockerfile
│
├── android/
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/faithdesk/mobile/
│       │   ├── FaithDeskApp.kt        ← Application class
│       │   ├── network/
│       │   │   └── SignalingManager.kt ← Socket.IO
│       │   ├── webrtc/
│       │   │   └── WebRTCManager.kt   ← WebRTC P2P
│       │   ├── service/
│       │   │   ├── ScreenCaptureService.kt ← MediaProjection
│       │   │   └── RemoteControlService.kt ← Accessibility
│       │   └── ui/
│       │       ├── MainActivity.kt     ← Tela inicial (ID)
│       │       ├── SessionActivity.kt  ← Sessão ativa (host)
│       │       └── ControllerActivity.kt ← Controlador
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── activity_session.xml
│           │   └── activity_controller.xml
│           ├── values/
│           │   ├── colors.xml
│           │   ├── strings.xml
│           │   └── themes.xml
│           └── xml/
│               └── accessibility_service_config.xml
│
└── windows-client/
    ├── main.js             ← Electron main
    ├── preload.js
    ├── package.json
    └── renderer/
        └── index.html      ← UI completa + WebRTC JS
```

---

## Segurança — o que está implementado

| Recurso | Status |
|---|---|
| ID único por dispositivo | ✅ |
| Solicitação explícita de conexão | ✅ |
| Aceitar/Recusar | ✅ |
| Notificação foreground visível | ✅ |
| Banner vermelho durante sessão | ✅ |
| Botão encerrar sempre visível | ✅ |
| Expiração de sessões (8h) | ✅ |
| Sem armazenamento de tela | ✅ |
| Sem backdoor ou acesso oculto | ✅ |
| WebRTC criptografado (DTLS-SRTP) | ✅ |
| Rate limiting no servidor | ✅ |
| AccessibilityService só age com sessão ativa | ✅ |

---

## Próximos passos opcionais

- [ ] Adicionar autenticação por conta (e-mail/senha)
- [ ] Criptografia de ponta a ponta da sinalização
- [ ] Histórico de sessões (local, sem dados de tela)
- [ ] Controle de qualidade de vídeo adaptativo
- [ ] Suporte a múltiplas sessões simultâneas
- [ ] App iOS (usando mesma arquitetura WebRTC)
