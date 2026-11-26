package com.agro.monitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Locale

/**
 * Pantalla principal de AgroSaffer: todos los nodos juntos, en tarjetas
 * compactas. Tocar una tarjeta abre el panel detallado de ese nodo.
 */
class ResumenActivity : AppCompatActivity() {

    private lateinit var dbRef: DatabaseReference
    private lateinit var configRef: DatabaseReference
    private var lecturasListener: ValueEventListener? = null
    private var configListener: ValueEventListener? = null

    private var snapshotLecturas: DataSnapshot? = null
    private var umbrales = UmbralesAgronomicos()

    // Hasta que lleguen los umbrales no se generan alertas (evita avisos a medias)
    private var configCargada = false

    private lateinit var contenedorNodos: LinearLayout
    private lateinit var layoutVacio: View
    private lateinit var tvCantidadNodos: TextView
    private lateinit var tvResumenEstados: TextView
    private lateinit var tvNodosEnLinea: TextView
    private lateinit var tvNodosConProblema: TextView

    private val manejador = Handler(Looper.getMainLooper())
    private val refrescoEstados = object : Runnable {
        override fun run() {
            renderizar()
            manejador.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_resumen)

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "DdaAJqXxIcadkzamNl16yWyy8k13"
        dbRef = FirebaseDatabase.getInstance().getReference("usuarios").child(uid).child("lecturas")
        configRef = FirebaseDatabase.getInstance().getReference("usuarios").child(uid).child("configuracion")

        contenedorNodos = findViewById(R.id.contenedorNodos)
        layoutVacio = findViewById(R.id.layoutVacioResumen)
        tvCantidadNodos = findViewById(R.id.tvCantidadNodos)
        tvResumenEstados = findViewById(R.id.tvResumenEstados)
        tvNodosEnLinea = findViewById(R.id.tvNodosEnLinea)
        tvNodosConProblema = findViewById(R.id.tvNodosConProblema)

        AlertasActivas.crearCanal(this)
        solicitarPermisoNotificaciones()

        findViewById<ImageView>(R.id.btnModoTemaResumen).apply {
            contentDescription = if (PreferenciasApp.esNocheActiva(this@ResumenActivity)) {
                "Cambiar a modo día"
            } else {
                "Cambiar a modo noche"
            }
            setOnClickListener { PreferenciasApp.alternarTema(this@ResumenActivity) }
        }
        findViewById<View>(R.id.btnExportarResumen).setOnClickListener {
            ExportadorExcel.exportar(this, dbRef)
        }

        NavegacionInferior.configurar(this, R.id.nav_inicio)

        configListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                umbrales = UmbralesAgronomicos.desde(snapshot)
                configCargada = true
                procesarAlertasDeTodos()
                renderizar()
            }

