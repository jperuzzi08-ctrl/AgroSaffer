package com.agro.monitor

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Locale

class HistorialActivity : AppCompatActivity() {

    private lateinit var contenedor: LinearLayout
    private lateinit var layoutVacio: View
    private lateinit var scrollAlertas: View
    private lateinit var tvContador: TextView
    private lateinit var tvVacioTexto: TextView
    private lateinit var btnBorrar: Button

    // Selector de nodo (mismo desplegable con círculos de estado que Inicio)
    private lateinit var spinnerNodo: Spinner
    private lateinit var adaptadorNodos: AdaptadorNodos
    private lateinit var dbRef: DatabaseReference
    private var nodosListener: ValueEventListener? = null

    /** Nodo cuyas alertas se muestran, o NodoInfo.TODOS. */
    private var filtroNodo: String = NodoInfo.TODOS

    /** Cantidad de alertas guardadas por nodo (para el desplegable). */
    private var alertasPorNodo: Map<String, Int> = emptyMap()
    private var totalAlertas = 0

    private val manejador = Handler(Looper.getMainLooper())
    private val refrescoEstados = object : Runnable {
        override fun run() {
            adaptadorNodos.refrescarEstados()
            manejador.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_historial)

        contenedor = findViewById(R.id.contenedorAlertas)
        layoutVacio = findViewById(R.id.layoutVacio)
        scrollAlertas = findViewById(R.id.scrollAlertas)
        tvContador = findViewById(R.id.tvContadorAlertas)
        tvVacioTexto = findViewById(R.id.tvVacioTexto)
        btnBorrar = findViewById(R.id.btnBorrarHistorial)

        // Arranca mostrando el nodo elegido en Inicio / Tendencias
        filtroNodo = savedInstanceState?.getString(ESTADO_FILTRO)
            ?: intent.getStringExtra(EXTRA_FILTRO_NODO) // al tocar la notificación
            ?: PreferenciasApp.nodoActual(this)
            ?: NodoInfo.TODOS

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "DdaAJqXxIcadkzamNl16yWyy8k13"
        dbRef = FirebaseDatabase.getInstance().getReference("usuarios").child(uid).child("lecturas")

        NavegacionInferior.configurar(this, R.id.nav_alertas)
        configurarSelectorNodo()

        // Cargar y mostrar alertas de la memoria local
        cargarHistorialVisual()

        // Botón para vaciar el historial (con confirmación)
        btnBorrar.setOnClickListener { confirmarLimpieza() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ESTADO_FILTRO, filtroNodo)
    }

    override fun onStart() {
        super.onStart()
        manejador.post(refrescoEstados)
    }

    override fun onStop() {
        super.onStop()
        manejador.removeCallbacks(refrescoEstados)
    }

    override fun onDestroy() {
        super.onDestroy()
        nodosListener?.let { dbRef.removeEventListener(it) }
        manejador.removeCallbacksAndMessages(null)
    }

    // ===================== Selector de nodo =====================

