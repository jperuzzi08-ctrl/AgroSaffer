package com.agro.monitor

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * Preferencias locales de la app: modo de apariencia (día / noche / automático),
 * último nodo elegido y subtítulos de los nodos (guardados por cuenta).
 */
object PreferenciasApp {

    private const val ARCHIVO = "AJUSTES_APP"
    private const val CLAVE_MODO_TEMA = "modo_tema"
    private const val CLAVE_NODO = "nodo_actual"

    const val TEMA_CLARO = AppCompatDelegate.MODE_NIGHT_NO
    const val TEMA_NOCHE = AppCompatDelegate.MODE_NIGHT_YES
    const val TEMA_AUTO = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    // ---------- Apariencia ----------

    fun modoTema(ctx: Context): Int = prefs(ctx).getInt(CLAVE_MODO_TEMA, TEMA_CLARO)

    /** Se llama al iniciar la app para respetar la elección guardada. */
    fun aplicarTemaGuardado(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(modoTema(ctx))
    }

    /** Guarda y aplica el modo; AppCompat recrea las pantallas abiertas. */
    fun cambiarTema(ctx: Context, modo: Int) {
        if (modo == modoTema(ctx) && modo == AppCompatDelegate.getDefaultNightMode()) return
        prefs(ctx).edit().putInt(CLAVE_MODO_TEMA, modo).apply()
        AppCompatDelegate.setDefaultNightMode(modo)
    }

    fun esNocheActiva(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Botón rápido del panel: pasa de día a noche y viceversa. */
    fun alternarTema(ctx: Context) {
        cambiarTema(ctx, if (esNocheActiva(ctx)) TEMA_CLARO else TEMA_NOCHE)
    }

    // ---------- Subtítulos de los nodos ----------
    // Se guardan solo en este teléfono, separados por cuenta. No se suben a Firebase.

    private const val CLAVE_SUBTITULO = "subtitulo_nodo__"

    /** Texto corto que el usuario eligió para identificar el nodo (puede estar vacío). */
    fun subtituloNodo(ctx: Context, nodo: String): String =
        Cuentas.prefs(ctx, Cuentas.PREFS_CUENTA)
            .getString(CLAVE_SUBTITULO + nodo, null)
            .orEmpty()

    /** Guarda el subtítulo; si queda vacío, lo elimina. */
    fun guardarSubtituloNodo(ctx: Context, nodo: String, subtitulo: String) {
        val prefs = Cuentas.prefs(ctx, Cuentas.PREFS_CUENTA)
        val limpio = subtitulo.trim().take(40)
        if (limpio.isEmpty()) {
            prefs.edit().remove(CLAVE_SUBTITULO + nodo).apply()
        } else {
            prefs.edit().putString(CLAVE_SUBTITULO + nodo, limpio).apply()
        }
    }

    // ---------- Nodo seleccionado ----------

    fun nodoActual(ctx: Context): String? =
        Cuentas.prefs(ctx, Cuentas.PREFS_CUENTA).getString(CLAVE_NODO, null)
            ?: prefs(ctx).getString(CLAVE_NODO, null) // valor de versiones anteriores

    fun guardarNodoActual(ctx: Context, nodo: String) {
        Cuentas.prefs(ctx, Cuentas.PREFS_CUENTA).edit().putString(CLAVE_NODO, nodo).apply()
        prefs(ctx).edit().remove(CLAVE_NODO).apply()
    }
}
