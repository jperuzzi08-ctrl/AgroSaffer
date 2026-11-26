package com.agro.monitor

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.chip.ChipGroup
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class DetalleGraficoActivity : AppCompatActivity() {

    private lateinit var lineChart: LineChart
    private lateinit var tvPromedio: TextView
    private lateinit var tvValorActual: TextView
    private lateinit var tvRango: TextView
    private lateinit var tvTitulo: TextView
    private lateinit var dbRef: DatabaseReference

    private var variableActual: String = "vpd"
    private var horasFiltro: Int = 24
    private var nodoIdActual: String? = null
    private var markerView: CustomMarkerView? = null

    private lateinit var spinnerNodo: Spinner
    private lateinit var adaptadorNodos: AdaptadorNodos
    private var nodosListener: ValueEventListener? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detalle_grafico)

        // Se llega tocando una tarjeta del panel (con variable) o desde la barra inferior (sin extras)
        val abiertaDesdeTarjeta = intent.hasExtra("VARIABLE")
        variableActual = savedInstanceState?.getString(ESTADO_VARIABLE)
            ?: intent.getStringExtra("VARIABLE")
            ?: "vpd"
        nodoIdActual = savedInstanceState?.getString(ESTADO_NODO)
            ?: intent.getStringExtra("NODO_ID")
            ?: PreferenciasApp.nodoActual(this)

        tvTitulo = findViewById(R.id.tvTituloGrafico)
        tvTitulo.text = nombreVariable(variableActual)
        findViewById<View>(R.id.btnVolver).apply {
            visibility = if (abiertaDesdeTarjeta) View.VISIBLE else View.GONE
            setOnClickListener { finish() }
        }
        findViewById<View>(R.id.selectorVariable).setOnClickListener { mostrarSelectorVariable(it) }

        lineChart = findViewById(R.id.lineChart)
        tvPromedio = findViewById(R.id.tvPromedio)
        tvValorActual = findViewById(R.id.tvValorActual)
        tvRango = findViewById(R.id.tvRango)

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "DdaAJqXxIcadkzamNl16yWyy8k13"
        dbRef = FirebaseDatabase.getInstance().getReference("usuarios").child(uid).child("lecturas")

        configurarGestosGrafico()
        configurarChipsRango()
        configurarSelectorNodo()
        NavegacionInferior.configurar(this, R.id.nav_tendencias)

        if (nodoIdActual.isNullOrEmpty()) {
            lineChart.setNoDataText("Cargando nodos…")
        }

        runnable = object : Runnable {
            override fun run() {
                cargarDatosDesdeFirebase()
                adaptadorNodos.refrescarEstados()
                handler.postDelayed(this, 10000)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Se actualiza solo mientras la pantalla está visible
        handler.removeCallbacks(runnable)
        handler.post(runnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(runnable)
    }

    /**
     * Selector de nodo con el mismo desplegable que Inicio: cada nodo con su
     * círculo de estado. El nodo elegido acá también queda elegido en Inicio.
     */
    private fun configurarSelectorNodo() {
        spinnerNodo = findViewById(R.id.spinnerNodoGrafico)
        adaptadorNodos = AdaptadorNodos(
            this,
            R.layout.item_nodo_chip,
            subtituloDe = { PreferenciasApp.subtituloNodo(this, it.id) }
        )
        spinnerNodo.adapter = adaptadorNodos
        spinnerNodo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                cambiarNodo(adaptadorNodos.getItem(position).id)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        nodosListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val nodos = NodoInfo.desdeSnapshot(snapshot)
                if (nodos.isEmpty()) {
                    lineChart.setNoDataText("No hay nodos asignados a esta cuenta")
                    lineChart.invalidate()
                    return
                }
                adaptadorNodos.actualizar(nodos)
                spinnerNodo.visibility = View.VISIBLE

                val posicion = adaptadorNodos.posicionDe(nodoIdActual)
                if (posicion >= 0) {
                    if (spinnerNodo.selectedItemPosition != posicion) {
                        spinnerNodo.setSelection(posicion, false)
                    }
                } else {
                    // Sin nodo elegido (o ya no existe): se toma el primero
                    spinnerNodo.setSelection(0, false)
                    cambiarNodo(nodos[0].id)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("DetalleGrafico", "Error al cargar nodos: ${error.message}")
            }
        }
        dbRef.addValueEventListener(nodosListener as ValueEventListener)
    }

    private fun cambiarNodo(nodo: String) {
        if (nodo == nodoIdActual) return
        nodoIdActual = nodo
        PreferenciasApp.guardarNodoActual(this, nodo)

        // Limpia lo del nodo anterior mientras llegan los datos del nuevo
        lineChart.setNoDataText("Sin mediciones en este rango 🌱")
        lineChart.fitScreen()
        lineChart.clear()
        tvValorActual.text = "--"
        tvPromedio.text = "--"
        tvRango.text = "--"
        cargarDatosDesdeFirebase()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ESTADO_NODO, nodoIdActual)
        outState.putString(ESTADO_VARIABLE, variableActual)
    }

    private fun nombreVariable(clave: String): String =
        VARIABLES.firstOrNull { it.first == clave }?.second ?: "Tendencias"

    /** Menú desplegable del título para cambiar la variable graficada. */
    private fun mostrarSelectorVariable(ancla: View) {
        val menu = PopupMenu(this, ancla)
        VARIABLES.forEachIndexed { indice, (_, nombre) ->
            menu.menu.add(0, indice, indice, nombre)
        }
        menu.setOnMenuItemClickListener { item ->
            val (clave, nombre) = VARIABLES[item.itemId]
            if (clave != variableActual) {
                variableActual = clave
                tvTitulo.text = nombre
                lineChart.fitScreen()
                cargarDatosDesdeFirebase()
            }
            true
        }
        menu.show()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun configurarGestosGrafico() {
        lineChart.apply {
            // --- HABILITAR INTERACCIÓN TÁCTIL Y DESPLAZAMIENTO ---
            setTouchEnabled(true)
            isDragEnabled = true           // Arrastrar con un dedo (Scroll horizontal)
            isScaleXEnabled = true         // Zoom horizontal
            isScaleYEnabled = false        // Escala Y fija para mantener claridad en amplitudes
            setPinchZoom(true)             // Zoom con dos dedos (Pinch-to-zoom)
            isDoubleTapToZoomEnabled = true

            // Inercia suave al deslizar
            isDragDecelerationEnabled = true
            dragDecelerationFrictionCoef = 0.9f

            setBackgroundColor(Color.TRANSPARENT)
            setDrawGridBackground(false)
            description.isEnabled = false
            legend.isEnabled = false
            axisRight.isEnabled = false
            setExtraOffsets(4f, 8f, 4f, 6f)

            setNoDataText("Sin mediciones en este rango 🌱")
            setNoDataTextColor(color(R.color.sp_tinta_suave))
            setNoDataTextTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))

            axisLeft.apply {
                textColor = color(R.color.sp_tinta_suave)
                textSize = 10f
                setDrawGridLines(true)
                gridColor = color(R.color.sp_borde)
                gridLineWidth = 1f
                enableGridDashedLine(8f, 6f, 0f)
                setDrawAxisLine(false)
                setLabelCount(5, false)
            }

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = color(R.color.sp_tinta_suave)
                textSize = 10f
                setDrawGridLines(false)
                setDrawAxisLine(false)
                setAvoidFirstLastClipping(true)
                yOffset = 8f
            }
        }
    }

    private fun configurarChipsRango() {
        val chips = findViewById<ChipGroup>(R.id.chipsRango)
        chips.setOnCheckedStateChangeListener { _, checkedIds ->
            horasFiltro = when (checkedIds.firstOrNull()) {
                R.id.chip1h -> 1
                R.id.chip6h -> 6
                R.id.chip24h -> 24
                R.id.chip7d -> 168
                R.id.chip30d -> 720
                R.id.chip6m -> 4320
                R.id.chip1a -> 8760
                else -> 24
            }
            cargarDatosDesdeFirebase()
        }
    }

    private fun cargarDatosDesdeFirebase() {
        val idNodo = nodoIdActual ?: return

        dbRef.child(idNodo).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val ahoraMillis = System.currentTimeMillis()
                val limiteTiempoMillis = ahoraMillis - (horasFiltro * 3600 * 1000L)
                val hijos = snapshot.children.toList()

                // Lecturas con su momento real (acepta segundos, milisegundos o fecha)
                val conFecha = hijos.mapNotNull { child ->
                    val valor = child.child(variableActual).value?.toString()?.toDoubleOrNull()
                        ?: return@mapNotNull null
                    val millis = Lecturas.millis(child) ?: return@mapNotNull null
                    HistorialDataMillis(timestamp = millis, valor = valor)
                }

                val listaDatos = if (conFecha.isNotEmpty()) {
                    conFecha.filter {
                        it.timestamp >= limiteTiempoMillis || horasFiltro >= 8760
                    }
                } else {
                    // Sin ninguna fecha reconocible: se grafican igual, en el orden
                    // en que llegaron, para no dejar la pantalla vacía.
                    hijos.mapIndexedNotNull { indice, child ->
                        val valor = child.child(variableActual).value?.toString()?.toDoubleOrNull()
                            ?: return@mapIndexedNotNull null
                        HistorialDataMillis(
                            timestamp = ahoraMillis - (hijos.size - indice) * 60_000L,
                            valor = valor
                        )
                    }
                }

                if (listaDatos.isNotEmpty()) {
                    dibujarGraficoReal(listaDatos)
                    actualizarMetricasCabecera(listaDatos)
                } else {
                    lineChart.clear()
                    lineChart.setNoDataText("Sin mediciones en este rango 🌱")
                    lineChart.invalidate()
                    tvValorActual.text = "--"
                    tvPromedio.text = "--"
                    tvRango.text = "--"
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("DetalleGrafico", "Error al cargar datos: ${error.message}")
            }
        })
    }

    private fun actualizarMetricasCabecera(lista: List<HistorialDataMillis>) {
        if (lista.isEmpty()) return
        val unidad = obtenerUnidadVariable(variableActual)
        val ultimoValor = lista.maxByOrNull { it.timestamp }?.valor ?: lista.last().valor
        val promedio = lista.sumOf { it.valor } / lista.size
        val minVal = lista.minOf { it.valor }
        val maxVal = lista.maxOf { it.valor }

        tvValorActual.text = "${formatear(ultimoValor)} $unidad"
        tvPromedio.text = "${formatear(promedio)} $unidad"
        tvRango.text = "${formatear(minVal)} – ${formatear(maxVal)} $unidad"
    }

    private fun formatear(valor: Double): String = String.format(Locale.getDefault(), "%.1f", valor)

    private fun dibujarGraficoReal(listaRaw: List<HistorialDataMillis>) {
        val listaOrdenada = listaRaw.sortedBy { it.timestamp }
        if (listaOrdenada.isEmpty()) return

        val primerTimestamp = listaOrdenada.first().timestamp
        val entries = ArrayList<Entry>()

        for (data in listaOrdenada) {
            val xRelativo = (data.timestamp - primerTimestamp).toFloat()
            entries.add(Entry(xRelativo, data.valor.toFloat()))
        }

        val verdeHoja = color(R.color.sp_hoja)
        val dataSet = LineDataSet(entries, "Historial").apply {
            mode = LineDataSet.Mode.HORIZONTAL_BEZIER
            color = verdeHoja
            lineWidth = 2.6f

            setDrawCircles(true)
            setCircleColor(verdeHoja)
            circleRadius = 4f
            setDrawCircleHole(true)
            circleHoleColor = color(R.color.sp_superficie)
            circleHoleRadius = 2f

            // Resplandor verde bajo la curva
            setDrawFilled(true)
            fillDrawable = ContextCompat.getDrawable(this@DetalleGraficoActivity, R.drawable.bg_relleno_grafico)

            isHighlightEnabled = true
            highLightColor = color(R.color.sp_sol)
            highlightLineWidth = 1.4f
            enableDashedHighlightLine(10f, 6f, 0f)
            setDrawHorizontalHighlightIndicator(false)
            setDrawVerticalHighlightIndicator(true)

            setDrawValues(false)
        }

        lineChart.data = LineData(dataSet)

        val unidad = obtenerUnidadVariable(variableActual)
        if (markerView == null) {
            markerView = CustomMarkerView(this, R.layout.marker_view, primerTimestamp, unidad, horasFiltro)
            markerView?.chartView = lineChart
            lineChart.marker = markerView
        } else {
            markerView?.actualizarBaseData(primerTimestamp, unidad, horasFiltro)
        }

        val sdfSalida = SimpleDateFormat(if (horasFiltro >= 168) "dd/MM" else "HH:mm", Locale.getDefault())
        lineChart.xAxis.apply {
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val timestampReal = value.toLong() + primerTimestamp
                    return sdfSalida.format(Date(timestampReal))
                }
            }
        }

        lineChart.apply {
            notifyDataSetChanged()
            invalidate()

            // Si hay más de 4 puntos, limita la ventana visible para permitir deslizar con el dedo
            if (entries.size > 4) {
                // Calcula la distancia promedio entre puntos para establecer un rango adecuado
                val rangoTotal = entries.last().x - entries.first().x
                val rangoVisible = (rangoTotal / (entries.size - 1)) * 3.5f

                if (rangoVisible > 0f) {
                    setVisibleXRangeMaximum(rangoVisible)
                    moveViewToX(entries.last().x) // Se ubica en los datos más recientes
                } else {
                    fitScreen()
                }
            } else {
                fitScreen()
            }
            invalidate()
        }
    }

    private fun obtenerUnidadVariable(varName: String): String {
        return when (varName) {
            "vpd" -> "kPa"
            "tempAire", "tempSuelo" -> "°C"
            "humAire", "hum15", "hum40" -> "%"
            "presion" -> "hPa"
            "bateria" -> "V"
            else -> ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
        nodosListener?.let { dbRef.removeEventListener(it) }
    }

    companion object {
        private const val ESTADO_VARIABLE = "variable_actual"
        private const val ESTADO_NODO = "nodo_actual"

        /** Variables que se pueden graficar: clave en Firebase → nombre visible. */
        private val VARIABLES = listOf(
            "vpd" to "Estrés hídrico (VPD)",
            "tempAire" to "Temperatura del aire",
            "humAire" to "Humedad del aire",
            "presion" to "Presión atmosférica",
            "hum15" to "Humedad a 15 cm",
            "hum40" to "Humedad a 40 cm",
            "tempSuelo" to "Temperatura del suelo"
        )
    }
}

data class HistorialDataMillis(
    val timestamp: Long,
    val valor: Double
)
