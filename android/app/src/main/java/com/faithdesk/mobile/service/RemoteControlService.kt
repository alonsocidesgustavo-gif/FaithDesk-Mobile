package com.faithdesk.mobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

/**
 * AccessibilityService para injetar eventos de toque no dispositivo host.
 *
 * TRANSPARÊNCIA TOTAL:
 *  - O usuário DEVE ativar manualmente em Configurações > Acessibilidade
 *  - A tela mostra claramente quando está ativa (indicador visual)
 *  - O usuário pode desativar a qualquer momento nas Configurações
 *  - Apenas executa ações quando uma sessão remota está autorizada
 *
 * APIs usadas:
 *  - dispatchGesture() — API oficial do Android (API 24+)
 *  - performGlobalAction() — para Back, Home, Recents
 */
class RemoteControlService : AccessibilityService() {

    private val TAG = "RemoteControlService"

    companion object {
        var instance: RemoteControlService? = null
            private set

        // Apenas executa se uma sessão estiver ativa
        var sessionActive = false
    }

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "RemoteControlService conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Não monitoramos eventos — apenas injetamos gestos quando solicitado
    }

    override fun onInterrupt() {
        Log.d(TAG, "RemoteControlService interrompido")
    }

    override fun onDestroy() {
        instance = null
        sessionActive = false
        super.onDestroy()
    }

    // ── Tap (toque simples) ───────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.N)
    fun performTap(x: Float, y: Float) {
        if (!sessionActive) return
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ── Swipe / Arrastar ──────────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.N)
    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        if (!sessionActive) return
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ── Long press ────────────────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.N)
    fun performLongPress(x: Float, y: Float) {
        if (!sessionActive) return
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ── Ações globais ─────────────────────────────────────────────────────────
    fun pressBack() { if (sessionActive) performGlobalAction(GLOBAL_ACTION_BACK) }
    fun pressHome() { if (sessionActive) performGlobalAction(GLOBAL_ACTION_HOME) }
    fun pressRecents() { if (sessionActive) performGlobalAction(GLOBAL_ACTION_RECENTS) }

    // ── Pinch / Zoom (dois dedos) ─────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.N)
    fun performPinch(
        centerX: Float, centerY: Float,
        startSpread: Float, endSpread: Float,
        duration: Long = 300
    ) {
        if (!sessionActive) return
        val path1 = Path().apply {
            moveTo(centerX - startSpread / 2, centerY)
            lineTo(centerX - endSpread / 2, centerY)
        }
        val path2 = Path().apply {
            moveTo(centerX + startSpread / 2, centerY)
            lineTo(centerX + endSpread / 2, centerY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, duration))
            .addStroke(GestureDescription.StrokeDescription(path2, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
