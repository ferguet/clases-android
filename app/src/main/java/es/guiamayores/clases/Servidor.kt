package es.guiamayores.clases

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Habla con el backend de /clases/transcribir.
 *
 * Un audio de una clase entera puede tardar bastante en subir con una
 * conexion movil floja, y el propio Whisper tarda un rato en procesarlo:
 * de ahi el timeout largo. Sin esto, una clase de una hora podria fallar
 * por "tiempo agotado" solo por ser larga, que seria el peor momento para
 * fallar -justo cuando mas merece la pena tenerla transcrita-.
 */
class Servidor(private val base: String) {

    private val cliente = OkHttpClient.Builder()
        // 90 segundos, no 30. El servidor vive en el plan gratuito de
        // Render, que se apaga solo tras 15 minutos sin uso: la primera
        // peticion despues de eso tiene que esperar a que arranque de
        // nuevo, y eso puede tardar medio minuto largo. Con 30 s fallaba
        // por "failed to connect" justo en ese caso -el peor momento para
        // fallar, porque parece un fallo de verdad y solo es el servidor
        // desperezandose-.
        .connectTimeout(90, TimeUnit.SECONDS)
        // Subir una clase de dos horas por datos moviles puede llevar un
        // buen rato, y el servidor ademas tiene que partirla y transcribir
        // trozo por trozo antes de contestar. Rendirse a los 5 minutos
        // dejaria tirada justo la clase larga, que es la que mas cuesta
        // volver a grabar.
        .writeTimeout(20, TimeUnit.MINUTES)
        .readTimeout(20, TimeUnit.MINUTES)
        .callTimeout(30, TimeUnit.MINUTES)
        .build()

    /** Lo que dijo el servidor la ultima vez sobre donde guarda las clases. */
    var ultimoGuardadoEn: String = ""
        private set

    data class Resultado(val texto: String, val fichero: String)

    /**
     * @param tipoMime el tipo real del fichero. Importa: un audio traido
     *   de otra app puede ser mp3, wav, ogg... y si se manda todo como
     *   "audio/mp4" el servicio de transcripcion puede rechazarlo por no
     *   corresponder el contenido con lo declarado.
     */
    suspend fun transcribir(
        audio: File, asignatura: String, tipoMime: String = "audio/mp4"
    ): Resultado = withContext(Dispatchers.IO) {
        val cuerpo = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio", audio.name,
                audio.asRequestBody(tipoMime.toMediaType())
            )
            .addFormDataPart("asignatura", asignatura.ifBlank { "sin_asignatura" })
            .build()

        val peticion = Request.Builder()
            .url("$base/clases/transcribir")
            .post(cuerpo)
            .build()

        cliente.newCall(peticion).execute().use { resp ->
            val texto = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw Exception(mensajeError(resp.code, texto))
            }
            val json = JSONObject(texto)
            Resultado(
                texto = json.optString("texto", "(sin texto)"),
                fichero = json.optString("fichero", "")
            )
        }
    }

    private fun mensajeError(codigo: Int, cuerpo: String): String = when (codigo) {
        413 -> "La grabación pesa demasiado para mandarla de una vez (más de 25 MB, " +
               "unos 60-70 minutos de audio). Divida la clase en dos grabaciones."
        502 -> "El servidor no ha podido hablar con el servicio de transcripción. " +
               "Puede ser un fallo pasajero: pruebe otra vez en un minuto."
        500 -> "El servidor no tiene configurada la clave de transcripción."
        else -> "Error del servidor ($codigo): ${cuerpo.take(200)}"
    }

    suspend fun listar(): List<String> = withContext(Dispatchers.IO) {
        val peticion = Request.Builder().url("$base/clases/listar").get().build()
        cliente.newCall(peticion).execute().use { resp ->
            // Antes esto devolvia lista vacia cuando fallaba, y era un
            // error: "no hay clases" y "no he podido preguntar" son cosas
            // distintas, y confundirlas deja a la persona mirando una
            // lista vacia sin saber que en realidad no hay conexion.
            val cuerpo = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw Exception(mensajeError(resp.code, cuerpo))
            val json = JSONObject(cuerpo)
            // Donde acaban guardadas las clases. Se enseña en pantalla:
            // saber si tus apuntes están a salvo o no, no debería ser algo
            // que la persona tenga que suponer.
            ultimoGuardadoEn = json.optString("guardado_en", "")
            val arr = json.optJSONArray("clases") ?: return@withContext emptyList()
            (0 until arr.length()).map { arr.getString(it) }
        }
    }

    data class Resumen(val texto: String, val fichero: String)

    /**
     * Pide el resumen de una clase ya transcrita. Puede tardar diez o
     * veinte segundos -la IA tiene que leer toda la clase-, de ahi que use
     * el mismo cliente de timeout largo que la transcripcion.
     */
    suspend fun resumir(ficheroTranscripcion: String): Resumen = withContext(Dispatchers.IO) {
        val peticion = Request.Builder()
            .url("$base/clases/resumir/$ficheroTranscripcion")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        cliente.newCall(peticion).execute().use { resp ->
            val cuerpo = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw Exception(mensajeError(resp.code, cuerpo))
            val json = JSONObject(cuerpo)
            Resumen(
                texto = json.optString("resumen", "(sin resumen)"),
                fichero = json.optString("fichero", "")
            )
        }
    }

    data class Avance(val estado: String, val porcentaje: Int, val parte: Int, val total: Int)

    /** Por donde va el resumen. Se pregunta cada pocos segundos mientras trabaja. */
    suspend fun progreso(fichero: String): Avance? = withContext(Dispatchers.IO) {
        try {
            val peticion = Request.Builder().url("$base/clases/progreso/$fichero").get().build()
            cliente.newCall(peticion).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val j = JSONObject(resp.body?.string() ?: "{}")
                Avance(
                    j.optString("estado", "?"), j.optInt("porcentaje", 0),
                    j.optInt("parte", 0), j.optInt("total", 0)
                )
            }
        } catch (e: Exception) { null }
    }

    suspend fun borrar(fichero: String) = withContext(Dispatchers.IO) {
        val peticion = Request.Builder().url("$base/clases/borrar/$fichero").delete().build()
        cliente.newCall(peticion).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception(mensajeError(resp.code, resp.body?.string() ?: ""))
        }
    }

    suspend fun leer(fichero: String): String = withContext(Dispatchers.IO) {
        val peticion = Request.Builder().url("$base/clases/leer/$fichero").get().build()
        cliente.newCall(peticion).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext "(no se pudo leer)"
            JSONObject(resp.body?.string() ?: "{}").optString("texto", "")
        }
    }
}
