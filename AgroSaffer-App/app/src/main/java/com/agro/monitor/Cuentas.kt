package com.agro.monitor

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

/** Cuenta que ya inició sesión en este teléfono (no guarda contraseñas). */
data class CuentaGuardada(
    val uid: String,
    val email: String,
    val ultimoIngreso: Long
) {
    /** Letra para el avatar circular. */
    val inicial: String
        get() = email.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/**
 * Cuentas guardadas en el dispositivo y datos locales separados por cuenta.
 *
 * - Al iniciar sesión, la cuenta queda guardada (correo + identificador) para volver
 *   a entrar con un toque: solo se pide la contraseña, que NO se guarda.
 * - El historial de alertas, las alertas activas y el nodo elegido se guardan por
 *   cuenta, así al cambiar de cuenta no se mezclan.
 */
object Cuentas {

    private const val ARCHIVO_CUENTAS = "CUENTAS_GUARDADAS"
    private const val CLAVE_LISTA = "lista"
    private const val MAX_CUENTAS = 5

    /** Archivos de preferencias que pertenecen a cada cuenta. */
    const val PREFS_HISTORIAL = "HISTORIAL_PREFS"
    const val PREFS_ALERTAS_ACTIVAS = "ALERTAS_ACTIVAS"
    const val PREFS_LECTURAS_EVALUADAS = "LECTURAS_EVALUADAS"
    const val PREFS_CUENTA = "PREFERENCIAS_CUENTA"
    private val ARCHIVOS_POR_CUENTA = listOf(
        PREFS_HISTORIAL, PREFS_ALERTAS_ACTIVAS, PREFS_LECTURAS_EVALUADAS, PREFS_CUENTA
    )

    private fun archivoCuentas(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(ARCHIVO_CUENTAS, Context.MODE_PRIVATE)

    // ===================== Cuentas guardadas =====================

    /** Cuentas guardadas, la usada más recientemente primero. */
    fun guardadas(ctx: Context): List<CuentaGuardada> {
        val texto = archivoCuentas(ctx).getString(CLAVE_LISTA, "").orEmpty()
        return texto.lines().mapNotNull { linea ->
            val partes = linea.split("\t")
            if (partes.size == 3) {
                CuentaGuardada(partes[0], partes[1], partes[2].toLongOrNull() ?: 0L)
            } else {
                null
            }
        }.sortedByDescending { it.ultimoIngreso }
    }

    private fun escribir(ctx: Context, cuentas: List<CuentaGuardada>) {
        val texto = cuentas.joinToString("\n") { "${it.uid}\t${it.email}\t${it.ultimoIngreso}" }
        archivoCuentas(ctx).edit().putString(CLAVE_LISTA, texto).apply()
    }

    /** Guarda (o actualiza) la cuenta con la que se acaba de entrar. */
    fun recordar(ctx: Context, usuario: FirebaseUser) {
        val email = usuario.email ?: return
        val nueva = CuentaGuardada(usuario.uid, email, System.currentTimeMillis())
        val resto = guardadas(ctx).filter { it.uid != usuario.uid }
        escribir(ctx, (listOf(nueva) + resto).take(MAX_CUENTAS))
    }

    /**
     * Quita la cuenta de este teléfono y borra sus datos locales (historial de
     * alertas y preferencias). No borra la cuenta real ni los datos de Firebase.
     */
    fun olvidar(ctx: Context, uid: String) {
        escribir(ctx, guardadas(ctx).filter { it.uid != uid })
        for (base in ARCHIVOS_POR_CUENTA) {
            ctx.applicationContext.deleteSharedPreferences(nombreArchivo(base, uid))
        }
    }

    fun usuarioActual(): FirebaseUser? = FirebaseAuth.getInstance().currentUser

    /** Cierra la sesión de Firebase y quita la notificación de alertas de esa cuenta. */
    fun cerrarSesion(ctx: Context) {
        AlertasActivas.quitarNotificacion(ctx)
        FirebaseAuth.getInstance().signOut()
    }

    // ===================== Datos locales por cuenta =====================

    private fun nombreArchivo(base: String, uid: String) = "${base}__$uid"

    /**
     * Preferencias de [base] para la cuenta con sesión iniciada.
     * La primera vez, los datos de versiones anteriores (sin separar por cuenta)
     * pasan a la cuenta que esté usando la app.
     */
    fun prefs(ctx: Context, base: String): SharedPreferences {
        val app = ctx.applicationContext
        val uid = usuarioActual()?.uid ?: return app.getSharedPreferences(base, Context.MODE_PRIVATE)
        val propias = app.getSharedPreferences(nombreArchivo(base, uid), Context.MODE_PRIVATE)
        migrarDatosAnteriores(app, base, propias)
        return propias
    }

    private fun migrarDatosAnteriores(ctx: Context, base: String, destino: SharedPreferences) {
        val anteriores = ctx.getSharedPreferences(base, Context.MODE_PRIVATE)
        val datos = anteriores.all
        if (datos.isEmpty()) return
        if (destino.all.isEmpty()) {
            val editor = destino.edit()
            for ((clave, valor) in datos) {
                when (valor) {
                    is String -> editor.putString(clave, valor)
                    is Long -> editor.putLong(clave, valor)
                    is Int -> editor.putInt(clave, valor)
                    is Boolean -> editor.putBoolean(clave, valor)
                    is Float -> editor.putFloat(clave, valor)
                    is Set<*> -> editor.putStringSet(clave, valor.filterIsInstance<String>().toSet())
                }
            }
            editor.apply()
        }
        anteriores.edit().clear().apply()
    }
}
