package com.agro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tarjeta "Promedio del campo": qué nodos entran en el promedio. */
class PromedioCampoTest {

    private val minuto = 60_000L

    private fun nodo(id: String, haceMinutos: Long?, tempAire: Double, hum15: Int = 30): NodoResumen {
        val millis = haceMinutos?.let { System.currentTimeMillis() - it * minuto }
        val lectura = LecturaData(tempAire = tempAire, hum15 = hum15, vpd = tempAire / 20)
        return NodoResumen(NodoInfo(id, true, millis), lectura, "", emptyList())
    }

    @Test
    fun promediaLosNodosConDatosRecientes() {
        val promedio = PromedioCampo.calcular(
            listOf(nodo("a", 2, 20.0, hum15 = 30), nodo("b", 40, 30.0, hum15 = 50))
        )!!
        assertEquals(25.0, promedio.tempAire, 0.001)
        assertEquals(40.0, promedio.hum15, 0.001)
        assertEquals(2, promedio.nodosIncluidos)
        assertEquals(0, promedio.nodosSinDatosRecientes)
        assertFalse(promedio.soloDatosViejos)
    }

    @Test
    fun unNodoApagadoHaceDiasNoDistorsionaElPromedio() {
        val promedio = PromedioCampo.calcular(
            listOf(nodo("a", 5, 20.0), nodo("b", 10, 22.0), nodo("viejo", 3 * 24 * 60, 45.0))
        )!!
        assertEquals(21.0, promedio.tempAire, 0.001)
        assertEquals(2, promedio.nodosIncluidos)
        assertEquals(1, promedio.nodosSinDatosRecientes)
    }

    @Test
    fun sinDatosRecientesUsaLasUltimasLecturasYLoAvisa() {
        val promedio = PromedioCampo.calcular(
            listOf(nodo("a", 120, 18.0), nodo("b", 300, 24.0))
        )!!
        assertEquals(21.0, promedio.tempAire, 0.001)
        assertEquals(2, promedio.nodosIncluidos)
        assertTrue(promedio.soloDatosViejos)
    }

    @Test
    fun sinLecturasNoHayPromedio() {
        val sinDatos = NodoResumen(NodoInfo("a", false, null), null, "", emptyList())
        assertNull(PromedioCampo.calcular(listOf(sinDatos)))
        assertNull(PromedioCampo.calcular(emptyList()))
    }
}
