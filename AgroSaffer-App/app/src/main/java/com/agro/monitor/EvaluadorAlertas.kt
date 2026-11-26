package com.agro.monitor

import com.google.firebase.database.DataSnapshot

/**
 * Umbrales agronómicos configurados en Ajustes.
 * Si un valor no está configurado queda en null y esa regla no se evalúa,
 * igual que hacía el panel de Inicio.
 */
data class UmbralesAgronomicos(
    val tempMax: Double? = null,
    val tempMin: Double? = null,
    val vpdMax: Double? = null,
    val vpdMin: Double? = null,
    val humSueloMin: Double? = null,
    val tempRadicularMin: Double? = null,
    val tempRadicularMax: Double? = null
) {
    companion object {
        fun desde(snapshot: DataSnapshot): UmbralesAgronomicos = UmbralesAgronomicos(
            tempMax = snapshot.child("tempMax").value?.toString()?.toDoubleOrNull(),
            tempMin = snapshot.child("tempMin").value?.toString()?.toDoubleOrNull(),
            vpdMax = snapshot.child("vpdMax").value?.toString()?.toDoubleOrNull(),
            vpdMin = snapshot.child("vpdMin").value?.toString()?.toDoubleOrNull(),
            humSueloMin = snapshot.child("humSueloMin").value?.toString()?.toDoubleOrNull(),
            tempRadicularMin = snapshot.child("tempRadicularMin").value?.toString()?.toDoubleOrNull(),
            tempRadicularMax = snapshot.child("tempRadicularMax").value?.toString()?.toDoubleOrNull()
        )
    }
}

/**
 * Evalúa una lectura contra los umbrales del cultivo.
 * La comparten el panel del nodo, el resumen de todos los nodos y la exportación,
 * para que todos muestren exactamente las mismas alertas.
 */
object EvaluadorAlertas {

    fun evaluar(idNodo: String, data: LecturaData, umbrales: UmbralesAgronomicos): List<AlertaActiva> {
        val alertas = mutableListOf<AlertaActiva>()

        fun agregar(tipo: String, titulo: String, mensaje: String) {
            alertas.add(AlertaActiva(tipo, titulo, mensaje))
        }

        umbrales.tempMax?.let { max ->
            if (data.tempAire > max) {
                agregar(
                    "CALOR",
                    "🚨 Alerta de Golpe de Calor",
                    "Temp. Aire en $idNodo de ${data.tempAire}°C (> $max°C). Riesgo de aborto de flores."
                )
            }
        }
        umbrales.tempMin?.let { min ->
            if (data.tempAire < min) {
                agregar(
                    "HELADA",
                    "❄️ Alerta Temprana de Helada",
                    "Temp. Aire en $idNodo cayó a ${data.tempAire}°C (< $min°C). Activar protección."
                )
            }
        }

        umbrales.humSueloMin?.let { minHum ->
            if (data.hum15 < minHum) {
                agregar(
                    "HUMEDAD_SUELO",
                    "💧 Alerta Humedad Crítica",
                    "Humedad 15cm en $idNodo cayó a ${data.hum15}% (< $minHum%). Riego urgente requerido."
                )
            }
        }

        umbrales.vpdMax?.let { maxVpd ->
            if (data.vpd > maxVpd) {
                agregar(
                    "VPD_ALTO",
                    "☀️ Estrés Hídrico (VPD)",
                    "VPD en $idNodo alcanzó ${data.vpd} kPa (> $maxVpd kPa). Cierre estomático activo."
                )
            }
        }

        umbrales.vpdMin?.let { minVpd ->
            if (data.vpd < minVpd && data.humAire > 90.0) {
                agregar(
                    "FUNGICO",
                    "🍄 Alerta Sanitaria Fúngica",
                    "VPD en $idNodo es ${data.vpd} kPa y humedad ${data.humAire}%. Monitorear hongos."
                )
            }
        }

        umbrales.tempRadicularMin?.let { minRad ->
            if (data.tempSuelo < minRad) {
                agregar(
                    "RAIZ_FRIA",
                    "🥶 Bloqueo Nutricional",
                    "Temp. de raíz en $idNodo es ${data.tempSuelo}°C (< $minRad°C). Asimilación suspendida."
                )
            }
        }

        umbrales.tempRadicularMax?.let { maxRad ->
            if (data.tempSuelo > maxRad) {
                agregar(
                    "RAIZ_CALIENTE",
                    "🔥 Calentamiento Radicular",
                    "Temp. de raíz en $idNodo es ${data.tempSuelo}°C (> $maxRad°C). Riesgo de anoxia."
                )
            }
        }

        if (data.bateria in 0.1..3.4) {
            agregar(
                "BATERIA",
                "🔋 Alerta de Hardware / Batería",
                "Tensión en $idNodo bajo nivel crítico (${data.bateria}V). Limpiar panel solar."
            )
        }
        return alertas
    }
}
