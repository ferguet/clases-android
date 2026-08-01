package es.guiamayores.clases

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
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
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    data class Resultado(val texto: String, val fichero: String)

    suspend fun transcribir(audio: File, asignatura: String): Resultado = withContext(Dispatchers.IO) {
        val cuerpo = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio", audio.name,
                audio.asRequestBody("audio/mp4".toMediaType())
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
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string() ?: "{}")
            val arr = json.optJSONArray("clases") ?: return@withContext emptyList()
            (0 until arr.length()).map { arr.getString(it) }
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
