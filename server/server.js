/**
 * FaithDesk Mobile — Signaling Server
 * 
 * Responsabilidades:
 *  - Registro de dispositivos (ID único)
 *  - Sinalização WebRTC (offer/answer/ICE)
 *  - Autenticação de sessões
 *  - Gerenciamento de conexões ativas
 *  - NÃO armazena vídeo/tela dos usuários
 */

const express = require('express');
const { createServer } = require('http');
const { Server } = require('socket.io');
const { v4: uuidv4 } = require('uuid');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');

const app = express();
const httpServer = createServer(app);

// ─── Segurança ────────────────────────────────────────────────────────────────
app.use(helmet());
app.use(cors({ origin: '*' }));
app.use(express.json());

const limiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutos
  max: 100,
  message: { error: 'Muitas requisições. Tente novamente em 15 minutos.' }
});
app.use('/api/', limiter);

// ─── Socket.IO ────────────────────────────────────────────────────────────────
const io = new Server(httpServer, {
  cors: { origin: '*', methods: ['GET', 'POST'] },
  pingTimeout: 60000,
  pingInterval: 25000
});

// ─── Estado em memória (sem persistência de tela) ─────────────────────────────
const devices = new Map();   // deviceId → { socketId, name, connectedAt }
const sessions = new Map();  // sessionId → { hostId, clientId, startedAt }
const pendingRequests = new Map(); // requestId → { from, to, timestamp }

// ─── Utilitários ─────────────────────────────────────────────────────────────
function generateDeviceId() {
  // Formato: XXX-XXX-XXX (9 dígitos, fácil de ler)
  const digits = Math.floor(Math.random() * 1_000_000_000).toString().padStart(9, '0');
  return `${digits.slice(0, 3)}-${digits.slice(3, 6)}-${digits.slice(6, 9)}`;
}

function formatId(rawId) {
  const clean = rawId.replace(/\D/g, '');
  if (clean.length !== 9) return null;
  return `${clean.slice(0, 3)}-${clean.slice(3, 6)}-${clean.slice(6, 9)}`;
}

function cleanExpiredSessions() {
  const now = Date.now();
  const SESSION_TTL = 8 * 60 * 60 * 1000; // 8 horas

  for (const [id, session] of sessions) {
    if (now - session.startedAt > SESSION_TTL) {
      sessions.delete(id);
      console.log(`[Server] Sessão expirada removida: ${id}`);
    }
  }

  for (const [id, req] of pendingRequests) {
    if (now - req.timestamp > 60_000) { // 1 minuto
      pendingRequests.delete(id);
    }
  }
}

setInterval(cleanExpiredSessions, 5 * 60 * 1000);

// ─── REST API ─────────────────────────────────────────────────────────────────
app.get('/health', (_, res) => {
  res.json({
    status: 'ok',
    devices: devices.size,
    sessions: sessions.size,
    uptime: process.uptime()
  });
});

// STUN/TURN config para o cliente
app.get('/api/ice-servers', (_, res) => {
  res.json({
    iceServers: [
      // STUN público do Google
      { urls: 'stun:stun.l.google.com:19302' },
      { urls: 'stun:stun1.l.google.com:19302' },
      // STUN público alternativo
      { urls: 'stun:stun.cloudflare.com:3478' },
      // Se você tiver um servidor TURN próprio, adicione aqui:
      // {
      //   urls: 'turn:seu-servidor.com:3478',
      //   username: 'faithdesk',
      //   credential: 'senha-segura'
      // }
    ]
  });
});

