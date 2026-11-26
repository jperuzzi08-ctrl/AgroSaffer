package com.agro.monitor

import java.util.Calendar

/** Saludo del banner con el paisaje, según la hora (lo usan Tu campo y el panel del nodo). */
fun saludoSegunHora(): String {
    return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Buen día"
        in 12..19 -> "Buenas tardes"
        else -> "Buenas noches"
    }
}
