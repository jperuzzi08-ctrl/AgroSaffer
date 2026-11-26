package com.agro.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat

/** Una condición fuera de rango en un nodo (golpe de calor, helada, etc.). */
data class AlertaActiva(
    val tipo: String,
    val titulo: String,
    val mensaje: String
)

/**
 * Lleva las alertas activas de cada nodo y mantiene UNA sola notificación de Android
 * que se actualiza sola:
 *  - suena solo cuando aparece una alerta nueva;
 *  - mientras la condición sigue, se actualiza en silencio con los valores nuevos;
 *  - desaparece cuando todo vuelve a la normalidad;
 *  - si el usuario la descarta, no vuelve hasta que haya una alerta nueva.
 *
 * Una alerta es "nueva" si no se vio activa en los últimos 30 minutos de lecturas
 * (así un valor que oscila justo en el límite no suena a cada rato).
 */
object AlertasActivas {

    const val CANAL = "CANAL_ALERTAS_AGRO"
    private const val ID_NOTIFICACION = 4201
    private const val CLAVE_DESCARTADA = "descartada"

    // Firebase no permite "#" en las claves, así que no choca con nombres de nodo
    private const val SEP = "#"
    private const val PREFIJO_ACTIVAS = "activas$SEP"
    private const val PREFIJO_VISTO = "visto$SEP"

    /** Si una alerta reaparece dentro de este plazo, no se vuelve a anunciar. */
    const val ENFRIAMIENTO_MS = 30 * 60_000L

    private fun prefs(ctx: Context) = Cuentas.prefs(ctx, Cuentas.PREFS_ALERTAS_ACTIVAS)

