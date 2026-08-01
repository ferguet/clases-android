package es.guiamayores.clases

import android.content.Context

/**
 * Lo unico que esta app recuerda: la direccion del servidor de
 * transcripcion. Igual que en Cuidame, se guarda aqui y no repartido por
 * el codigo, para poder cambiarla sin recompilar.
 */
class Ajustes(contexto: Context) {
    private val p = contexto.getSharedPreferences("clases", Context.MODE_PRIVATE)

    var servidor: String
        get() = p.getString("servidor", "https://ai-council-ekax.onrender.com")
            ?: "https://ai-council-ekax.onrender.com"
        set(v) = p.edit().putString("servidor", v.trim().trimEnd('/')).apply()

    var ultimaAsignatura: String
        get() = p.getString("asignatura", "") ?: ""
        set(v) = p.edit().putString("asignatura", v).apply()
}
