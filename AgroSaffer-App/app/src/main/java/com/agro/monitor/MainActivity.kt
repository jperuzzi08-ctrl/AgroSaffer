package com.agro.monitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var dbRef: DatabaseReference
    private lateinit var configRef: DatabaseReference

    private var lecturaQuery: Query? = null
    private var lecturaListener: ValueEventListener? = null
    private var configListener: ValueEventListener? = null
    private var nodosListener: ValueEventListener? = null

    private var nodoSeleccionadoActual: String? = null
    private val listaNodosActual = mutableListOf<String>()
    private lateinit var adaptadorNodos: AdaptadorNodos

    // Vistas principales
    private lateinit var spinnerNodos: Spinner
    private lateinit var imgWifi: ImageView
    private lateinit var imgLora: ImageView

    // Estado de conexión: servidor (Firebase) y nodo (antigüedad de la última lectura)
    private var conexionRef: DatabaseReference? = null
    private var conexionListener: ValueEventListener? = null
    private var servidorConectado: Boolean? = null // null = verificando
    private var millisUltimaLectura: Long? = null
    private var hayLecturaDelNodo = false
    private val manejadorEstado = Handler(Looper.getMainLooper())
    private val refrescoEstado = object : Runnable {
        override fun run() {
            // Aunque no lleguen datos nuevos, un nodo puede pasar a "demorado"
            actualizarIndicadoresConexion()
            adaptadorNodos.refrescarEstados()
            manejadorEstado.postDelayed(this, 60_000)
        }
    }
    private lateinit var tvVPD: TextView
    private lateinit var tvTempAire: TextView
    private lateinit var tvHumAire: TextView
    private lateinit var tvPresion: TextView
    private lateinit var tvHum15: TextView
    private lateinit var tvHum40: TextView
    private lateinit var tvTempSuelo: TextView

    // Elementos del diseño solarpunk
    private lateinit var tvUltimaLectura: TextView
    private lateinit var puntoEstado: View
    private lateinit var tvEstadoVPD: TextView
    private lateinit var tvRangoVPD: TextView
    private lateinit var gaugeTrack: View
    private lateinit var gaugeBajo: View
    private lateinit var gaugeOptimo: View
    private lateinit var gaugeAlto: View
    private lateinit var gaugeMarcador: View
    private lateinit var barraHumAire: LinearProgressIndicator
    private lateinit var barraHum15: LinearProgressIndicator
    private lateinit var barraHum40: LinearProgressIndicator

    private var ultimoVpd: Double? = null

    // Alertas: hasta que lleguen los umbrales no se evalúa nada (evita avisos a medias)
    private var configCargada = false
    private var ultimaLecturaEvaluada: Triple<String, LecturaData, Long?>? = null

    // Umbrales agronómicos
    private var tempMaxConfig: Double? = null
    private var tempMinConfig: Double? = null
    private var vpdMaxConfig: Double? = null
    private var vpdMinConfig: Double? = null
    private var humSueloMinConfig: Double? = null
    private var tempRadicularMinConfig: Double? = null
    private var tempRadicularMaxConfig: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid ?: "DdaAJqXxIcadkzamNl16yWyy8k13"

        // Referencia directa al UID activo del usuario logueado
        dbRef = FirebaseDatabase.getInstance().getReference("usuarios").child(uid).child("lecturas")
        configRef = FirebaseDatabase.getInstance().getReference("usuarios").child(uid).child("configuracion")

        // Inicialización de componentes XML
        spinnerNodos = findViewById(R.id.spinnerNodos)
        adaptadorNodos = AdaptadorNodos(this, R.layout.item_nodo_seleccion)
        spinnerNodos.adapter = adaptadorNodos
        spinnerNodos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val seleccionado = adaptadorNodos.getItem(position).id
                if (seleccionado != nodoSeleccionadoActual) {
                    seleccionarNodo(seleccionado)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        imgWifi = findViewById(R.id.imgWifi)
        imgLora = findViewById(R.id.imgLora)
        val btnModoTema = findViewById<View>(R.id.btnModoTema)

        tvVPD = findViewById(R.id.tvVPD)
        tvTempAire = findViewById(R.id.tvTempAire)
        tvHumAire = findViewById(R.id.tvHumAire)
        tvPresion = findViewById(R.id.tvPresion)
        tvHum15 = findViewById(R.id.tvHum15)
        tvHum40 = findViewById(R.id.tvHum40)
        tvTempSuelo = findViewById(R.id.tvTempSuelo)
        val btnIrHistorial = findViewById<View>(R.id.btnIrHistorial)

        tvUltimaLectura = findViewById(R.id.tvUltimaLectura)
        puntoEstado = findViewById(R.id.puntoEstado)
        tvEstadoVPD = findViewById(R.id.tvEstadoVPD)
        tvRangoVPD = findViewById(R.id.tvRangoVPD)
        gaugeTrack = findViewById(R.id.gaugeTrack)
        gaugeBajo = findViewById(R.id.gaugeBajo)
        gaugeOptimo = findViewById(R.id.gaugeOptimo)
        gaugeAlto = findViewById(R.id.gaugeAlto)
        gaugeMarcador = findViewById(R.id.gaugeMarcador)
        barraHumAire = findViewById(R.id.barraHumAire)
        barraHum15 = findViewById(R.id.barraHum15)
        barraHum40 = findViewById(R.id.barraHum40)

        findViewById<TextView>(R.id.tvSaludo).text = saludoSegunHora()
        actualizarIndicadorVpd()

        AlertasActivas.crearCanal(this)
        solicitarPermisoNotificaciones()
        escucharConexionServidor()
        escucharConfiguracion()
        cargarNodosEnSpinner()

        // Toque en los íconos de conexión: detalle de servidor y nodo
        findViewById<View>(R.id.pillConexion).setOnClickListener { mostrarDetalleConexion() }

        // Toda la tarjeta del nodo abre el selector
        findViewById<View>(R.id.filaNodo).setOnClickListener { spinnerNodos.performClick() }

        // Botón rápido: alterna entre modo día y modo noche
        btnModoTema.contentDescription = if (PreferenciasApp.esNocheActiva(this)) {
            "Cambiar a modo día"
        } else {
            "Cambiar a modo noche"
        }
        btnModoTema.setOnClickListener { PreferenciasApp.alternarTema(this) }

        NavegacionInferior.configurar(this, R.id.nav_inicio)

        btnIrHistorial.setOnClickListener {
            startActivity(Intent(this, HistorialActivity::class.java))
        }

        findViewById<View>(R.id.cardVPD).setOnClickListener { abrirDetalleGrafico("vpd", "Estrés hídrico (VPD)") }
        findViewById<View>(R.id.cardTempAire).setOnClickListener { abrirDetalleGrafico("tempAire", "Temperatura del aire") }
        findViewById<View>(R.id.cardHumAire).setOnClickListener { abrirDetalleGrafico("humAire", "Humedad del aire") }
        findViewById<View>(R.id.cardPresion).setOnClickListener { abrirDetalleGrafico("presion", "Presión atmosférica") }
        findViewById<View>(R.id.cardHum15).setOnClickListener { abrirDetalleGrafico("hum15", "Humedad a 15 cm") }
        findViewById<View>(R.id.cardHum40).setOnClickListener { abrirDetalleGrafico("hum40", "Humedad a 40 cm") }
        findViewById<View>(R.id.cardTempSuelo).setOnClickListener { abrirDetalleGrafico("tempSuelo", "Temperatura del suelo") }
    }

    private fun saludoSegunHora(): String {
        return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Buen día"
            in 12..19 -> "Buenas tardes"
            else -> "Buenas noches"
        }
    }

    private fun solicitarPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun escucharConfiguracion() {
        configListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    tempMaxConfig = snapshot.child("tempMax").value?.toString()?.toDoubleOrNull()
                    tempMinConfig = snapshot.child("tempMin").value?.toString()?.toDoubleOrNull()
                    vpdMaxConfig = snapshot.child("vpdMax").value?.toString()?.toDoubleOrNull()
                    vpdMinConfig = snapshot.child("vpdMin").value?.toString()?.toDoubleOrNull()
                    humSueloMinConfig = snapshot.child("humSueloMin").value?.toString()?.toDoubleOrNull()
                    tempRadicularMinConfig = snapshot.child("tempRadicularMin").value?.toString()?.toDoubleOrNull()
                    tempRadicularMaxConfig = snapshot.child("tempRadicularMax").value?.toString()?.toDoubleOrNull()
                }
                // Los umbrales definen las zonas del medidor VPD
                actualizarIndicadorVpd()

                // Con umbrales nuevos (o recién cargados) se reevalúa la última lectura
                configCargada = true
                ultimaLecturaEvaluada?.let { (nodo, datos, momento) ->
                    procesarAlertas(nodo, datos, momento)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("MainActivity", "Error al cargar configuración: ${error.message}")
            }
        }
        configRef.addValueEventListener(configListener as ValueEventListener)
    }

    private fun seleccionarNodo(nodo: String) {
        nodoSeleccionadoActual = nodo
        PreferenciasApp.guardarNodoActual(this, nodo)
        escucharUltimaLectura(nodo)
    }

    private fun cargarNodosEnSpinner() {
        nodosListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Cada nodo con la hora de su última lectura (para el círculo de estado)
                val nodos = NodoInfo.desdeSnapshot(snapshot)

                if (nodos.isNotEmpty()) {
                    val nuevosIds = nodos.map { it.id }
                    val cambioLaLista = nuevosIds != listaNodosActual
                    adaptadorNodos.actualizar(nodos)

                    // Si solo cambiaron los estados, se mantiene la selección actual
                    if (!cambioLaLista) return

                    listaNodosActual.clear()
                    listaNodosActual.addAll(nuevosIds)

                    // Alertas de nodos que ya no existen: fuera de la notificación
                    AlertasActivas.podarNodos(this@MainActivity, nuevosIds)
                    AlertasActivas.actualizarNotificacion(this@MainActivity, hayNuevas = false)

                    // Recuperar el nodo elegido (también tras cambiar de tema o reabrir la app)
                    val nodoPreferido = nodoSeleccionadoActual
                        ?: PreferenciasApp.nodoActual(this@MainActivity)
                    val indicePrevio = listaNodosActual.indexOf(nodoPreferido)
                    if (indicePrevio >= 0 && nodoPreferido == nodoSeleccionadoActual) {
                        spinnerNodos.setSelection(indicePrevio, false)
                    } else {
                        val indice = maxOf(indicePrevio, 0)
                        spinnerNodos.setSelection(indice, false)
                        seleccionarNodo(listaNodosActual[indice])
                    }
                } else {
                    Toast.makeText(this@MainActivity, "No hay nodos asignados a esta cuenta", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("MainActivity", "Error al consultar Firebase: ${error.message}")
                Toast.makeText(this@MainActivity, "Error Firebase: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
        dbRef.addValueEventListener(nodosListener as ValueEventListener)
    }

    private fun escucharUltimaLectura(idNodo: String) {
        lecturaListener?.let { listener ->
            lecturaQuery?.removeEventListener(listener)
        }

        // Nodo nuevo: hasta que llegue su lectura, su estado es desconocido
        millisUltimaLectura = null
        hayLecturaDelNodo = false
        tvUltimaLectura.text = "Sin datos"
        actualizarIndicadoresConexion()

        lecturaQuery = dbRef.child(idNodo).orderByKey().limitToLast(1)
        lecturaListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    for (child in snapshot.children) {
                        val tempAire = child.child("tempAire").value?.toString()?.toDoubleOrNull() ?: 0.0
                        val humAire = child.child("humAire").value?.toString()?.toDoubleOrNull() ?: 0.0
                        val presion = child.child("presion").value?.toString()?.toDoubleOrNull() ?: 0.0
                        val hum15 = child.child("hum15").value?.toString()?.toDoubleOrNull() ?: 0.0
                        val hum40 = child.child("hum40").value?.toString()?.toDoubleOrNull() ?: 0.0
                        val tempSuelo = child.child("tempSuelo").value?.toString()?.toDoubleOrNull() ?: 0.0
                        val vpd = child.child("vpd").value?.toString()?.toDoubleOrNull() ?: 0.0
                        val bateria = child.child("bateria").value?.toString()?.toDoubleOrNull() ?: 0.0

                        // Los valores van sin unidad: la unidad está fija en el diseño
                        tvVPD.text = String.format(Locale.getDefault(), "%.2f", vpd)
                        tvTempAire.text = String.format(Locale.getDefault(), "%.1f", tempAire)
                        tvHumAire.text = String.format(Locale.getDefault(), "%.0f", humAire)
                        tvPresion.text = String.format(Locale.getDefault(), "%.0f", presion)
                        tvHum15.text = String.format(Locale.getDefault(), "%.0f", hum15)
                        tvHum40.text = String.format(Locale.getDefault(), "%.0f", hum40)
                        tvTempSuelo.text = String.format(Locale.getDefault(), "%.1f", tempSuelo)

                        barraHumAire.setProgressCompat(humAire.coerceIn(0.0, 100.0).toInt(), true)
                        barraHum15.setProgressCompat(hum15.coerceIn(0.0, 100.0).toInt(), true)
                        barraHum40.setProgressCompat(hum40.coerceIn(0.0, 100.0).toInt(), true)

                        ultimoVpd = vpd
                        actualizarIndicadorVpd()
                        actualizarMomentoLectura(child)

                        val lecturaData = LecturaData(
                            bateria = bateria,
                            hum15 = hum15.toInt(),
                            hum40 = hum40.toInt(),
                            humAire = humAire,
                            presion = presion,
                            tempAire = tempAire,
                            tempSuelo = tempSuelo,
                            vpd = vpd
                        )

                        // Momento de la lectura: decide si una alerta es nueva o continúa.
                        // Si la lectura no trae fecha se usa "ahora", pero solo la primera
                        // vez (al cambiar de nodo, reabrir la app o cambiar de tema, Firebase
                        // vuelve a entregar la misma lectura y no debe anunciarse de nuevo).
                        val lecturaNueva = esLecturaNueva(idNodo, child)
                        val momento = Lecturas.millis(child)
                            ?: if (lecturaNueva) System.currentTimeMillis() else null

                        ultimaLecturaEvaluada = Triple(idNodo, lecturaData, momento)
                        if (configCargada) {
                            procesarAlertas(idNodo, lecturaData, momento)
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("MainActivity", "Error en lecturas: ${error.message}")
                Toast.makeText(this@MainActivity, "Error en nodo $idNodo: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }

        lecturaQuery?.addValueEventListener(lecturaListener as ValueEventListener)
    }

    /**
     * Pinta el estado del VPD (chip de color) y ubica el marcador solar
     * sobre el medidor bajo / óptimo / alto según los umbrales configurados.
     */
    private fun actualizarIndicadorVpd() {
        val min = (vpdMinConfig ?: VPD_MIN_DEFECTO).coerceAtLeast(0.0)
        val max = (vpdMaxConfig ?: VPD_MAX_DEFECTO).coerceAtLeast(min + 0.1)
        val escala = maxOf(3.0, max + 0.5)

        tvRangoVPD.text = String.format(Locale.getDefault(), "Rango ideal %.1f – %.1f kPa", min, max)

        asignarPeso(gaugeBajo, min)
        asignarPeso(gaugeOptimo, max - min)
        asignarPeso(gaugeAlto, escala - max)
        gaugeTrack.requestLayout()

        val vpd = ultimoVpd ?: return

        val (texto, fondo, colorTexto) = when {
            // Colores fijos: la tarjeta VPD es oscura tanto de día como de noche
            vpd > max -> Triple("☀  Estrés alto · cierre estomático", R.color.estado_alto_fondo, R.color.estado_alto_texto)
            vpd < min -> Triple("💧  VPD bajo · riesgo fúngico", R.color.estado_bajo_fondo, R.color.estado_bajo_texto)
            else -> Triple("🌱  Zona óptima de transpiración", R.color.estado_optimo_fondo, R.color.estado_optimo_texto)
        }
        tvEstadoVPD.text = texto
        tvEstadoVPD.backgroundTintList = ColorStateList.valueOf(color(fondo))
        tvEstadoVPD.setTextColor(color(colorTexto))

        val fraccion = (vpd / escala).coerceIn(0.0, 1.0).toFloat()
        gaugeTrack.post {
            val ancho = gaugeTrack.width.toFloat()
            val diametro = gaugeMarcador.width.toFloat()
            val limite = maxOf(0f, ancho - diametro)
            gaugeMarcador.translationX = (ancho * fraccion - diametro / 2f).coerceIn(0f, limite)
            gaugeMarcador.visibility = View.VISIBLE
        }
    }

    private fun asignarPeso(segmento: View, peso: Double) {
        (segmento.layoutParams as LinearLayout.LayoutParams).weight = peso.toFloat().coerceAtLeast(0.05f)
    }

    private fun actualizarMomentoLectura(lectura: DataSnapshot) {
        val millis = Lecturas.millis(lectura)
        millisUltimaLectura = millis
        hayLecturaDelNodo = true
        if (millis == null) {
            tvUltimaLectura.text = "Sin hora"
        } else {
            val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
            tvUltimaLectura.text = if (DateUtils.isToday(millis)) {
                "Hoy $hora"
            } else {
                SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(millis))
            }
        }
        actualizarIndicadoresConexion()
    }

    // ===================== Verificación de conexiones =====================

    /**
     * Firebase expone ".info/connected": true mientras la app tiene conexión
     * activa con el servidor, false cuando se corta (sin internet, servidor caído…).
     */
    private fun escucharConexionServidor() {
        val ref = FirebaseDatabase.getInstance().getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val conectado = snapshot.getValue(Boolean::class.java) == true
                // Al arrancar Firebase informa "false" antes de conectar: eso se
                // muestra como "verificando" hasta que se confirme o pase el plazo.
                if (conectado) {
                    servidorConectado = true
                } else if (servidorConectado == true) {
                    servidorConectado = false
                }
                actualizarIndicadoresConexion()
            }

            override fun onCancelled(error: DatabaseError) {
                servidorConectado = false
                actualizarIndicadoresConexion()
            }
        }
        ref.addValueEventListener(listener)
        conexionRef = ref
        conexionListener = listener

        manejadorEstado.postDelayed({
            if (servidorConectado == null) {
                servidorConectado = false
                actualizarIndicadoresConexion()
            }
        }, PLAZO_VERIFICACION_MS)
    }

    private fun estadoNodo(): EstadoNodo =
        if (hayLecturaDelNodo) EstadoNodo.desde(millisUltimaLectura) else EstadoNodo.DESCONOCIDO

    private fun actualizarIndicadoresConexion() {
        val colorServidor = when (servidorConectado) {
            true -> R.color.sp_hoja
            false -> R.color.sp_terracota
            null -> R.color.sp_tinta_tenue
        }
        imgWifi.setColorFilter(color(colorServidor))

        val colorNodo = estadoNodo().color
        imgLora.setColorFilter(color(colorNodo))
        puntoEstado.backgroundTintList = ColorStateList.valueOf(color(colorNodo))
    }

    private fun mostrarDetalleConexion() {
        val servidor = when (servidorConectado) {
            true -> "✅ Conectado a Firebase"
            false -> "⚠️ Sin conexión. Revisá internet en el teléfono; mientras tanto ves los últimos datos guardados."
            null -> "⏳ Verificando…"
        }

        val nombreNodo = nodoSeleccionadoActual ?: "—"
        val hace = Lecturas.textoMinutos(Lecturas.minutosDesde(millisUltimaLectura))
        val nodo = when (estadoNodo()) {
            EstadoNodo.EN_LINEA -> "✅ En línea · última lectura hace $hace"
            EstadoNodo.DEMORADO -> "🟡 Demorado · última lectura hace $hace"
            EstadoNodo.SIN_SENAL -> "⚠️ Sin señal · última lectura hace $hace. Revisá la alimentación y la antena del nodo."
            EstadoNodo.DESCONOCIDO -> if (hayLecturaDelNodo) {
                "❔ La lectura no trae fecha, no se puede saber si es reciente."
            } else {
                "❔ Todavía no llegaron lecturas de este nodo."
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Estado de conexión")
            .setMessage(
                "Servidor\n$servidor\n\n" +
                    "Nodo $nombreNodo\n$nodo\n\n" +
                    "El nodo figura en línea si envió datos en los últimos " +
                    "${EstadoNodo.EN_LINEA_MS / 60_000} minutos."
            )
            .setPositiveButton("Entendido", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        manejadorEstado.post(refrescoEstado)
    }

    override fun onResume() {
        super.onResume()
        // Si se cambió de nodo en Tendencias, Inicio muestra el mismo
        val nodoGuardado = PreferenciasApp.nodoActual(this)
        if (nodoGuardado != null && nodoGuardado != nodoSeleccionadoActual) {
            val posicion = adaptadorNodos.posicionDe(nodoGuardado)
            if (posicion >= 0) {
                spinnerNodos.setSelection(posicion, false)
                seleccionarNodo(nodoGuardado)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        manejadorEstado.removeCallbacks(refrescoEstado)
    }

    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    /**
     * Recuerda, por nodo, la huella de la última lectura ya evaluada (clave + contenido).
     * Se guarda aparte del historial, así "Limpiar" no hace que vuelvan a saltar.
     */
    private fun esLecturaNueva(idNodo: String, lectura: DataSnapshot): Boolean {
        val huella = "${lectura.key}:${lectura.value?.hashCode()}"
        val prefs = Cuentas.prefs(this, Cuentas.PREFS_LECTURAS_EVALUADAS)
        if (prefs.getString(idNodo, null) == huella) return false
        prefs.edit().putString(idNodo, huella).apply()
        return true
    }

    /**
     * Evalúa la lectura contra los umbrales y actualiza la única notificación de alertas.
     * Solo las alertas NUEVAS suenan y se guardan en el historial; las que siguen activas
     * actualizan la notificación en silencio, y las que se resuelven desaparecen de ella.
     */
    private fun procesarAlertas(idNodo: String, data: LecturaData, momento: Long?) {
        val activas = evaluarAlertas(idNodo, data)
        val nuevas = AlertasActivas.registrar(this, idNodo, activas, momento)
        nuevas.forEach { registrarEnHistorial(it) }
        AlertasActivas.actualizarNotificacion(this, hayNuevas = nuevas.isNotEmpty())
    }

    /** Condiciones fuera de rango de una lectura (sin efectos: no notifica ni guarda). */
    private fun evaluarAlertas(idNodo: String, data: LecturaData): List<AlertaActiva> {
        val alertas = mutableListOf<AlertaActiva>()
        fun agregar(tipo: String, titulo: String, mensaje: String) {
            alertas.add(AlertaActiva(tipo, titulo, mensaje))
        }

        tempMaxConfig?.let { max ->
            if (data.tempAire > max) {
                agregar("CALOR", "🚨 Alerta de Golpe de Calor", "Temp. Aire en $idNodo de ${data.tempAire}°C (> $max°C). Riesgo de aborto de flores.")
            }
        }
        tempMinConfig?.let { min ->
            if (data.tempAire < min) {
                agregar("HELADA", "❄️ Alerta Temprana de Helada", "Temp. Aire en $idNodo cayó a ${data.tempAire}°C (< $min°C). Activar protección.")
            }
        }

        humSueloMinConfig?.let { minHum ->
            if (data.hum15 < minHum) {
                agregar("HUMEDAD_SUELO", "💧 Alerta Humedad Crítica", "Humedad 15cm en $idNodo cayó a ${data.hum15}% (< $minHum%). Riego urgente requerido.")
            }
        }

        vpdMaxConfig?.let { maxVpd ->
            if (data.vpd > maxVpd) {
                agregar("VPD_ALTO", "☀️ Estrés Hídrico (VPD)", "VPD en $idNodo alcanzó ${data.vpd} kPa (> $maxVpd kPa). Cierre estomático activo.")
            }
        }

        vpdMinConfig?.let { minVpd ->
            if (data.vpd < minVpd && data.humAire > 90.0) {
                agregar("FUNGICO", "🍄 Alerta Sanitaria Fúngica", "VPD en $idNodo es ${data.vpd} kPa y humedad ${data.humAire}%. Monitorear hongos.")
            }
        }

        tempRadicularMinConfig?.let { minRad ->
            if (data.tempSuelo < minRad) {
                agregar("RAIZ_FRIA", "🥶 Bloqueo Nutricional", "Temp. de raíz en $idNodo es ${data.tempSuelo}°C (< $minRad°C). Asimilación suspendida.")
            }
        }
        tempRadicularMaxConfig?.let { maxRad ->
            if (data.tempSuelo > maxRad) {
                agregar("RAIZ_CALIENTE", "🔥 Calentamiento Radicular", "Temp. de raíz en $idNodo es ${data.tempSuelo}°C (> $maxRad°C). Riesgo de anoxia.")
            }
        }

        if (data.bateria in 0.1..3.4) {
            agregar("BATERIA", "🔋 Alerta de Hardware / Batería", "Tensión en $idNodo bajo nivel crítico (${data.bateria}V). Limpiar panel solar.")
        }
        return alertas
    }

    /** Guarda el evento en la pestaña Alertas (mismo formato de siempre). */
    private fun registrarEnHistorial(alerta: AlertaActiva) {
        val prefs = Cuentas.prefs(this, Cuentas.PREFS_HISTORIAL)
        val historialPrevio = prefs.getString("lista_alertas", "") ?: ""

        val formatoFecha = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val fechaHora = formatoFecha.format(Date())
        val nuevaEntrada = "⚠️ [$fechaHora] ${alerta.titulo}: ${alerta.mensaje}"

        val historialNuevo = if (historialPrevio.isEmpty()) {
            nuevaEntrada
        } else {
            "$historialPrevio|$nuevaEntrada"
        }

        prefs.edit().putString("lista_alertas", historialNuevo).apply()
    }

    private fun abrirDetalleGrafico(variable: String, titulo: String) {
        val intent = Intent(this, DetalleGraficoActivity::class.java).apply {
            putExtra("VARIABLE", variable)
            putExtra("TITULO", titulo)
            putExtra("NODO_ID", nodoSeleccionadoActual)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        lecturaListener?.let { listener ->
            lecturaQuery?.removeEventListener(listener)
        }
        configListener?.let { listener ->
            configRef.removeEventListener(listener)
        }
        // Evita que una pantalla recreada (p. ej. al cambiar de tema) deje oyentes vivos
        nodosListener?.let { listener ->
            dbRef.removeEventListener(listener)
        }
        conexionListener?.let { listener ->
            conexionRef?.removeEventListener(listener)
        }
        manejadorEstado.removeCallbacksAndMessages(null)
    }

    companion object {
        // Mismos valores por defecto que usa ConfiguracionActivity al guardar
        private const val VPD_MIN_DEFECTO = 0.4
        private const val VPD_MAX_DEFECTO = 1.5


        // Plazo para dar por caída la conexión si Firebase nunca la confirma
        private const val PLAZO_VERIFICACION_MS = 8_000L
    }
}