    /** Quita la notificación (al cerrar sesión o cambiar de cuenta). */
    fun quitarNotificacion(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_NOTIFICACION)
    }

    fun crearCanal(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL,
                "Alertas Agronómicas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos de estrés hídrico, sanidad y mantenimiento de nodo"
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    /**
     * Guarda las alertas activas de [nodo] según su última lectura y devuelve las que
     * hay que anunciar (sonido + historial).
     *
     * @param momento hora de la lectura. Si es null (lectura sin fecha ya evaluada),
     *        se actualiza el contenido pero no se anuncia nada.
     */
    fun registrar(ctx: Context, nodo: String, alertas: List<AlertaActiva>, momento: Long?): List<AlertaActiva> {
        val p = prefs(ctx)
        val editor = p.edit()

        val vistos = alertas.mapNotNull { alerta ->
            val clave = "$PREFIJO_VISTO$nodo$SEP${alerta.tipo}"
            if (p.contains(clave)) alerta.tipo to p.getLong(clave, 0L) else null
        }.toMap()
        val (nuevas, vistosActualizados) = decidirNuevas(alertas, vistos, momento)
        vistosActualizados.forEach { (tipo, cuando) ->
            editor.putLong("$PREFIJO_VISTO$nodo$SEP$tipo", cuando)
        }

        val claveActivas = "$PREFIJO_ACTIVAS$nodo"
        if (alertas.isEmpty()) {
            editor.remove(claveActivas)
        } else {
            editor.putString(
                claveActivas,
                alertas.joinToString("\n") { "${it.tipo}\t${it.titulo}\t${it.mensaje}" }
            )
        }
        // Una alerta nueva vuelve a mostrar la notificación aunque se haya descartado
        if (nuevas.isNotEmpty()) editor.putBoolean(CLAVE_DESCARTADA, false)
        editor.apply()
        return nuevas
    }

    /**
     * Decide qué alertas anunciar (lógica pura, probada en AlertasActivasTest).
     *
     * @param vistos última vez (hora de lectura) que cada tipo estuvo activo en el nodo
     * @param momento hora de la lectura actual; null = no anunciar nada
     * @return las alertas a anunciar y los "vistos" a guardar
     */
    internal fun decidirNuevas(
        alertas: List<AlertaActiva>,
        vistos: Map<String, Long>,
        momento: Long?
    ): Pair<List<AlertaActiva>, Map<String, Long>> {
        if (momento == null) return emptyList<AlertaActiva>() to emptyMap()
        val nuevas = alertas.filter { alerta ->
            val visto = vistos[alerta.tipo]
            visto == null || momento - visto > ENFRIAMIENTO_MS
        }
        val actualizados = alertas
            .filter { alerta -> vistos[alerta.tipo].let { it == null || momento > it } }
            .associate { it.tipo to momento }
        return nuevas to actualizados
    }

    /** Alertas activas guardadas, por nodo. */
    fun activasPorNodo(ctx: Context): Map<String, List<AlertaActiva>> =
        prefs(ctx).all
            .filterKeys { it.startsWith(PREFIJO_ACTIVAS) }
            .mapNotNull { (clave, valor) ->
                val nodo = clave.removePrefix(PREFIJO_ACTIVAS)
                val alertas = (valor as? String).orEmpty().lines().mapNotNull { linea ->
                    val partes = linea.split("\t")
                    if (partes.size == 3) AlertaActiva(partes[0], partes[1], partes[2]) else null
                }
                if (alertas.isEmpty()) null else nodo to alertas
            }
            .toMap()
            .toSortedMap()

    /** Olvida los nodos que ya no existen en la cuenta. */
    fun podarNodos(ctx: Context, existentes: Collection<String>) {
        val p = prefs(ctx)
        val editor = p.edit()
        var cambio = false
        for (clave in p.all.keys) {
            val nodo = when {
                clave.startsWith(PREFIJO_ACTIVAS) -> clave.removePrefix(PREFIJO_ACTIVAS)
                clave.startsWith(PREFIJO_VISTO) -> clave.removePrefix(PREFIJO_VISTO).substringBeforeLast(SEP)
                else -> continue
            }
            if (nodo !in existentes) {
                editor.remove(clave)
                cambio = true
            }
        }
        if (cambio) editor.apply()
    }

    /** Lo llama el sistema cuando el usuario desliza la notificación para descartarla. */
    fun marcarDescartada(ctx: Context) {
        prefs(ctx).edit().putBoolean(CLAVE_DESCARTADA, true).apply()
    }

    /**
     * Publica, actualiza o quita la única notificación de alertas.
     * @param hayNuevas true si apareció una alerta nueva: suena y se muestra arriba.
     */
    fun actualizarNotificacion(ctx: Context, hayNuevas: Boolean) {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val activas = activasPorNodo(ctx)
        val lista = activas.flatMap { (nodo, alertas) -> alertas.map { nodo to it } }

        if (lista.isEmpty()) {
            // Todo volvió a la normalidad
            manager.cancel(ID_NOTIFICACION)
            prefs(ctx).edit().putBoolean(CLAVE_DESCARTADA, false).apply()
            return
        }

        // Descartada por el usuario y sin novedades: no se vuelve a mostrar
        if (!hayNuevas && prefs(ctx).getBoolean(CLAVE_DESCARTADA, false)) return

        val builder = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_hoja)
            .setColor(ContextCompat.getColor(ctx, R.color.sp_hoja))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(lista.size)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(false)
            .setContentIntent(abrirAlertas(ctx, activas.keys))
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    ctx, 0, Intent(ctx, DescarteAlertasReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

        if (lista.size == 1) {
            val (nodo, alerta) = lista[0]
            builder.setContentTitle(alerta.titulo)
                .setContentText(alerta.mensaje)
                .setSubText(nodo)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alerta.mensaje))
        } else {
            val nodos = activas.keys.joinToString(", ")
            val estilo = NotificationCompat.InboxStyle()
                .setBigContentTitle("⚠️ ${lista.size} alertas activas")
            lista.take(MAX_LINEAS).forEach { (nodo, alerta) ->
                estilo.addLine(if (activas.size > 1) "${alerta.titulo} · $nodo" else alerta.titulo)
            }
            if (lista.size > MAX_LINEAS) estilo.setSummaryText("y ${lista.size - MAX_LINEAS} más")
            builder.setContentTitle("⚠️ ${lista.size} alertas activas")
                .setContentText(lista.joinToString(" · ") { it.second.titulo })
                .setSubText(nodos)
                .setStyle(estilo)
        }

        if (hayNuevas) {
            // Algo nuevo: suena/vibra y aparece arriba aunque ya estuviera visible
            builder.setOnlyAlertOnce(false)
        } else {
            // Mismas alertas con valores nuevos: se actualiza en silencio
            builder.setOnlyAlertOnce(true).setSilent(true)
        }

        manager.notify(ID_NOTIFICACION, builder.build())
    }

    /** Tocar la notificación abre Alertas (con Inicio debajo para la barra inferior). */
    private fun abrirAlertas(ctx: Context, nodos: Set<String>): PendingIntent? {
        val alertas = Intent(ctx, HistorialActivity::class.java).apply {
            putExtra(
                HistorialActivity.EXTRA_FILTRO_NODO,
                if (nodos.size == 1) nodos.first() else NodoInfo.TODOS
            )
        }
        return TaskStackBuilder.create(ctx)
            .addNextIntent(Intent(ctx, ResumenActivity::class.java))
            .addNextIntent(alertas)
            .getPendingIntent(1, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private const val MAX_LINEAS = 6
}

/** Recibe el aviso de que el usuario descartó la notificación de alertas. */
class DescarteAlertasReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlertasActivas.marcarDescartada(context)
    }
}
