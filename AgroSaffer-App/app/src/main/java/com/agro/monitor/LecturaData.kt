package com.agro.monitor

import com.google.firebase.database.DataSnapshot

data class LecturaData(
    val hum15: Int = 0,
    val hum40: Int = 0,
    val humAire: Double = 0.0,
    val tempAire: Double = 0.0,
    val tempSuelo: Double = 0.0,
    val bateria: Double = 0.0,
    val vpd: Double = 0.0,
    val presion: Double = 0.0,
    val fecha: String = ""
) {
    companion object {
        /** Arma una lectura a partir de uno de los hijos de "lecturas". */
        fun desde(lectura: DataSnapshot): LecturaData = LecturaData(
            hum15 = lectura.child("hum15").value?.toString()?.toDoubleOrNull()?.toInt() ?: 0,
            hum40 = lectura.child("hum40").value?.toString()?.toDoubleOrNull()?.toInt() ?: 0,
            humAire = lectura.child("humAire").value?.toString()?.toDoubleOrNull() ?: 0.0,
            tempAire = lectura.child("tempAire").value?.toString()?.toDoubleOrNull() ?: 0.0,
            tempSuelo = lectura.child("tempSuelo").value?.toString()?.toDoubleOrNull() ?: 0.0,
            bateria = lectura.child("bateria").value?.toString()?.toDoubleOrNull() ?: 0.0,
            vpd = lectura.child("vpd").value?.toString()?.toDoubleOrNull() ?: 0.0,
            presion = lectura.child("presion").value?.toString()?.toDoubleOrNull() ?: 0.0,
            fecha = lectura.child("fecha").value?.toString().orEmpty()
        )
    }
}
