package com.agro.monitor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Genera un archivo .xlsx real (sin librerías externas) y lo guarda en el teléfono.
 *
 * El libro tiene dos hojas:
 *  - Resumen: el último estado de cada nodo.
 *  - Lecturas: todas las mediciones recibidas, en orden cronológico.
 */
object ExportadorExcel {

    private const val MIME_XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val CARPETA_DESCARGAS = "AgroSaffer"

    @Volatile
    private var exportando = false

    fun exportar(context: Context, dbRef: DatabaseReference) {
        if (exportando) {
            avisar(context, "Ya se está generando un Excel…")
            return
        }
        exportando = true
        avisar(context, "Generando Excel con todos los datos…")

        val appContext = context.applicationContext
        // Para mostrar el diálogo final en la pantalla que pidió la exportación
        val pantalla = WeakReference<Activity>(context as? Activity)
        dbRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    exportando = false
                    avisar(appContext, "No hay datos para exportar")
                    return
                }

                val subtitulos = snapshot.children.associate { nodo ->
                    nodo.key.orEmpty() to PreferenciasApp.subtituloNodo(appContext, nodo.key.orEmpty())
                }

                Thread {
                    try {
                        val bytes = construirXlsx(snapshot, subtitulos)
                        val nombre = "AgroSaffer_Datos_${fechaArchivo()}.xlsx"
                        val guardado = guardar(appContext, bytes, nombre)
                        mostrarListo(pantalla, appContext, guardado)
                    } catch (e: Exception) {
                        avisar(appContext, "No se pudo crear el Excel: ${e.message}")
                    } finally {
                        exportando = false
                    }
                }.start()
            }

            override fun onCancelled(error: DatabaseError) {
                exportando = false
                avisar(appContext, "No se pudieron leer los datos: ${error.message}")
            }
        })
    }

    // ===================== Archivo XLSX =====================

    private data class Celda(
        val texto: String? = null,
        val numero: Double? = null,
        val estilo: Int = 0
    )

    private data class Hoja(
        val nombre: String,
        val encabezados: List<String>,
        val filas: List<List<Celda>>
    )

    private fun construirXlsx(
        snapshot: DataSnapshot,
        subtitulos: Map<String, String>
    ): ByteArray {
        val nodos = snapshot.children.sortedBy { it.key.orEmpty() }

        val filasResumen = mutableListOf<List<Celda>>()
        val filasLecturas = mutableListOf<List<Celda>>()

        for (nodo in nodos) {
            val id = nodo.key.orEmpty()
            val subtitulo = subtitulos[id].orEmpty()
            val ultima = Lecturas.ultima(nodo)
            val info = NodoInfo(id, ultima != null, ultima?.let { Lecturas.millis(it) })
            val lectura = ultima?.let { LecturaData.desde(it) }

            filasResumen.add(
                listOf(
                    Celda(texto = id),
                    Celda(texto = subtitulo),
                    Celda(texto = info.estado.etiqueta),
                    Celda(texto = fechaTexto(info.millisUltima)),
                    numero(lectura?.vpd),
                    numero(lectura?.tempAire),
                    numero(lectura?.humAire),
                    numero(lectura?.presion),
                    numero(lectura?.hum15?.toDouble()),
                    numero(lectura?.hum40?.toDouble()),
                    numero(lectura?.tempSuelo),
                    numero(lectura?.bateria)
                )
            )

            val lecturas = nodo.children.mapNotNull { hijo ->
                val millis = Lecturas.millis(hijo)
                val data = LecturaData.desde(hijo)
                Triple(millis, hijo.key.orEmpty(), data)
            }.sortedWith(compareBy<Triple<Long?, String, LecturaData>>(
                { it.first ?: Long.MAX_VALUE },
                { it.second }
            ))

            for ((millis, clave, data) in lecturas) {
                filasLecturas.add(
                    listOf(
                        Celda(texto = id),
                        Celda(texto = subtitulo),
                        Celda(texto = fechaTexto(millis)),
                        numero(data.vpd),
                        numero(data.tempAire),
                        numero(data.humAire),
                        numero(data.presion),
                        numero(data.hum15.toDouble()),
                        numero(data.hum40.toDouble()),
                        numero(data.tempSuelo),
                        numero(data.bateria),
                        Celda(texto = clave)
                    )
                )
            }
        }

        val hojaResumen = Hoja(
            "Resumen",
            listOf(
                "Nodo", "Subtítulo", "Estado", "Última lectura", "VPD (kPa)",
                "Temp. aire (°C)", "Hum. aire (%)", "Presión (hPa)",
                "Hum. 15 cm (%)", "Hum. 40 cm (%)", "Temp. suelo (°C)", "Batería (V)"
            ),
            filasResumen
        )
        val hojaLecturas = Hoja(
            "Lecturas",
            listOf(
                "Nodo", "Subtítulo", "Fecha y hora", "VPD (kPa)",
                "Temp. aire (°C)", "Hum. aire (%)", "Presión (hPa)",
                "Hum. 15 cm (%)", "Hum. 40 cm (%)", "Temp. suelo (°C)",
                "Batería (V)", "Registro"
            ),
            filasLecturas
        )

        return crearLibro(listOf(hojaResumen, hojaLecturas))
    }

    private fun numero(valor: Double?): Celda =
        if (valor == null || valor.isNaN() || valor.isInfinite()) Celda() else Celda(numero = valor)

    private fun fechaTexto(millis: Long?): String =
        millis?.let { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(it)) }.orEmpty()

    private fun fechaArchivo(): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())

    private fun crearLibro(hojas: List<Hoja>): ByteArray {
        val salida = ByteArrayOutputStream()
        ZipOutputStream(salida).use { zip ->
            fun agregar(nombre: String, contenido: String) {
                zip.putNextEntry(ZipEntry(nombre))
                zip.write(contenido.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            agregar("[Content_Types].xml", contentTypes(hojas.size))
            agregar("_rels/.rels", RELS_RAIZ)
            agregar("xl/workbook.xml", workbook(hojas))
            agregar("xl/_rels/workbook.xml.rels", workbookRels(hojas.size))
            agregar("xl/styles.xml", ESTILOS)
            hojas.forEachIndexed { indice, hoja ->
                agregar("xl/worksheets/sheet${indice + 1}.xml", worksheet(hoja, indice == 0))
            }
        }
        return salida.toByteArray()
    }

    private fun contentTypes(cantidadHojas: Int): String = buildString {
        append(XML_DECLARACION)
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        for (i in 1..cantidadHojas) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        append("</Types>")
    }

    private fun workbook(hojas: List<Hoja>): String = buildString {
        append(XML_DECLARACION)
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        hojas.forEachIndexed { indice, hoja ->
            append("""<sheet name="${escapar(nombreHoja(hoja.nombre))}" sheetId="${indice + 1}" r:id="rId${indice + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(cantidadHojas: Int): String = buildString {
        append(XML_DECLARACION)
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..cantidadHojas) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${cantidadHojas + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun worksheet(hoja: Hoja, seleccionada: Boolean): String {
        val totalFilas = hoja.filas.size + 1
        val totalColumnas = hoja.encabezados.size
        val ultimaColumna = letraColumna(totalColumnas)
        return buildString {
            append(XML_DECLARACION)
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            append("""<dimension ref="A1:$ultimaColumna$totalFilas"/>""")
            append("<sheetViews><sheetView")
            if (seleccionada) append(""" tabSelected="1"""")
            append(""" workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
            append("""<sheetFormatPr defaultRowHeight="15"/>""")
            append("<cols>")
            for (i in 1..totalColumnas) {
                val ancho = when (i) {
                    1 -> 22
                    2 -> 24
                    3 -> 20
                    else -> 14
                }
                append("""<col min="$i" max="$i" width="$ancho" customWidth="1"/>""")
            }
            append("</cols><sheetData>")

            append("""<row r="1" ht="22" customHeight="1">""")
            hoja.encabezados.forEachIndexed { indice, texto ->
                append(celdaTexto("${letraColumna(indice + 1)}1", texto, 1))
            }
            append("</row>")

            hoja.filas.forEachIndexed { indiceFila, fila ->
                val numeroFila = indiceFila + 2
                append("""<row r="$numeroFila">""")
                fila.forEachIndexed celda@ { indiceColumna, celda ->
                    if (celda.texto == null && celda.numero == null) return@celda
                    val referencia = "${letraColumna(indiceColumna + 1)}$numeroFila"
                    if (celda.numero != null) {
                        append("""<c r="$referencia"><v>${celda.numero}</v></c>""")
                    } else {
                        append(celdaTexto(referencia, celda.texto.orEmpty(), celda.estilo))
                    }
                }
                append("</row>")
            }
            append("</sheetData>")
            if (totalFilas > 1) {
                append("""<autoFilter ref="A1:$ultimaColumna$totalFilas"/>""")
            }
            append("</worksheet>")
        }
    }

    private fun celdaTexto(referencia: String, texto: String, estilo: Int): String {
        val atributoEstilo = if (estilo > 0) """ s="$estilo"""" else ""
        return """<c r="$referencia"$atributoEstilo t="inlineStr"><is><t xml:space="preserve">${escapar(texto)}</t></is></c>"""
    }

    private fun letraColumna(numero: Int): String {
        var n = numero
        val letras = StringBuilder()
        while (n > 0) {
            val resto = (n - 1) % 26
            letras.insert(0, ('A' + resto))
            n = (n - 1) / 26
        }
        return letras.toString()
    }

    private fun nombreHoja(nombre: String): String =
        nombre.replace(Regex("""[\\/?*\[\]:]"""), "_").take(31).ifBlank { "Hoja" }

    private fun escapar(texto: String): String = buildString {
        for (caracter in texto.take(32767)) {
            when (caracter) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (caracter.code >= 0x20 || caracter == '\t' || caracter == '\n' || caracter == '\r') {
                    append(caracter)
                }
            }
        }
    }

    // ===================== Guardado =====================

    /** Dónde quedó el archivo: texto para mostrar y Uri para compartirlo o abrirlo. */
    private class Guardado(val ruta: String, val uri: Uri)

    private fun guardar(context: Context, bytes: ByteArray, nombre: String): Guardado {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_XLSX)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + CARPETA_DESCARGAS
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                ?: throw IOException("No se pudo crear el archivo en Descargas")
            try {
                resolver.openOutputStream(uri)?.use { salida -> salida.write(bytes) }
                    ?: throw IOException("No se pudo escribir el archivo")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            valores.clear()
            valores.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, valores, null, null)
            return Guardado("Descargas/$CARPETA_DESCARGAS/$nombre", uri)
        }

        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val carpeta = File(base, CARPETA_DESCARGAS)
        if (!carpeta.exists() && !carpeta.mkdirs()) {
            throw IOException("No se pudo crear la carpeta de exportación")
        }
        val archivo = File(carpeta, nombre)
        archivo.writeBytes(bytes)
        // FileProvider permite pasarle el archivo a otras apps (Android 9 o anterior)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.archivos", archivo)
        return Guardado(archivo.absolutePath, uri)
    }

    /**
     * Avisa que el Excel está listo y ofrece compartirlo (WhatsApp, Drive, correo…)
     * o abrirlo. Si la pantalla ya se cerró, alcanza con un aviso.
     */
    private fun mostrarListo(pantalla: WeakReference<Activity>, appContext: Context, guardado: Guardado) {
        Handler(Looper.getMainLooper()).post {
            val activity = pantalla.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                Toast.makeText(appContext, "Excel guardado en ${guardado.ruta}", Toast.LENGTH_LONG).show()
                return@post
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Excel listo 🌱")
                .setMessage("Se guardó en ${guardado.ruta}")
                .setPositiveButton("Compartir") { _, _ -> compartir(activity, guardado.uri) }
                .setNeutralButton("Abrir") { _, _ -> abrir(activity, guardado.uri) }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun compartir(activity: Activity, uri: Uri) {
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = MIME_XLSX
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Datos de AgroSaffer")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(envio, "Compartir Excel"))
    }

    private fun abrir(activity: Activity, uri: Uri) {
        val ver = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_XLSX)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(ver)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                activity,
                "No hay una app para abrir Excel. Probá con Compartir.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun avisar(context: Context, mensaje: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, mensaje, Toast.LENGTH_LONG).show()
        }
    }

    private const val XML_DECLARACION = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    private const val RELS_RAIZ = XML_DECLARACION +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
        "</Relationships>"

    private const val ESTILOS = XML_DECLARACION +
        """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
        """<fonts count="2">""" +
        """<font><sz val="11"/><name val="Calibri"/></font>""" +
        """<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>""" +
        "</fonts>" +
        """<fills count="3">""" +
        """<fill><patternFill patternType="none"/></fill>""" +
        """<fill><patternFill patternType="gray125"/></fill>""" +
        """<fill><patternFill patternType="solid"><fgColor rgb="FF2F5D3A"/><bgColor indexed="64"/></patternFill></fill>""" +
        "</fills>" +
        """<borders count="1"><border/></borders>""" +
        """<cellStyleXfs count="1"><xf/></cellStyleXfs>""" +
        """<cellXfs count="2">""" +
        """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>""" +
        "</cellXfs>" +
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
        "</styleSheet>"
}