    private fun configurarSelectorNodo() {
        spinnerNodo = findViewById(R.id.spinnerNodoAlertas)
        adaptadorNodos = AdaptadorNodos(this, R.layout.item_nodo_chip) { info ->
            val cantidad = if (info.esTodos) totalAlertas else alertasPorNodo[info.id] ?: 0
            textoCantidad(cantidad)
        }
        spinnerNodo.adapter = adaptadorNodos
        spinnerNodo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val elegido = adaptadorNodos.getItem(position).id
                if (elegido == filtroNodo) return
                filtroNodo = elegido
                // Elegir un nodo concreto acá también lo elige en Inicio y Tendencias
                if (elegido != NodoInfo.TODOS) {
                    PreferenciasApp.guardarNodoActual(this@HistorialActivity, elegido)
                }
                cargarHistorialVisual()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        nodosListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val nodos = NodoInfo.desdeSnapshot(snapshot)
                adaptadorNodos.actualizar(listOf(NodoInfo.OPCION_TODOS) + nodos)
                spinnerNodo.visibility = View.VISIBLE

                // Recalcula los conteos por nodo ahora que se conocen todos los nodos
                contarAlertasPorNodo(leerAlertas())

                var posicion = adaptadorNodos.posicionDe(filtroNodo)
                if (posicion < 0) {
                    // El nodo guardado ya no existe: se muestran todos
                    posicion = 0
                    filtroNodo = NodoInfo.TODOS
                    cargarHistorialVisual()
                }
                if (spinnerNodo.selectedItemPosition != posicion) {
                    spinnerNodo.setSelection(posicion, false)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Historial", "Error al cargar nodos: ${error.message}")
            }
        }
        dbRef.addValueEventListener(nodosListener as ValueEventListener)
    }

    /** Las alertas guardadas mencionan el nodo como "… en <nodo> …". */
    private fun esDelNodo(alerta: String, nodo: String): Boolean = alerta.contains(" en $nodo ")

    private fun nodoDeAlerta(alerta: String, nodos: List<String>): String? =
        nodos.sortedByDescending { it.length }.firstOrNull { esDelNodo(alerta, it) }

    private fun contarAlertasPorNodo(alertas: List<String>) {
        val nodos = adaptadorNodos.ids().filter { it != NodoInfo.TODOS }
        alertasPorNodo = alertas
            .mapNotNull { nodoDeAlerta(it, nodos) }
            .groupingBy { it }
            .eachCount()
        totalAlertas = alertas.size
        adaptadorNodos.refrescarEstados()
    }

    private fun textoCantidad(cantidad: Int): String = when (cantidad) {
        0 -> "sin alertas"
        1 -> "1 alerta"
        else -> "$cantidad alertas"
    }

    // ===================== Lista de alertas =====================

    private fun leerAlertas(): List<String> {
        val prefs = Cuentas.prefs(this, Cuentas.PREFS_HISTORIAL)
        // Recuperamos el string largo separado por caracteres "|"
        val historialRaw = prefs.getString(CLAVE_ALERTAS, "") ?: ""
        return historialRaw.split("|").filter { it.isNotBlank() }
    }

    private fun cargarHistorialVisual() {
        contenedor.removeAllViews()

        val todas = leerAlertas()
        contarAlertasPorNodo(todas)

        // Filtradas por nodo y al revés (la más nueva arriba)
        val listaAlertas = todas
            .filter { filtroNodo == NodoInfo.TODOS || esDelNodo(it, filtroNodo) }
            .reversed()

        val hayAlertas = listaAlertas.isNotEmpty()
        layoutVacio.visibility = if (hayAlertas) View.GONE else View.VISIBLE
        scrollAlertas.visibility = if (hayAlertas) View.VISIBLE else View.GONE
        btnBorrar.isEnabled = hayAlertas

        val cantidad = when (listaAlertas.size) {
            0 -> "Sin eventos registrados"
            1 -> "1 evento registrado"
            else -> "${listaAlertas.size} eventos registrados"
        }
        tvContador.text = if (filtroNodo == NodoInfo.TODOS) cantidad else "$cantidad en $filtroNodo"

        val enOtrosNodos = todas.size - listaAlertas.size
        tvVacioTexto.text = when {
            filtroNodo == NodoInfo.TODOS -> "No se registraron eventos de estrés en el cultivo."
            enOtrosNodos > 0 -> "Este nodo no registró eventos de estrés. " +
                "Hay ${textoCantidad(enOtrosNodos)} en otros nodos."
            else -> "Este nodo no registró eventos de estrés."
        }

        val inflater = LayoutInflater.from(this)
        for (alerta in listaAlertas) {
            val item = inflater.inflate(R.layout.item_alerta, contenedor, false)
            val datos = interpretarAlerta(alerta)

            item.findViewById<TextView>(R.id.tvAlertaIcono).apply {
                text = datos.icono
                backgroundTintList = ColorStateList.valueOf(color(colorDeFondo(datos.titulo)))
            }
            item.findViewById<TextView>(R.id.tvAlertaTitulo).text = datos.titulo
            item.findViewById<TextView>(R.id.tvAlertaMensaje).apply {
                text = datos.mensaje
                visibility = if (datos.mensaje.isEmpty()) View.GONE else View.VISIBLE
            }
            item.findViewById<TextView>(R.id.tvAlertaFecha).apply {
                text = datos.fecha
                visibility = if (datos.fecha.isEmpty()) View.GONE else View.VISIBLE
            }
            contenedor.addView(item)
        }
    }

    /** Limpia las alertas del nodo elegido, o todas si está en "Todos los nodos". */
    private fun confirmarLimpieza() {
        val todos = filtroNodo == NodoInfo.TODOS
        MaterialAlertDialogBuilder(this)
            .setTitle(if (todos) "¿Limpiar historial?" else "¿Limpiar alertas de $filtroNodo?")
            .setMessage(
                if (todos) {
                    "Se borrarán todas las alertas guardadas en este dispositivo."
                } else {
                    "Se borrarán las alertas de $filtroNodo guardadas en este dispositivo. " +
                        "Las de otros nodos se mantienen."
                }
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Limpiar") { _, _ ->
                val prefs = Cuentas.prefs(this, Cuentas.PREFS_HISTORIAL)
                if (todos) {
                    prefs.edit().clear().apply()
                } else {
                    val restantes = leerAlertas().filterNot { esDelNodo(it, filtroNodo) }
                    prefs.edit().putString(CLAVE_ALERTAS, restantes.joinToString("|")).apply()
                }
                cargarHistorialVisual()
            }
            .show()
    }

    /**
     * Las alertas se guardan como: "⚠️ [dd/MM/yyyy HH:mm:ss] 🚨 Título: mensaje".
     * Se separan en partes para mostrarlas con jerarquía visual.
     */
    private fun interpretarAlerta(texto: String): AlertaVisual {
        val coincidencia = PATRON_ALERTA.find(texto.trim())
            ?: return AlertaVisual("⚠️", texto.trim(), "", "")

        val (fechaRaw, tituloCompleto, mensaje) = coincidencia.destructured
        val partes = tituloCompleto.trim().split(" ", limit = 2)
        val tieneEmoji = partes.size == 2 && !partes[0].first().isLetterOrDigit()
        val icono = if (tieneEmoji) partes[0] else "⚠️"
        val titulo = if (tieneEmoji) partes[1] else tituloCompleto.trim()

        return AlertaVisual(icono, titulo, mensaje.trim(), formatearFecha(fechaRaw))
    }

    private fun formatearFecha(fechaRaw: String): String {
        return try {
            val entrada = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            val salida = SimpleDateFormat("d MMM yyyy · HH:mm", Locale("es", "AR"))
            entrada.parse(fechaRaw)?.let { salida.format(it) } ?: fechaRaw
        } catch (e: Exception) {
            fechaRaw
        }
    }

    @ColorRes
    private fun colorDeFondo(titulo: String): Int {
        val t = titulo.lowercase(Locale.getDefault())
        return when {
            "calor" in t || "calentamiento" in t -> R.color.sp_terracota_suave
            "helada" in t || "bloqueo" in t -> R.color.sp_cielo_suave
            "humedad" in t -> R.color.sp_agua_suave
            "fúngica" in t || "fungica" in t -> R.color.sp_glicina_suave
            "vpd" in t || "estrés" in t || "batería" in t -> R.color.sp_sol_suave
            else -> R.color.sp_menta
        }
    }

    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    private data class AlertaVisual(
        val icono: String,
        val titulo: String,
        val mensaje: String,
        val fecha: String
    )

    companion object {
        /** Nodo a mostrar al abrir (o NodoInfo.TODOS); lo usa la notificación de alertas. */
        const val EXTRA_FILTRO_NODO = "FILTRO_NODO"

        private const val CLAVE_ALERTAS = "lista_alertas"
        private const val ESTADO_FILTRO = "filtro_nodo"

        private val PATRON_ALERTA = Regex("""^\S*\s*\[(.+?)]\s*(.+?):\s(.+)$""", RegexOption.DOT_MATCHES_ALL)
    }
}
