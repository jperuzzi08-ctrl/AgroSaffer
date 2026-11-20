package com.agro.monitor

import android.content.Intent
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Barra inferior compartida por las secciones principales.
 *
 * Inicio (MainActivity) es siempre la raíz: las demás secciones se abren encima
 * y, al saltar entre ellas, la anterior se cierra. Así el botón "atrás" siempre
 * vuelve a Inicio y no se apilan pantallas repetidas.
 */
object NavegacionInferior {

    fun configurar(activity: AppCompatActivity, @IdRes seccionActual: Int) {
        val nav = activity.findViewById<BottomNavigationView>(R.id.navInferior) ?: return

        nav.selectedItemId = seccionActual

        nav.setOnItemSelectedListener { item ->
            val destino: Class<*>? = when (item.itemId) {
                R.id.nav_inicio -> null // Inicio ya está debajo: alcanza con cerrar la sección
                R.id.nav_tendencias -> DetalleGraficoActivity::class.java
                R.id.nav_alertas -> HistorialActivity::class.java
                R.id.nav_ajustes -> ConfiguracionActivity::class.java
                else -> return@setOnItemSelectedListener false
            }

            destino?.let { activity.startActivity(Intent(activity, it)) }
            if (activity !is MainActivity) activity.finish()
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)

            // La pantalla de destino marca su propia sección
            false
        }

        // Tocar la sección en la que ya estás no hace nada
        nav.setOnItemReselectedListener { }
    }
}
