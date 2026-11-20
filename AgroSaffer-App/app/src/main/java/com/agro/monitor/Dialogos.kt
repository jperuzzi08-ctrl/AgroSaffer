package com.agro.monitor

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Diálogos compartidos entre pantallas. */
object Dialogos {

    /**
     * Doble verificación antes de quitar una cuenta del teléfono.
     * Muestra "Sí" (contorno rojo) a la izquierda y "No" (contorno verde) a la derecha.
     */
    fun confirmarQuitarCuenta(ctx: Context, email: String?, alConfirmar: () -> Unit) {
        val rojo = ContextCompat.getColor(ctx, R.color.sp_rojo)
        val verde = ContextCompat.getColor(ctx, R.color.sp_hoja)
        val icono = ContextCompat.getDrawable(ctx, R.drawable.ic_borrar)?.mutate()?.apply { setTint(rojo) }
        val cuenta = if (email.isNullOrBlank()) "esta cuenta" else email

        val dialogo = MaterialAlertDialogBuilder(ctx)
            .setIcon(icono)
            .setTitle("¿Quitar esta cuenta?")
            .setMessage(
                "Tendrás que volver a iniciar sesión con $cuenta " +
                    "escribiendo el correo y la contraseña.\n\n" +
                    "También se borra el historial de alertas guardado en este teléfono " +
                    "(tu cuenta y los datos de tus nodos en el servidor no se borran).\n\n" +
                    "¿Estás seguro?"
            )
            // Android ubica el botón "negativo" antes que el "positivo": para que
            // "Sí" quede a la izquierda, "Sí" ocupa el lugar del negativo.
            .setNegativeButton("Sí") { _, _ -> alConfirmar() }
            .setPositiveButton("No", null)
            .show()

        contornear(ctx, dialogo.getButton(DialogInterface.BUTTON_NEGATIVE), rojo, separarDelAnterior = false)
        contornear(ctx, dialogo.getButton(DialogInterface.BUTTON_POSITIVE), verde, separarDelAnterior = true)
    }

    /** Convierte un botón de texto del diálogo en un botón con contorno del [color] dado. */
    private fun contornear(ctx: Context, boton: Button?, color: Int, separarDelAnterior: Boolean) {
        val densidad = ctx.resources.displayMetrics.density
        (boton as? MaterialButton)?.apply {
            strokeColor = ColorStateList.valueOf(color)
            strokeWidth = (1.5f * densidad).toInt().coerceAtLeast(2)
            setTextColor(color)
            rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x33))
            val horizontal = (20 * densidad).toInt()
            setPadding(horizontal, paddingTop, horizontal, paddingBottom)
            if (separarDelAnterior) {
                (layoutParams as? ViewGroup.MarginLayoutParams)?.let { margenes ->
                    margenes.marginStart = (10 * densidad).toInt()
                    layoutParams = margenes
                }
            }
        }
    }
}
