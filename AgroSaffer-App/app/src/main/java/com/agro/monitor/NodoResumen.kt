package com.agro.monitor

import com.google.firebase.database.DataSnapshot

/**
 * Promedio de las últimas mediciones de todos los nodos (tarjeta "Promedio del campo").
 *
 * Solo promedia nodos con datos recientes (en línea o demorados), para que un nodo
 * apagado hace días no distorsione el resultado. Si ninguno tiene datos recientes,
 * usa las últimas lecturas de todos y lo indica con [soloDatosViejos].
 */
data class PromedioCampo(
    val vpd: Double,
    val tempAire: Double,
    val humAire: Double,
    val hum15: Double,
    val hum40: Double,
    val tempSuelo: Double,
    val nodosIncluidos: Int,
    val nodosSinDatosRecientes: Int,
    val soloDatosViejos: Boolean
) {
    companion object {
        fun calcular(nodos: List<NodoResumen>): PromedioCampo? {
            val conDatos = nodos.filter { it.lectura != null }
            if (conDatos.isEmpty()) return null
            val recientes = conDatos.filter {
                it.info.estado == EstadoNodo.EN_LINEA || it.info.estado == EstadoNodo.DEMORADO
            }
            val lecturas = recientes.ifEmpty { conDatos }.mapNotNull { it.lectura }
            fun promedio(valor: (LecturaData) -> Double) = lecturas.map(valor).average()
            return PromedioCampo(
                vpd = promedio { it.vpd },
                tempAire = promedio { it.tempAire },
                humAire = promedio { it.humAire },
                hum15 = promedio { it.hum15.toDouble() },
                hum40 = promedio { it.hum40.toDouble() },
                tempSuelo = promedio { it.tempSuelo },
                nodosIncluidos = lecturas.size,
                nodosSinDatosRecientes = conDatos.size - recientes.size,
                soloDatosViejos = recientes.isEmpty()
            )
        }
    }
}

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
