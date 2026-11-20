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

    /** Momento de la lectura: campo "timestamp" (ms o s), "fecha" o la propia clave. */
    fun millis(lectura: DataSnapshot): Long? {
        lectura.child("timestamp").value?.toString()?.toLongOrNull()?.let { ts ->
            return if (ts > 100_000_000_000L) ts else ts * 1000
        }
        val formato = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val candidatos = listOfNotNull(lectura.child("fecha").value?.toString(), lectura.key)
        for (texto in candidatos) {
            try {
                formato.parse(texto)?.let { return it.time }
            } catch (_: Exception) {
                // No es una fecha legible; se prueba el siguiente candidato
            }
        }
        return null
    }

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
