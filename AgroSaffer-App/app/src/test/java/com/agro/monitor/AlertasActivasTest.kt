package com.agro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simula lecturas sucesivas de un nodo y cuenta cuántas veces sonaría la notificación.
 * Antes del arreglo cada lectura fuera de rango generaba una notificación nueva.
 */
class AlertasActivasTest {

    private val minuto = 60_000L
    private val calor = AlertaActiva("CALOR", "🚨 Alerta de Golpe de Calor", "Temp. Aire en nodo1 de 36°C")
    private val humedad = AlertaActiva("HUMEDAD_SUELO", "💧 Alerta Humedad Crítica", "Humedad 15cm en nodo1 cayó a 12%")

    /** Aplica una secuencia de (momento, alertas activas) y devuelve cuántos anuncios hubo. */
    private fun simular(lecturas: List<Pair<Long?, List<AlertaActiva>>>): List<List<String>> {
        val vistos = mutableMapOf<String, Long>()
        return lecturas.map { (momento, alertas) ->
            val (nuevas, actualizados) = AlertasActivas.decidirNuevas(alertas, vistos, momento)
            vistos.putAll(actualizados)
            nuevas.map { it.tipo }
        }
    }

    @Test
    fun calorSostenidoSuenaUnaSolaVez() {
        // 24 lecturas cada 5 minutos (2 horas) con la temperatura siempre alta
        val lecturas = (0 until 24).map { i -> (i * 5 * minuto) to listOf(calor) }
        val anuncios = simular(lecturas).flatten()
        assertEquals(listOf("CALOR"), anuncios)
    }

    @Test
    fun valorQueOscilaEnElLimiteNoSpamea() {
        // Entra y sale del rango en cada lectura durante 2 horas
        val lecturas = (0 until 24).map { i ->
            (i * 5 * minuto) to (if (i % 2 == 0) listOf(calor) else emptyList())
        }
        assertEquals(1, simular(lecturas).flatten().size)
    }

    @Test
    fun vuelveASonarSiReapareceDespuesDeMediaHoraNormal() {
        val lecturas = listOf(
            0L to listOf(calor),
            5 * minuto to emptyList(),
            50 * minuto to listOf(calor) // 50 min después de la última vez visto
        )
        assertEquals(listOf(listOf("CALOR"), emptyList(), listOf("CALOR")), simular(lecturas))
    }

    @Test
    fun mismaLecturaReentregadaNoVuelveASonar() {
        // Cambiar de nodo, reabrir la app o cambiar de tema reentrega la misma lectura
        val lecturas = listOf(
            100 * minuto to listOf(calor),
            100 * minuto to listOf(calor),
            100 * minuto to listOf(calor)
        )
        assertEquals(1, simular(lecturas).flatten().size)
    }

    @Test
    fun lecturaSinFechaYaEvaluadaNoAnuncia() {
        val lecturas = listOf(
            null to listOf(calor),
            null to listOf(calor, humedad)
        )
        assertTrue(simular(lecturas).flatten().isEmpty())
    }

    @Test
    fun soloSuenaLaAlertaQueAparecePorPrimeraVez() {
        val lecturas = listOf(
            0L to listOf(calor),
            5 * minuto to listOf(calor, humedad),
            10 * minuto to listOf(calor, humedad)
        )
        assertEquals(listOf(listOf("CALOR"), listOf("HUMEDAD_SUELO"), emptyList()), simular(lecturas))
    }

    @Test
    fun lecturaViejaDespuesDeUnaNuevaNoAnuncia() {
        // Si llega una lectura anterior a la última vista, no cuenta como nueva
        val lecturas = listOf(
            60 * minuto to listOf(calor),
            10 * minuto to listOf(calor)
        )
        assertEquals(1, simular(lecturas).flatten().size)
    }
}
