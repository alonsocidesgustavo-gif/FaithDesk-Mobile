package com.faithdesk.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class FaithDeskApp : Application() {

    companion object {
        const val CHANNEL_SESSION = "faithdesk_session"
        const val CHANNEL_REQUESTS = "faithdesk_requests"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Canal de sessão ativa
            NotificationChannel(
                CHANNEL_SESSION,
                "Sessão Ativa",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indica que uma sessão remota está em andamento"
                manager.createNotificationChannel(this)
            }

            // Canal de solicitações de conexão
            NotificationChannel(
                CHANNEL_REQUESTS,
                "Solicitações de Conexão",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificação de nova solicitação de acesso remoto"
                manager.createNotificationChannel(this)
            }
        }
    }
}