// ─── WebSocket Events ─────────────────────────────────────────────────────────
io.on('connection', (socket) => {
  console.log(`[Server] Socket conectado: ${socket.id}`);

  // ── 1. Dispositivo se registra ──────────────────────────────────────────────
  socket.on('register', ({ deviceName, existingId } = {}) => {
    let deviceId = existingId && formatId(existingId);

    // Se o ID existente já está em uso por outro socket, gera novo
    if (deviceId && devices.has(deviceId) && devices.get(deviceId).socketId !== socket.id) {
      deviceId = null;
    }

    if (!deviceId) {
      // Garante unicidade
      do { deviceId = generateDeviceId(); } while (devices.has(deviceId));
    }

    devices.set(deviceId, {
      socketId: socket.id,
      name: deviceName || 'Dispositivo',
      connectedAt: Date.now()
    });

    socket.data.deviceId = deviceId;
    socket.join(deviceId);

    socket.emit('registered', { deviceId });
    console.log(`[Server] Dispositivo registrado: ${deviceId} (${deviceName})`);
  });

  // ── 2. Cliente solicita conexão ao host ─────────────────────────────────────
  socket.on('request-connection', ({ targetId }) => {
    const formattedTarget = formatId(targetId);
    if (!formattedTarget) {
      return socket.emit('error', { code: 'INVALID_ID', message: 'ID inválido.' });
    }

    const target = devices.get(formattedTarget);
    if (!target) {
      return socket.emit('error', { code: 'DEVICE_NOT_FOUND', message: 'Dispositivo não encontrado ou offline.' });
    }

    const requestId = uuidv4();
    const requesterDevice = devices.get(socket.data.deviceId);

    pendingRequests.set(requestId, {
      from: socket.data.deviceId,
      to: formattedTarget,
      fromSocket: socket.id,
      timestamp: Date.now()
    });

    // Notifica o host
    io.to(target.socketId).emit('incoming-request', {
      requestId,
      fromId: socket.data.deviceId,
      fromName: requesterDevice?.name || 'Dispositivo desconhecido'
    });

    socket.emit('request-sent', { requestId });
    console.log(`[Server] Solicitação ${requestId}: ${socket.data.deviceId} → ${formattedTarget}`);
  });

  // ── 3. Host aceita ou recusa ─────────────────────────────────────────────────
  socket.on('respond-to-request', ({ requestId, accepted }) => {
    const req = pendingRequests.get(requestId);
    if (!req) {
      return socket.emit('error', { code: 'REQUEST_EXPIRED', message: 'Solicitação não encontrada ou expirada.' });
    }

    pendingRequests.delete(requestId);

    if (!accepted) {
      io.to(req.fromSocket).emit('request-declined', { requestId });
      console.log(`[Server] Solicitação ${requestId} recusada.`);
      return;
    }

    // Cria sessão
    const sessionId = uuidv4();
    sessions.set(sessionId, {
      hostId: req.to,
      clientId: req.from,
      hostSocket: socket.id,
      clientSocket: req.fromSocket,
      startedAt: Date.now()
    });

    // Notifica ambos
    socket.emit('session-started', { sessionId, role: 'host' });
    io.to(req.fromSocket).emit('session-started', { sessionId, role: 'client' });

    console.log(`[Server] Sessão iniciada: ${sessionId}`);
  });

  // ── 4. Sinalização WebRTC (offer / answer / ice-candidate) ──────────────────
  socket.on('webrtc-signal', ({ sessionId, type, data }) => {
    const session = sessions.get(sessionId);
    if (!session) return;

    const isHost = session.hostSocket === socket.id;
    const targetSocket = isHost ? session.clientSocket : session.hostSocket;

    io.to(targetSocket).emit('webrtc-signal', { sessionId, type, data });
  });

  // ── 5. Encerrar sessão ───────────────────────────────────────────────────────
  socket.on('end-session', ({ sessionId }) => {
    const session = sessions.get(sessionId);
    if (!session) return;

    const otherSocket = session.hostSocket === socket.id
      ? session.clientSocket
      : session.hostSocket;

    io.to(otherSocket).emit('session-ended', { sessionId, reason: 'remote' });
    sessions.delete(sessionId);
    console.log(`[Server] Sessão encerrada: ${sessionId}`);
  });

  // ── 6. Desconexão ────────────────────────────────────────────────────────────
  socket.on('disconnect', () => {
    const deviceId = socket.data.deviceId;
    if (deviceId) {
      devices.delete(deviceId);
      console.log(`[Server] Dispositivo desconectado: ${deviceId}`);

      // Encerra sessões ativas desse dispositivo
      for (const [sessionId, session] of sessions) {
        if (session.hostSocket === socket.id || session.clientSocket === socket.id) {
          const otherSocket = session.hostSocket === socket.id
            ? session.clientSocket
            : session.hostSocket;
          io.to(otherSocket).emit('session-ended', { sessionId, reason: 'disconnect' });
          sessions.delete(sessionId);
        }
      }
    }
  });
});

// ─── Iniciar servidor ─────────────────────────────────────────────────────────
const PORT = process.env.PORT || 3000;
httpServer.listen(PORT, () => {
  console.log(`\n🚀 FaithDesk Mobile — Servidor rodando na porta ${PORT}`);
  console.log(`   Health: http://localhost:${PORT}/health`);
  console.log(`   ICE Servers: http://localhost:${PORT}/api/ice-servers\n`);
});

module.exports = { app, io };
