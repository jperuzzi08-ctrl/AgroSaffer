package com.agro.monitor

import com.google.firebase.database.DataSnapshot

/**
 * Todo lo que necesita una tarjeta del resumen principal: estado del nodo,
 * última lectura, subtítulo local y alertas agronómicas activas.
 */
data class NodoResumen(
    val info: NodoInfo,
    val lectura: LecturaData?,
    val subtitulo: String,
    val alertas: List<AlertaActiva>
) {
    val hayProblema: Boolean
        get() = alertas.isNotEmpty() || info.estado != EstadoNodo.EN_LINEA

    companion object {
        fun desde(
            nodo: DataSnapshot,
            subtitulo: String,
            umbrales: UmbralesAgronomicos
        ): NodoResumen? {
            val id = nodo.key ?: return null
            val ultima = Lecturas.ultima(nodo)
            val info = NodoInfo(id, ultima != null, ultima?.let { Lecturas.millis(it) })
            val lectura = ultima?.let { LecturaData.desde(it) }
            val alertas = lectura?.let { EvaluadorAlertas.evaluar(id, it, umbrales) }.orEmpty()
            return NodoResumen(info, lectura, subtitulo, alertas)
        }
    }
}