            override fun onCancelled(error: DatabaseError) {
                renderizar()
            }
        }
        configRef.addValueEventListener(configListener as ValueEventListener)

        lecturasListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshotLecturas = snapshot
                if (snapshot.exists()) {
                    AlertasActivas.podarNodos(
                        this@ResumenActivity,
                        snapshot.children.mapNotNull { it.key }
                    )
                }
                procesarAlertasDeTodos()
                renderizar()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    this@ResumenActivity,
                    "Error al cargar los nodos: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        dbRef.addValueEventListener(lecturasListener as ValueEventListener)
    }

    override fun onStart() {
        super.onStart()
        manejador.post(refrescoEstados)
    }

    override fun onResume() {
        super.onResume()
        // El saludo del banner cambia con la hora del día
        findViewById<TextView>(R.id.tvSaludo).text = saludoSegunHora()
        // Al volver de editar un subtítulo, la tarjeta se actualiza.
        renderizar()
    }

    override fun onStop() {
        super.onStop()
        manejador.removeCallbacks(refrescoEstados)
    }

    override fun onDestroy() {
        super.onDestroy()
        lecturasListener?.let { dbRef.removeEventListener(it) }
        configListener?.let { configRef.removeEventListener(it) }
        manejador.removeCallbacksAndMessages(null)
    }

    // ===================== Alertas =====================

    /**
     * Genera las alertas de TODOS los nodos (no solo del que esté abierto), así la
     * notificación y el historial avisan aunque te quedes en esta pantalla.
     * Solo suenan las alertas nuevas: volver a procesar la misma lectura no repite nada.
     */
    private fun procesarAlertasDeTodos() {
        val snapshot = snapshotLecturas ?: return
        if (!configCargada) return

        var nuevas = 0
        for (nodo in snapshot.children) {
            val id = nodo.key ?: continue
            val ultima = Lecturas.ultima(nodo) ?: continue
            val alertas = EvaluadorAlertas.evaluar(id, LecturaData.desde(ultima), umbrales)
            // Lecturas sin fecha: se muestran, pero no se anuncian desde acá
            nuevas += AlertasActivas.procesarNodo(this, id, alertas, Lecturas.millis(ultima))
        }
        AlertasActivas.actualizarNotificacion(this, hayNuevas = nuevas > 0)
    }

    // ===================== Tarjetas =====================

    private fun renderizar() {
        val snapshot = snapshotLecturas ?: return

        val nodos = snapshot.children
            .mapNotNull { nodo ->
                NodoResumen.desde(
                    nodo,
                    PreferenciasApp.subtituloNodo(this, nodo.key.orEmpty()),
                    umbrales
                )
            }
            // Primero los que necesitan atención, después por nombre
            .sortedWith(compareByDescending<NodoResumen> { it.hayProblema }.thenBy { it.info.id })

        val enLinea = nodos.count { it.info.estado == EstadoNodo.EN_LINEA }
        val conProblema = nodos.count { it.hayProblema }

        tvCantidadNodos.text = when (nodos.size) {
            0 -> "Sin nodos"
            1 -> "1 nodo en el campo"
            else -> "${nodos.size} nodos en el campo"
        }
        tvResumenEstados.text = when {
            nodos.isEmpty() -> "No hay nodos asignados a esta cuenta"
            conProblema == 0 -> "Todo en orden · actualizado en vivo"
            conProblema == 1 -> "1 nodo necesita atención"
            else -> "$conProblema nodos necesitan atención"
        }

        tvNodosEnLinea.text = when (enLinea) {
            0 -> "0 en línea"
            1 -> "1 en línea"
            else -> "$enLinea en línea"
        }
        tvNodosConProblema.text = if (conProblema == 0) {
            "Sin problemas"
        } else {
            "$conProblema con problemas"
        }
        tvNodosConProblema.backgroundTintList = ColorStateList.valueOf(
            color(if (conProblema == 0) R.color.sp_menta else R.color.sp_terracota_suave)
        )
        tvNodosConProblema.setTextColor(
            color(if (conProblema == 0) R.color.sp_bosque_oscuro else R.color.sp_terracota_oscuro)
        )

        mostrarPromedio(nodos)

        contenedorNodos.removeAllViews()
        layoutVacio.visibility = if (nodos.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (nodo in nodos) {
            val tarjeta = inflater.inflate(R.layout.item_resumen_nodo, contenedorNodos, false)
                as MaterialCardView
            enlazarTarjeta(tarjeta, nodo)
            contenedorNodos.addView(tarjeta)
        }
    }

    /** Tarjeta "Promedio del campo": promedio de las últimas mediciones de todos los nodos. */
    private fun mostrarPromedio(nodos: List<NodoResumen>) {
        val tarjeta = findViewById<View>(R.id.cardPromedio)
        val promedio = PromedioCampo.calcular(nodos)
        if (promedio == null) {
            tarjeta.visibility = View.GONE
            return
        }
        tarjeta.visibility = View.VISIBLE

        fun formato(patron: String, valor: Double) = String.format(Locale.getDefault(), patron, valor)
        findViewById<TextView>(R.id.tvPromVpd).text = formato("%.2f", promedio.vpd)
        findViewById<TextView>(R.id.tvPromTempAire).text = formato("%.1f", promedio.tempAire)
        findViewById<TextView>(R.id.tvPromHumAire).text = formato("%.0f", promedio.humAire)
        findViewById<TextView>(R.id.tvPromHum15).text = formato("%.0f", promedio.hum15)
        findViewById<TextView>(R.id.tvPromHum40).text = formato("%.0f", promedio.hum40)
        findViewById<TextView>(R.id.tvPromTempSuelo).text = formato("%.1f", promedio.tempSuelo)

        val incluidos = if (promedio.nodosIncluidos == 1) "1 nodo" else "${promedio.nodosIncluidos} nodos"
        findViewById<TextView>(R.id.tvPromedioDetalle).text = when {
            promedio.soloDatosViejos ->
                "Ningún nodo envió datos en la última hora · últimas lecturas de $incluidos"
            promedio.nodosSinDatosRecientes == 0 ->
                "Últimas lecturas de $incluidos"
            promedio.nodosSinDatosRecientes == 1 ->
                "Últimas lecturas de $incluidos · no incluye 1 sin datos recientes"
            else ->
                "Últimas lecturas de $incluidos · no incluye ${promedio.nodosSinDatosRecientes} sin datos recientes"
        }
    }

    private fun enlazarTarjeta(tarjeta: MaterialCardView, nodo: NodoResumen) {
        val data = nodo.lectura

        tarjeta.findViewById<TextView>(R.id.tvNombreNodoResumen).text = nodo.info.id
        tarjeta.findViewById<TextView>(R.id.tvSubtituloNodoResumen).apply {
            text = nodo.subtitulo
            visibility = if (nodo.subtitulo.isBlank()) View.GONE else View.VISIBLE
        }
        tarjeta.findViewById<TextView>(R.id.tvEstadoNodoResumen).text = nodo.info.detalle()
        tarjeta.findViewById<View>(R.id.puntoEstadoResumen).backgroundTintList =
            ColorStateList.valueOf(color(nodo.info.estado.color))

        val badge = tarjeta.findViewById<TextView>(R.id.tvBadgeProblema)
        badge.visibility = if (nodo.hayProblema) View.VISIBLE else View.GONE
        badge.contentDescription = when {
            nodo.alertas.isNotEmpty() -> "Tiene ${nodo.alertas.size} alertas"
            nodo.info.estado == EstadoNodo.DEMORADO -> "Nodo demorado"
            nodo.info.estado == EstadoNodo.SIN_SENAL -> "Nodo sin señal"
            else -> "Nodo sin datos"
        }

        tarjeta.strokeWidth = if (nodo.hayProblema) dp(1) else 0
        tarjeta.strokeColor = color(R.color.sp_terracota)

        tarjeta.findViewById<TextView>(R.id.tvVpdResumen).text =
            data?.let { String.format(Locale.getDefault(), "%.2f", it.vpd) } ?: "--"
        tarjeta.findViewById<TextView>(R.id.tvTempAireResumen).text =
            data?.let { String.format(Locale.getDefault(), "%.1f", it.tempAire) } ?: "--"
        tarjeta.findViewById<TextView>(R.id.tvHumAireResumen).text =
            data?.let { String.format(Locale.getDefault(), "%.0f", it.humAire) } ?: "--"
        tarjeta.findViewById<TextView>(R.id.tvHum15Resumen).text =
            data?.hum15?.toString() ?: "--"
        tarjeta.findViewById<TextView>(R.id.tvHum40Resumen).text =
            data?.hum40?.toString() ?: "--"
        tarjeta.findViewById<TextView>(R.id.tvTempSueloResumen).text =
            data?.let { String.format(Locale.getDefault(), "%.1f", it.tempSuelo) } ?: "--"
        tarjeta.findViewById<TextView>(R.id.tvBateriaResumen).text =
            data?.let { String.format(Locale.getDefault(), "Batería %.1f V", it.bateria) } ?: "Batería --"
        tarjeta.findViewById<TextView>(R.id.tvPresionResumen).text =
            data?.let { String.format(Locale.getDefault(), "%.0f hPa", it.presion) } ?: "-- hPa"

        val alertas = tarjeta.findViewById<TextView>(R.id.tvAlertasResumen)
        alertas.text = when {
            nodo.alertas.size == 1 -> "1 alerta"
            nodo.alertas.size > 1 -> "${nodo.alertas.size} alertas"
            nodo.info.estado != EstadoNodo.EN_LINEA -> nodo.info.estado.etiqueta
            else -> "Sin alertas"
        }
        alertas.setTextColor(
            color(if (nodo.alertas.isNotEmpty()) R.color.sp_terracota_oscuro else R.color.sp_tinta_suave)
        )

        tarjeta.contentDescription = buildString {
            append(nodo.info.id)
            if (nodo.subtitulo.isNotBlank()) append(", ${nodo.subtitulo}")
            append(", ${nodo.info.detalle()}")
            if (nodo.hayProblema) append(", necesita atención")
        }

        tarjeta.setOnClickListener { abrirNodo(nodo.info.id) }
        tarjeta.findViewById<View>(R.id.btnEditarSubtituloResumen).setOnClickListener {
            Dialogos.editarSubtitulo(this, nodo.info.id, nodo.subtitulo) {
                renderizar()
            }
        }
    }

    private fun abrirNodo(idNodo: String) {
        PreferenciasApp.guardarNodoActual(this, idNodo)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NODO_ID, idNodo)
        )
    }

    // ===================== Utilidades =====================

    private fun solicitarPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)
}
