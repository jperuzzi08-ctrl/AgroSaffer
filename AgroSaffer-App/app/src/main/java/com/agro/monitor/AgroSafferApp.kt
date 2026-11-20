package com.agro.monitor

import android.app.Application

class AgroSafferApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Aplica el modo día / noche elegido antes de abrir cualquier pantalla
        PreferenciasApp.aplicarTemaGuardado(this)
    }
}
