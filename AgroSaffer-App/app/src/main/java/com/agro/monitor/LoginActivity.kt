package com.agro.monitor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var etEmail: EditText
    private lateinit var etPass: EditText
    private lateinit var btnLogin: Button
    private lateinit var layoutCuentas: View
    private lateinit var layoutFormulario: View
    private lateinit var contenedorCuentas: LinearLayout
    private lateinit var tvTituloFormulario: TextView
    private lateinit var btnVerCuentas: View

    private var mostrandoFormulario = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        window.statusBarColor = ContextCompat.getColor(this, R.color.sp_amanecer)

        // 1. Inicialización de Firebase Auth
        auth = FirebaseAuth.getInstance()

        // Si ya había una sesión iniciada, pasa directamente a MainActivity
        auth.currentUser?.let { usuario ->
            Cuentas.recordar(this, usuario)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // 2. Referencias a vistas XML
        etEmail = findViewById(R.id.etEmail)
        etPass = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        layoutCuentas = findViewById(R.id.layoutCuentas)
        layoutFormulario = findViewById(R.id.layoutFormulario)
        contenedorCuentas = findViewById(R.id.contenedorCuentas)
        tvTituloFormulario = findViewById(R.id.tvTituloFormulario)
        btnVerCuentas = findViewById(R.id.btnVerCuentas)

        // 3. Acción de inicio de sesión (botón o tecla "Listo" del teclado)
        btnLogin.setOnClickListener { iniciarSesion() }
        etPass.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                iniciarSesion()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.btnOtraCuenta).setOnClickListener { mostrarFormulario(null) }
        btnVerCuentas.setOnClickListener { mostrarCuentas() }

        // Desde el formulario, "atrás" vuelve a la lista de cuentas guardadas
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mostrandoFormulario && Cuentas.guardadas(this@LoginActivity).isNotEmpty()) {
                    mostrarCuentas()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // 4. Pantalla inicial: cuentas guardadas, o el formulario si no hay / se pidió otra cuenta
        val pidioOtraCuenta = intent.getStringExtra(EXTRA_MODO) == MODO_OTRA_CUENTA
        if (pidioOtraCuenta || Cuentas.guardadas(this).isEmpty()) {
            mostrarFormulario(null)
        } else {
            mostrarCuentas()
        }
    }

    // ===================== Cuentas guardadas =====================

    private fun mostrarCuentas() {
        val cuentas = Cuentas.guardadas(this)
        if (cuentas.isEmpty()) {
            mostrarFormulario(null)
            return
        }
        mostrandoFormulario = false
        ocultarTeclado()
        layoutFormulario.visibility = View.GONE
        layoutCuentas.visibility = View.VISIBLE

        contenedorCuentas.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (cuenta in cuentas) {
            val tarjeta = inflater.inflate(R.layout.item_cuenta, contenedorCuentas, false)
            tarjeta.findViewById<TextView>(R.id.tvInicialCuenta).text = cuenta.inicial
            tarjeta.findViewById<TextView>(R.id.tvEmailCuenta).text = cuenta.email
            tarjeta.findViewById<TextView>(R.id.tvUltimoUsoCuenta).text = textoUltimoUso(cuenta.ultimoIngreso)
            tarjeta.contentDescription = "Entrar como ${cuenta.email}"
            // Tocar la cuenta: solo falta la contraseña
            tarjeta.setOnClickListener { mostrarFormulario(cuenta.email) }
            tarjeta.findViewById<View>(R.id.btnQuitarCuenta).setOnClickListener {
                confirmarQuitarCuenta(cuenta)
            }
            contenedorCuentas.addView(tarjeta)
        }
    }

    private fun textoUltimoUso(millis: Long): String {
        if (millis <= 0L) return "Guardada en este teléfono"
        val relativo = DateUtils.getRelativeTimeSpanString(
            millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        return "Último uso: $relativo"
    }

    private fun confirmarQuitarCuenta(cuenta: CuentaGuardada) {
        Dialogos.confirmarQuitarCuenta(this, cuenta.email) {
            Cuentas.olvidar(this, cuenta.uid)
            Toast.makeText(this, "Cuenta quitada de este teléfono", Toast.LENGTH_SHORT).show()
            mostrarCuentas()
        }
    }

    // ===================== Formulario =====================

    /** @param email cuenta guardada elegida, o null para escribir otra cuenta. */
    private fun mostrarFormulario(email: String?) {
        mostrandoFormulario = true
        layoutCuentas.visibility = View.GONE
        layoutFormulario.visibility = View.VISIBLE
        btnVerCuentas.visibility =
            if (Cuentas.guardadas(this).isNotEmpty()) View.VISIBLE else View.GONE
        etPass.text?.clear()

        if (email != null) {
            tvTituloFormulario.text = "Hola de nuevo 🌱"
            etEmail.setText(email)
            mostrarTeclado(etPass)
        } else {
            tvTituloFormulario.text = "Iniciá sesión"
            etEmail.text?.clear()
            mostrarTeclado(etEmail)
        }
    }

    private fun mostrarTeclado(campo: EditText) {
        campo.requestFocus()
        campo.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(campo, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun ocultarTeclado() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun iniciarSesion() {
        val email = etEmail.text.toString().trim()
        val pass = etPass.text.toString().trim()

        if (email.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Por favor, completá los campos", Toast.LENGTH_SHORT).show()
            return
        }

        mostrarCargando(true)

        // Autenticación nativa de Firebase
        auth.signInWithEmailAndPassword(email, pass)
            .addOnCompleteListener(this) { task ->
                val usuario = auth.currentUser
                if (task.isSuccessful && usuario != null) {
                    // Queda guardada para volver a entrar con un toque
                    Cuentas.recordar(this, usuario)
                    Toast.makeText(this, "¡Bienvenido!", Toast.LENGTH_SHORT).show()

                    val intent = Intent(this@LoginActivity, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    mostrarCargando(false)
                    Toast.makeText(
                        this@LoginActivity,
                        "Email o clave incorrectos",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    private fun mostrarCargando(cargando: Boolean) {
        btnLogin.isEnabled = !cargando
        btnLogin.text = if (cargando) "Entrando…" else "Entrar al campo"
    }

    companion object {
        /** Cómo abrir el inicio de sesión (desde Ajustes). */
        const val EXTRA_MODO = "MODO_LOGIN"

        /** Abre directamente el formulario para otra cuenta. */
        const val MODO_OTRA_CUENTA = "otra_cuenta"
    }
}
