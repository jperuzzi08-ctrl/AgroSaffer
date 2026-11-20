package com.agro.monitor

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat

/**
 * Adaptador de nodos para los Spinner de Inicio, Tendencias y Alertas.
 * En la lista desplegable cada nodo lleva un círculo de color con su estado
 * (en línea / demorado / sin señal / sin datos) y hace cuánto envió datos.
 *
 * @param layoutSeleccion vista que se muestra con el spinner cerrado; debe tener
 *        un TextView R.id.tvNombreNodo y, opcionalmente, un círculo R.id.puntoNodo.
 * @param detalleExtra texto opcional que se agrega al detalle de cada fila
 *        (en Alertas: cuántas alertas tiene cada nodo).
 */
class AdaptadorNodos(
    private val context: Context,
    @LayoutRes private val layoutSeleccion: Int,
    private val detalleExtra: ((NodoInfo) -> String?)? = null
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private var nodos: List<NodoInfo> = emptyList()

    fun actualizar(nuevos: List<NodoInfo>) {
        nodos = nuevos
        notifyDataSetChanged()
    }

    /** Vuelve a pintar estados y textos (cambian con el paso del tiempo). */
    fun refrescarEstados() = notifyDataSetChanged()

    fun ids(): List<String> = nodos.map { it.id }

    fun posicionDe(id: String?): Int = nodos.indexOfFirst { it.id == id }

    override fun getCount(): Int = nodos.size

    override fun getItem(position: Int): NodoInfo = nodos[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val vista = convertView ?: inflater.inflate(layoutSeleccion, parent, false)
        val info = nodos[position]
        vista.findViewById<TextView>(R.id.tvNombreNodo).text = nombre(info)
        vista.findViewById<View>(R.id.puntoNodo)?.let { punto ->
            punto.visibility = if (info.esTodos) View.GONE else View.VISIBLE
            pintarPunto(punto, info.estado)
        }
        vista.contentDescription = "${nombre(info)}, ${detalle(info)}"
        return vista
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val vista = convertView ?: inflater.inflate(R.layout.item_nodo, parent, false)
        val info = nodos[position]
        vista.findViewById<TextView>(R.id.tvNombreNodo).text = nombre(info)
        vista.findViewById<TextView>(R.id.tvEstadoNodo).text = detalle(info)
        vista.findViewById<View>(R.id.puntoNodo).let { punto ->
            // "Todos los nodos" no tiene estado propio: se deja el hueco para alinear
            punto.visibility = if (info.esTodos) View.INVISIBLE else View.VISIBLE
            pintarPunto(punto, info.estado)
        }
        vista.contentDescription = "${nombre(info)}, ${detalle(info)}"
        return vista
    }

    private fun nombre(info: NodoInfo): String = if (info.esTodos) "Todos los nodos" else info.id

    private fun detalle(info: NodoInfo): String {
        val extra = detalleExtra?.invoke(info)
        return if (info.esTodos) {
            extra ?: "Todos los nodos de la cuenta"
        } else {
            listOfNotNull(info.detalle(), extra).joinToString(" · ")
        }
    }

    private fun pintarPunto(punto: View, estado: EstadoNodo) {
        punto.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, estado.color))
    }
}
