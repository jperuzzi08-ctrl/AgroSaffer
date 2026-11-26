package com.agro.monitor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class ConfiguracionActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var configRef: DatabaseReference
    private lateinit var lecturasRef: DatabaseReference

    private lateinit var etTempMax: EditText
    private lateinit var etTempMin: EditText
    private lateinit var etVpdMax: EditText
    private lateinit var etVpdMin: EditText
    private lateinit var etHumSueloMin: EditText
    private lateinit var etTempRadicularMin: EditText
    private lateinit var etTempRadicularMax: EditText
    private lateinit var btnGuardarConfig: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_configuracion)

        auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid ?: "DdaAJqXxIcadkzamNl16yWyy8k13"

        configRef = FirebaseDatabase.getInstance().getReference("usuarios").child(uid).child("configuracion")
        lecturasRef = FirebaseDatabase.getInstance().getReference("usuarios").child(uid).child("lecturas")

        etTempMax = findViewById(R.id.etTempMax)
        etTempMin = findViewById(R.id.etTempMin)
        etVpdMax = findViewById(R.id.etVpdMax)
        etVpdMin = findViewById(R.id.etVpdMin)
        etHumSueloMin = findViewById(R.id.etHumSueloMin)
        etTempRadicularMin = findViewById(R.id.etTempRadicularMin)
        etTempRadicularMax = findViewById(R.id.etTempRadicularMax)
        btnGuardarConfig = findViewById(R.id.btnGuardarConfig)

        // Al cambiar de tema la pantalla se recrea: los campos ya conservan lo
        // que el usuario escribió, así que solo se cargan de Firebase la primera vez.
        if (savedInstanceState == null) {
            cargarConfiguracionActual()
        }

        btnGuardarConfig.setOnClickListener { guardarConfiguracion() }

        findViewById<View>(R.id.opcionExportarExcel).setOnClickListener {
            ExportadorExcel.exportar(this, lecturasRef)
        }

        configurarCuenta()
        configurarSelectorTema()
        NavegacionInferior.configurar(this, R.id.nav_ajustes)
    }

    private fun configurarSelectorTema() {
        val grupo = findViewById<MaterialButtonToggleGroup>(R.id.grupoTema)
        val botonActual = when (PreferenciasApp.modoTema(this)) {
            PreferenciasApp.TEMA_NOCHE -> R.id.btnTemaNoche
            PreferenciasApp.TEMA_AUTO -> R.id.btnTemaAuto
            else -> R.id.btnTemaClaro
        }
        grupo.check(botonActual)

        grupo.addOnButtonCheckedListener { _, botonId, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            val modo = when (botonId) {
                R.id.btnTemaNoche -> PreferenciasApp.TEMA_NOCHE
                R.id.btnTemaAuto -> PreferenciasApp.TEMA_AUTO
                else -> PreferenciasApp.TEMA_CLARO
            }
            PreferenciasApp.cambiarTema(this, modo)
        }
    }

    private fun cargarConfiguracionActual() {
        configRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val tempMax = snapshot.child("tempMax").value?.toString()?.toDoubleOrNull()
                    val tempMin = snapshot.child("tempMin").value?.toString()?.toDoubleOrNull()
                    val vpdMax = snapshot.child("vpdMax").value?.toString()?.toDoubleOrNull()
                    val vpdMin = snapshot.child("vpdMin").value?.toString()?.toDoubleOrNull()
                    val humSueloMin = snapshot.child("humSueloMin").value?.toString()?.toDoubleOrNull()
                    val tempRadicularMin = snapshot.child("tempRadicularMin").value?.toString()?.toDoubleOrNull()
                    val tempRadicularMax = snapshot.child("tempRadicularMax").value?.toString()?.toDoubleOrNull()

                    tempMax?.let { etTempMax.setText(it.toString()) }
                    tempMin?.let { etTempMin.setText(it.toString()) }
                    vpdMax?.let { etVpdMax.setText(it.toString()) }
                    vpdMin?.let { etVpdMin.setText(it.toString()) }
                    humSueloMin?.let { etHumSueloMin.setText(it.toString()) }
                    tempRadicularMin?.let { etTempRadicularMin.setText(it.toString()) }
                    tempRadicularMax?.let { etTempRadicularMax.setText(it.toString()) }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@ConfiguracionActivity, "Error al cargar configuración", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun guardarConfiguracion() {
        val tempMax = etTempMax.text.toString().trim().toDoubleOrNull() ?: 35.0
        val tempMin = etTempMin.text.toString().trim().toDoubleOrNull() ?: 2.0
        val vpdMax = etVpdMax.text.toString().trim().toDoubleOrNull() ?: 1.5
        val vpdMin = etVpdMin.text.toString().trim().toDoubleOrNull() ?: 0.4
        val humSueloMin = etHumSueloMin.text.toString().trim().toDoubleOrNull() ?: 20.0
        val tempRadicularMin = etTempRadicularMin.text.toString().trim().toDoubleOrNull() ?: 12.0
        val tempRadicularMax = etTempRadicularMax.text.toString().trim().toDoubleOrNull() ?: 28.0

        val configMap = mapOf(
            "tempMax" to tempMax,
            "tempMin" to tempMin,
            "vpdMax" to vpdMax,
            "vpdMin" to vpdMin,
            "humSueloMin" to humSueloMin,
            "tempRadicularMin" to tempRadicularMin,
            "tempRadicularMax" to tempRadicularMax
        )

        configRef.setValue(configMap).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Toast.makeText(this, "✅ Parámetros agronómicos guardados", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "❌ Error al guardar datos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===================== Cuenta =====================

    private fun configurarCuenta() {
        val usuario = auth.currentUser
        val email = usuario?.email ?: "Sin sesión"
        findViewById<TextView>(R.id.tvEmailCuentaActual).text = email
        findViewById<TextView>(R.id.tvInicialCuentaActual).text =
            email.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

        // Opciones de cuenta, a la vista dentro de la tarjeta
        findViewById<View>(R.id.opcionCambiarCuenta).setOnClickListener { cambiarCuenta() }
        findViewById<View>(R.id.opcionCerrarSesion).setOnClickListener { cerrarSesion() }
        findViewById<View>(R.id.opcionQuitarCuenta).setOnClickListener { confirmarQuitarCuenta() }
    }

    /**
     * Cierra la sesión. La cuenta queda guardada en el inicio de sesión para volver
     * a entrar con un toque (solo se pide la contraseña).
     */
    private fun cerrarSesion() {
        Cuentas.cerrarSesion(this)
        irAlInicioDeSesion(otraCuenta = false)
    }

    /** Cierra la sesión y muestra las otras cuentas guardadas (o el formulario si no hay). */
    private fun cambiarCuenta() {
        val uidActual = auth.currentUser?.uid
        val hayOtras = Cuentas.guardadas(this).any { it.uid != uidActual }
        Cuentas.cerrarSesion(this)
        irAlInicioDeSesion(otraCuenta = !hayOtras)
    }

    /**
     * Doble verificación antes de quitar la cuenta: cierra la sesión y la borra
     * (junto con su historial local) de este teléfono.
     */
    private fun confirmarQuitarCuenta() {
        val usuario = auth.currentUser ?: return
        Dialogos.confirmarQuitarCuenta(this, usuario.email) {
            val uid = usuario.uid
            Cuentas.cerrarSesion(this)
            Cuentas.olvidar(this, uid)
            irAlInicioDeSesion(otraCuenta = false)
        }
    }

    private fun irAlInicioDeSesion(otraCuenta: Boolean) {
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (otraCuenta) putExtra(LoginActivity.EXTRA_MODO, LoginActivity.MODO_OTRA_CUENTA)
        }
        startActivity(intent)
        finish()
    }
}