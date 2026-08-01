package es.guiamayores.clases

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Envoltorio fino sobre MediaRecorder.
 *
 * Se graba en formato AAC/M4A, que es el que mejor entienden a la vez
 * Android y Whisper, y a una calidad de voz normal (no musica): asi el
 * fichero pesa poco. Una hora de clase en este formato ronda los 15-25 MB,
 * dentro del limite de 25 MB que impone la API de Groq.
 */
class Grabadora(private val contexto: Context) {

    private var grabador: MediaRecorder? = null
    var ficheroActual: File? = null
        private set

    fun empezar() {
        val carpeta = File(contexto.cacheDir, "grabaciones").apply { mkdirs() }
        val fichero = File(carpeta, "clase_${System.currentTimeMillis()}.m4a")
        ficheroActual = fichero

        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(contexto) else @Suppress("DEPRECATION") MediaRecorder()

        r.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Calidad de voz, no de musica: mantiene el fichero pequeño
            // para clases largas sin perder inteligibilidad del habla.
            setAudioEncodingBitRate(48_000)
            setAudioSamplingRate(22_050)
            setOutputFile(fichero.absolutePath)
            prepare()
            start()
        }
        grabador = r
    }

    /** Para de grabar y devuelve el fichero resultante, o null si algo fallo. */
    fun parar(): File? {
        return try {
            grabador?.apply { stop(); release() }
            grabador = null
            ficheroActual
        } catch (e: Exception) {
            grabador?.release()
            grabador = null
            null
        }
    }

    fun cancelar() {
        try { grabador?.release() } catch (e: Exception) {}
        grabador = null
        ficheroActual?.delete()
        ficheroActual = null
    }
}
