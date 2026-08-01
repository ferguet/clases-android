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
    private lateinit var textoResultado: TextView
    private lateinit var listaClases: LinearLayout

    private var grabando = false
    private var cronometro: Chronometer? = null

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

        raiz.addView(hueco(30))
        raiz.addView(texto("CLASES ANTERIORES", 13f, Color.parseColor("#7E8AA0")))
        listaClases = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(listaClases)
        raiz.addView(boton("🔄 Actualizar lista", "#1D4ED8") { cargarLista() })

        cargarLista()
    }

    private fun alPulsarGrabar() {
        ajustes.ultimaAsignatura = campoAsignatura.text.toString()
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

        estado.text = "⏳ Transcribiendo… (puede tardar un poco)"
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
            val clases = try { servidor.listar() } catch (e: Exception) { emptyList() }
            listaClases.removeAllViews()
            if (clases.isEmpty()) {
                listaClases.addView(texto("Todavía no hay clases guardadas.", 14f, Color.parseColor("#6C7689")))
                return@launch
            }
            for (nombre in clases.take(20)) {
                listaClases.addView(botonPequeno(nombre) {
                    lifecycleScope.launch {
                        estado.text = "Cargando…"
                        textoResultado.text = try { servidor.leer(nombre) } catch (e: Exception) { "(error al leer)" }
                        estado.text = "Mostrando: $nombre"
                        estado.setTextColor(Color.parseColor("#9AA4B2"))
                    }
                })
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
