package com.agro.monitor

import androidx.annotation.ColorRes
import com.google.firebase.database.DataSnapshot
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Estado de un nodo según hace cuánto envió su última lectura.
 * El color es el del círculo que se ve en los selectores de nodo.
 */
enum class EstadoNodo(@ColorRes val color: Int, val etiqueta: String) {
    EN_LINEA(R.color.sp_hoja, "En línea"),
    DEMORADO(R.color.sp_sol_oscuro, "Demorado"),
    SIN_SENAL(R.color.sp_terracota, "Sin señal"),
    DESCONOCIDO(R.color.sp_tinta_tenue, "Sin datos");

    companion object {
        /** Envió datos hace 15 min o menos: en línea. */
        const val EN_LINEA_MS = 15 * 60_000L

        /** Entre 15 y 60 min: demorado. Más de 60 min: sin señal. */
        const val DEMORADO_MS = 60 * 60_000L

        fun desde(millisUltimaLectura: Long?): EstadoNodo {
            val millis = millisUltimaLectura ?: return DESCONOCIDO
            val antiguedad = System.currentTimeMillis() - millis
            return when {
                antiguedad <= EN_LINEA_MS -> EN_LINEA
                antiguedad <= DEMORADO_MS -> DEMORADO
                else -> SIN_SENAL
            }
        }
    }
}

/** Resumen de un nodo para los selectores: si tiene lecturas y cuándo fue la última. */
data class NodoInfo(
    val id: String,
    val hayLecturas: Boolean,
    val millisUltima: Long?
) {
    val estado: EstadoNodo
        get() = if (hayLecturas) EstadoNodo.desde(millisUltima) else EstadoNodo.DESCONOCIDO

    /** Opción especial "Todos los nodos" (se usa en Alertas). */
    val esTodos: Boolean
        get() = id == TODOS

    /** Texto corto para la lista desplegable, p. ej. "En línea · hace 3 min". */
    fun detalle(): String = when {
        !hayLecturas -> "Sin lecturas todavía"
        millisUltima == null -> "Lecturas sin fecha"
        else -> "${estado.etiqueta} · hace ${Lecturas.textoMinutos(Lecturas.minutosDesde(millisUltima))}"
    }

    companion object {
        /** Identificador de la opción "Todos los nodos". */
        const val TODOS = "__todos__"

        val OPCION_TODOS = NodoInfo(TODOS, hayLecturas = false, millisUltima = null)

        /** Arma la lista de nodos a partir del nodo "lecturas" de Firebase. */
        fun desdeSnapshot(lecturas: DataSnapshot): List<NodoInfo> =
            lecturas.children.mapNotNull { nodo ->
                val id = nodo.key ?: return@mapNotNull null
                val ultima = Lecturas.ultima(nodo)
                NodoInfo(id, ultima != null, ultima?.let { Lecturas.millis(it) })
            }
    }
}

/** Utilidades para interpretar lecturas de Firebase. */
object Lecturas {

    /** Última lectura de un nodo: Firebase entrega los hijos ordenados por clave. */
    fun ultima(nodo: DataSnapshot): DataSnapshot? = nodo.children.lastOrNull()

    /**
     * Momento de la lectura. Acepta:
     *  - timestamp numérico en milisegundos o en segundos (como suele mandar el nodo),
     *  - timestamp/fecha como texto ISO o "yyyy-MM-dd HH:mm:ss",
     *  - la propia clave de Firebase si tiene formato de fecha.
     */
    fun millis(lectura: DataSnapshot): Long? {
        parseTimestamp(lectura.child("timestamp").value)?.let { return it }
        parseFecha(lectura.child("fecha").value?.toString())?.let { return it }
        parseFecha(lectura.key)?.let { return it }
        return null
    }

    private fun parseTimestamp(valor: Any?): Long? {
        val texto = valor?.toString()?.trim().orEmpty()
        if (texto.isEmpty()) return null
        val numero = texto.toDoubleOrNull()
        if (numero != null) {
            val entero = numero.toLong()
            return when {
                entero > 100_000_000_000L -> entero          // milisegundos
                entero > 100_000_000L -> entero * 1000L     // segundos
                else -> null
            }
        }
        return parseFecha(texto)
    }

    private fun parseFecha(texto: String?): Long? {
        val limpio = texto?.trim().orEmpty()
        if (limpio.isEmpty()) return null
        for (patron in FORMATOS_FECHA) {
            try {
                val formato = SimpleDateFormat(patron, Locale.US).apply { isLenient = false }
                formato.parse(limpio)?.let { return it.time }
            } catch (_: Exception) {
                // Se prueba el siguiente formato
            }
        }
        return null
    }

    private val FORMATOS_FECHA = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yyyy HH:mm",
        "yyyy/MM/dd HH:mm:ss"
    )

    fun minutosDesde(millis: Long?): Long? =
        millis?.let { ((System.currentTimeMillis() - it) / 60_000).coerceAtLeast(0) }

    fun textoMinutos(minutos: Long?): String = when {
        minutos == null -> "un rato"
        minutos < 1 -> "menos de 1 min"
        minutos < 60 -> "$minutos min"
        minutos < 60 * 24 -> "${minutos / 60} h ${minutos % 60} min"
        else -> "${minutos / (60 * 24)} días"
    }
}
