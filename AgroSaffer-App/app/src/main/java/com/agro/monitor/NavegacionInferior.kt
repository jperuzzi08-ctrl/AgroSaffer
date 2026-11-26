package com.agro.monitor

import android.content.Intent
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarMenuView

/**
 * Barra inferior compartida por las secciones principales.
 *
 * La pantalla principal es ResumenActivity: muestra todos los nodos.
 * El resto de las secciones se abren encima del resumen y, al saltar entre
 * ellas, la anterior se cierra. Así el botón "atrás" siempre vuelve al resumen
 * y no se apilan pantallas repetidas.
 */
object NavegacionInferior {

    fun configurar(activity: AppCompatActivity, @IdRes seccionActual: Int) {
        val nav = activity.findViewById<BottomNavigationView>(R.id.navInferior) ?: return

        nav.selectedItemId = seccionActual
        destacarInicio(nav)

        nav.setOnItemSelectedListener { item ->
            val destino: Class<*>? = when (item.itemId) {
                R.id.nav_inicio -> ResumenActivity::class.java
                R.id.nav_tendencias -> DetalleGraficoActivity::class.java
                R.id.nav_alertas -> HistorialActivity::class.java
                R.id.nav_ajustes -> ConfiguracionActivity::class.java
                else -> return@setOnItemSelectedListener false
            }

            if (destino != null && activity::class.java != destino) {
                val intent = Intent(activity, destino)
                if (destino == ResumenActivity::class.java) {
                    // Vuelve al resumen que ya está en la pila, sin crear otro.
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                activity.startActivity(intent)

                // El resumen es la raíz: se conserva debajo para que "atrás" vuelva a él.
                if (activity !is ResumenActivity) activity.finish()
            }
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)

            // La pantalla de destino marca su propia sección
            false
        }

        // Tocar la sección en la que ya estás no hace nada
        nav.setOnItemReselectedListener { }
    }

    /**
     * El ícono de Inicio tiene colores propios (círculo verde + casa negra).
     * BottomNavigationView tiñe todos los íconos con itemIconTint, así que a
     * este en particular se le quita el tinte después de inflar el menú.
     */
    private fun destacarInicio(nav: BottomNavigationView) {
        nav.post {
            val menuView = nav.getMenuView() as? NavigationBarMenuView ?: return@post
            menuView.findItemView(R.id.nav_inicio)?.setIconTintList(null)
        }
    }
}
