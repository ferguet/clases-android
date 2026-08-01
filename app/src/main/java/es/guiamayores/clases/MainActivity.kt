package es.guiamayores.clases

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * PRIMER PASO DEL PROYECTO DE CLASES: GRABAR Y TRANSCRIBIR. NADA MAS.
 *
 * Deliberadamente pequeño. Nada de entonacion, nada de examenes del MIR,
 * nada de diapositivas: eso son capas que se añaden despues, una vez esta
 * capa base -tener el audio convertido en texto limpio- funciona de
 * verdad. Es el mismo enfoque por fases que Cuidame.
 *
 * SIN DATOS DE PACIENTES. Esta pantalla es para clases y estudio. El modo
 * de practicas hospitalarias necesitaria su propio filtro de anonimizado
 * antes de guardar nada, y no esta aqui todavia.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ajustes: Ajustes
    private lateinit var grabadora: Grabadora
    private lateinit var servidor: Servidor

    private lateinit var estado: TextView
    private lateinit var botonGrabar: Button
    private lateinit var campoAsignatura: EditText
    private lateinit var campoServidor: EditText
    private lateinit var textoResultado: TextView
    private lateinit var listaClases: LinearLayout
    private lateinit var botonResumir: Button

    /** El fichero de la transcripcion que se está viendo ahora mismo, para
     *  poder pedir su resumen sin tener que volver a buscarlo. */
    private var ficheroVisible: String? = null

    private var grabando = false
    private var cronometro: Chronometer? = null

    /**
     * Elegir un audio de cualquier app o carpeta del movil.
     *
     * OpenDocument, no GetContent. Con GetContent, Android ofrecia las
     * apps de MUSICA como forma de "obtener un audio", y varias de ellas
     * lo que hacen al tocar un fichero es reproducirlo en vez de
     * devolverlo. La persona tocaba su grabacion, empezaba a sonar, y no
     * se adjuntaba nada: parecia que la app estaba rota.
     *
     * OpenDocument abre siempre el explorador de archivos del sistema,
     * que es lo que hace falta: elegir un fichero, no escucharlo.
     */
    private val elegirAudio = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) transcribirDeFuera(uri) }

    private val pedirPermiso = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { concedido -> if (concedido) empezarGrabacion() else avisar("Sin permiso de micrófono no se puede grabar.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ajustes = Ajustes(this)
        grabadora = Grabadora(this)
        servidor = Servidor(ajustes.servidor)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101828"))
            addView(raiz)
        })

        raiz.addView(texto("Clases", 34f, Color.WHITE, true))
        raiz.addView(texto(
            "Graba la clase, y al terminar se transcribe sola. Sin datos de " +
            "pacientes: esto es para clases y estudio.",
            15f, Color.parseColor("#9AA4B2")
        ))

        // LA DIRECCION DEL SERVIDOR, A LA VISTA Y EDITABLE.
        //
        // Estaba escondida en el codigo, y el resultado fue el previsible:
        // un "failed to connect" sin que ni la persona ni yo pudieramos
        // saber a que direccion estaba llamando en realidad. Un error de
        // conexion que no dice a donde intentaba conectarse no es un
        // mensaje de error, es una adivinanza.
        raiz.addView(hueco(20))
        raiz.addView(texto("SERVIDOR", 13f, Color.parseColor("#7E8AA0")))
        campoServidor = EditText(this).apply {
            setText(ajustes.servidor)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2434")); cornerRadius = 20f
            }
        }
        raiz.addView(campoServidor)
        raiz.addView(botonPequeno("🔌  Probar la conexión") { probarConexion() })

        raiz.addView(hueco(24))
        campoAsignatura = EditText(this).apply {
            hint = "Asignatura (opcional)"
            setText(ajustes.ultimaAsignatura)
            setHintTextColor(Color.parseColor("#6C7689"))
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2434")); cornerRadius = 20f
            }
        }
        raiz.addView(campoAsignatura)

        raiz.addView(hueco(20))
        estado = texto("Listo para grabar", 17f, Color.parseColor("#9AA4B2"))
        raiz.addView(estado)

        cronometro = Chronometer(this).apply {
            textSize = 40f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            visibility = android.view.View.GONE
        }
        raiz.addView(cronometro)

        botonGrabar = boton("🎙️  EMPEZAR A GRABAR", "#0B7A3B") { alPulsarGrabar() }
        raiz.addView(botonGrabar)

        // TRAER UN AUDIO DE FUERA.
        //
        // No todas las clases se graban con esta app: puede venir de la
        // grabadora del movil, de un audio que ha pasado un compañero, o
        // de una grabadora aparte. Si solo se pudiera transcribir lo
        // grabado aqui dentro, la app serviria de mucho menos justo el dia
        // que no pudiste ir a clase -que es cuando mas falta hace-.
        raiz.addView(boton("📂  TRAER UN AUDIO YA GRABADO", "#1D4ED8") {
            guardarServidor()
            try {
                // Se listan los tipos uno a uno en vez de "audio/*"
                // porque algunos exploradores esconden ficheros cuyo tipo
                // no reconocen: un .m4a exportado por otra grabadora
                // puede quedar en gris si solo se pide el comodin.
                elegirAudio.launch(arrayOf(
                    "audio/*", "audio/mpeg", "audio/mp4", "audio/x-m4a",
                    "audio/wav", "audio/x-wav", "audio/ogg", "audio/opus",
                    "application/octet-stream"
                ))
            } catch (e: Exception) {
                avisar("No se pudo abrir el explorador de archivos")
            }
        })

        raiz.addView(hueco(30))
        raiz.addView(texto("TRANSCRIPCIÓN", 13f, Color.parseColor("#7E8AA0")))
        textoResultado = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#D0D6E0"))
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2434")); cornerRadius = 20f
            }
            text = "Aún no hay ninguna transcripción."
        }
        raiz.addView(textoResultado)

        botonResumir = boton("📝  RESUMIR EN APUNTES", "#7C2D6E") { alPulsarResumir() }
        botonResumir.isEnabled = false
        botonResumir.alpha = 0.5f
        raiz.addView(botonResumir)

        raiz.addView(hueco(30))
        raiz.addView(texto("CLASES ANTERIORES", 13f, Color.parseColor("#7E8AA0")))
        listaClases = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(listaClases)
        raiz.addView(boton("🔄 Actualizar lista", "#1D4ED8") { cargarLista() })

        cargarLista()
    }

    private fun alPulsarGrabar() {
        ajustes.ultimaAsignatura = campoAsignatura.text.toString()
        guardarServidor()
        if (!grabando) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pedirPermiso.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                empezarGrabacion()
            }
        } else {
            pararYTranscribir()
        }
    }

    private fun empezarGrabacion() {
        try {
            grabadora.empezar()
            grabando = true
            botonGrabar.text = "⏹️  PARAR Y TRANSCRIBIR"
            botonGrabar.background = GradientDrawable().apply {
                setColor(Color.parseColor("#B91C1C")); cornerRadius = 26f
            }
            estado.text = "🔴 Grabando…"
            estado.setTextColor(Color.parseColor("#F87171"))
            cronometro?.visibility = android.view.View.VISIBLE
            cronometro?.base = android.os.SystemClock.elapsedRealtime()
            cronometro?.start()
        } catch (e: Exception) {
            avisar("No se pudo empezar a grabar: ${e.message}")
        }
    }

    private fun pararYTranscribir() {
        cronometro?.stop()
        cronometro?.visibility = android.view.View.GONE
        val fichero = grabadora.parar()
        grabando = false
        botonGrabar.text = "🎙️  EMPEZAR A GRABAR"
        botonGrabar.background = GradientDrawable().apply {
            setColor(Color.parseColor("#0B7A3B")); cornerRadius = 26f
        }

        if (fichero == null || !fichero.exists() || fichero.length() == 0L) {
            estado.text = "La grabación no se guardó bien. Inténtelo de nuevo."
            estado.setTextColor(Color.parseColor("#F87171"))
            return
        }

        estado.text = "⏳ Transcribiendo… (si el servidor estaba dormido, puede tardar medio minuto en despertar)"
        estado.setTextColor(Color.parseColor("#FACC15"))
        botonGrabar.isEnabled = false

        lifecycleScope.launch {
            try {
                servidor = Servidor(ajustes.servidor) // por si cambió la direccion
                val resultado = servidor.transcribir(fichero, campoAsignatura.text.toString())
                textoResultado.text = resultado.texto
                estado.text = "✅ Transcrito y guardado (${resultado.fichero})"
                estado.setTextColor(Color.parseColor("#4ADE80"))
                fichero.delete() // el audio ya no hace falta: el texto ya está a salvo en el servidor
                habilitarResumen(resultado.fichero)
                cargarLista()
            } catch (e: Exception) {
                estado.text = "❌ ${e.message}"
                estado.setTextColor(Color.parseColor("#F87171"))
            } finally {
                botonGrabar.isEnabled = true
            }
        }
    }

    private fun cargarLista() {
        lifecycleScope.launch {
            // EL BOTON QUE NO HACIA NADA.
            //
            // Antes esto era `try { ... } catch { emptyList() }`: si fallaba
            // la conexion, se tragaba el error y pintaba la misma lista de
            // siempre. Desde fuera parecia un boton roto, cuando en realidad
            // estaba avisando de algo importante -que no habia servidor- y
            // nadie lo oia. Un fallo que no se ve es peor que un fallo.
            listaClases.removeAllViews()
            val clases = try {
                servidor.listar()
            } catch (e: Exception) {
                estado.text = "❌ No se pudo consultar la lista.\n${e.message}"
                estado.setTextColor(Color.parseColor("#F87171"))
                listaClases.addView(texto(
                    "No se ha podido preguntar al servidor, así que no sé qué clases hay. " +
                    "Pruebe el botón de probar la conexión.",
                    14f, Color.parseColor("#F87171")
                ))
                return@launch
            }
            if (clases.isEmpty()) {
                listaClases.addView(texto("Todavía no hay clases guardadas.", 14f, Color.parseColor("#6C7689")))
                return@launch
            }
            for (nombre in clases.take(20)) {
                // Que se vea de un vistazo qué es cada cosa: la
                // transcripción literal o los apuntes ya resumidos.
                val etiqueta = if (nombre.endsWith("_resumen.txt"))
                    "📝  " + nombre.removeSuffix("_resumen.txt") + "  (apuntes)"
                else
                    "🎙️  " + nombre.removeSuffix(".txt")
                listaClases.addView(botonPequeno(etiqueta) {
                    lifecycleScope.launch {
                        estado.text = "Cargando…"
                        textoResultado.text = try { servidor.leer(nombre) } catch (e: Exception) { "(error al leer)" }
                        estado.text = "Mostrando: $nombre"
                        estado.setTextColor(Color.parseColor("#9AA4B2"))
                        // Los ficheros de resumen ("_resumen.txt") no se
                        // resumen otra vez: no tiene sentido pedirle a la
                        // IA que resuma su propio resumen.
                        if (nombre.endsWith("_resumen.txt")) {
                            botonResumir.isEnabled = false; botonResumir.alpha = 0.5f
                            ficheroVisible = null
                        } else {
                            habilitarResumen(nombre)
                        }
                    }
                })
            }
        }
    }

    /**
     * Dice si el servidor contesta, y si no, POR QUE no. Nada de "failed
     * to connect" a secas: el mensaje tiene que decir a donde llamaba y
     * que paso, o no sirve para arreglar nada.
     */
    /**
     * Coge un audio elegido de otra app y lo manda a transcribir.
     *
     * Hay que copiarlo antes a una carpeta propia: el "uri" que devuelve
     * el explorador de Android no es una ruta de fichero de verdad, sino
     * un permiso temporal para leerlo, y OkHttp necesita un fichero real
     * para subirlo.
     */
    private fun transcribirDeFuera(uri: android.net.Uri) {
        estado.text = "📂 Preparando el audio…"
        estado.setTextColor(Color.parseColor("#FACC15"))

        lifecycleScope.launch {
            var copia: java.io.File? = null
            try {
                val tipo = contentResolver.getType(uri) ?: "audio/mpeg"
                val nombre = nombreDelFichero(uri)

                copia = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val carpeta = java.io.File(cacheDir, "importados").apply { mkdirs() }
                    val destino = java.io.File(carpeta, nombre)
                    contentResolver.openInputStream(uri)?.use { entrada ->
                        destino.outputStream().use { salida -> entrada.copyTo(salida) }
                    } ?: throw Exception("No se pudo abrir el archivo elegido")
                    destino
                }

                // SIN TOPE DE TAMAÑO AQUI.
                //
                // Antes esta pantalla rechazaba todo lo que pasara de 25 MB.
                // Tenia sentido cuando el servidor no sabia partir audios,
                // pero desde que sabe, este tope solo servia para prohibir
                // lo que el servidor ya podia hacer: la web funcionaba con
                // clases grandes y el movil las rechazaba, por un limite que
                // ya no existia en ningun sitio salvo aqui.
                val mb = copia.length() / (1024.0 * 1024.0)
                estado.text = "⏳ Transcribiendo %s (%.1f MB)…\n".format(nombre, mb) +
                    "Las clases largas se parten en trozos, así que puede tardar varios minutos."
                estado.setTextColor(Color.parseColor("#FACC15"))

                servidor = Servidor(ajustes.servidor)
                val resultado = servidor.transcribir(copia, campoAsignatura.text.toString(), tipo)
                textoResultado.text = resultado.texto
                estado.text = "✅ Transcrito y guardado (${resultado.fichero})"
                estado.setTextColor(Color.parseColor("#4ADE80"))
                habilitarResumen(resultado.fichero)
                cargarLista()
            } catch (e: Exception) {
                estado.text = "❌ ${e.message}"
                estado.setTextColor(Color.parseColor("#F87171"))
            } finally {
                copia?.delete()
            }
        }
    }

    /** El nombre real del fichero elegido, para que el servidor lo reciba
     *  con su extension correcta (mp3, wav, m4a...). */
    private fun nombreDelFichero(uri: android.net.Uri): String {
        var nombre: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) nombre = c.getString(i)
            }
        } catch (e: Exception) {}
        return (nombre ?: "audio_importado.mp3").replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    /** Toma la direccion escrita en pantalla como la buena. */
    private fun guardarServidor() {
        val d = campoServidor.text.toString().trim().trimEnd('/')
        if (d.isNotBlank()) {
            ajustes.servidor = d
            servidor = Servidor(ajustes.servidor)
        }
    }

    private fun probarConexion() {
        val direccion = campoServidor.text.toString().trim().trimEnd('/')
        if (direccion.isBlank()) { avisar("Escriba una dirección primero"); return }
        ajustes.servidor = direccion
        servidor = Servidor(ajustes.servidor)

        estado.text = "⏳ Probando $direccion …\n(si el servidor está dormido tarda hasta un minuto)"
        estado.setTextColor(Color.parseColor("#FACC15"))

        lifecycleScope.launch {
            try {
                val clases = servidor.listar()
                estado.text = "✅ El servidor contesta.\n$direccion\n" +
                              "Clases guardadas: ${clases.size}\n" +
                              "Se guardan en: ${servidor.ultimoGuardadoEn}"
                estado.setTextColor(Color.parseColor("#4ADE80"))
                cargarLista()
            } catch (e: Exception) {
                estado.text = "❌ No contesta.\nDirección: $direccion\n" +
                              "Motivo: ${e.javaClass.simpleName} — ${e.message}"
                estado.setTextColor(Color.parseColor("#F87171"))
            }
        }
    }

    private fun habilitarResumen(fichero: String) {
        ficheroVisible = fichero
        botonResumir.isEnabled = true
        botonResumir.alpha = 1f
    }

    private fun alPulsarResumir() {
        val fichero = ficheroVisible ?: return
        botonResumir.isEnabled = false
        estado.text = "⏳ Convirtiendo en apuntes… (puede tardar medio minuto)"
        estado.setTextColor(Color.parseColor("#FACC15"))

        lifecycleScope.launch {
            try {
                val resultado = servidor.resumir(fichero)
                textoResultado.text = resultado.texto
                estado.text = "✅ Apuntes listos (${resultado.fichero})"
                estado.setTextColor(Color.parseColor("#4ADE80"))
                // El resumen ya se enseña; no tiene sentido resumir un
                // resumen, así que el botón se queda apagado hasta que se
                // cargue otra transcripción.
                ficheroVisible = null
                cargarLista()
            } catch (e: Exception) {
                estado.text = "❌ ${e.message}"
                estado.setTextColor(Color.parseColor("#F87171"))
                botonResumir.isEnabled = true
            }
        }
    }

    private fun avisar(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    // ---------- ayudas visuales ----------

    private fun texto(t: String, tam: Float, color: Int, negrita: Boolean = false) = TextView(this).apply {
        text = t; textSize = tam; setTextColor(color)
        if (negrita) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 10, 0, 10)
    }

    private fun boton(t: String, colorHex: String, alPulsar: () -> Unit) = Button(this).apply {
        text = t; textSize = 19f; setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = 26f }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 160)
            .apply { topMargin = 20 }
        setOnClickListener { alPulsar() }
    }

    private fun botonPequeno(t: String, alPulsar: () -> Unit) = Button(this).apply {
        text = t; textSize = 14f; setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply { setColor(Color.parseColor("#243044")); cornerRadius = 16f }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 110)
            .apply { topMargin = 8 }
        setOnClickListener { alPulsar() }
    }

    private fun hueco(alto: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, alto)
    }
}
