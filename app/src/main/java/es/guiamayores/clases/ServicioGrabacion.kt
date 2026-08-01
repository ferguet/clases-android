package es.guiamayores.clases

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * GRABAR AUNQUE LA PANTALLA ESTE EN OTRA COSA.
 *
 * EL PROBLEMA
 *
 * La grabacion vivia dentro de la pantalla. Y Android, en cuanto minimizas
 * una app, para lo que estaba haciendo: la pantalla deja de existir y el
 * micrófono se corta. O sea que para grabar una clase de dos horas habia
 * que dejar el movil quieto con la app delante todo el rato, sin mirar el
 * correo, sin apuntar nada, sin consultar una duda. Justo lo que nadie
 * hace en clase.
 *
 * Y lo peor no es que se corte, es que se corta SIN AVISAR: te encuentras
 * media clase al terminar.
 *
 * LA SOLUCION
 *
 * La misma que en Cuidame: un servicio en primer plano. Android permite
 * seguir trabajando con la pantalla apagada a cambio de una condicion que
 * ademas me parece justa -tiene que haber un aviso permanente visible-.
 * Nada de grabar a escondidas: mientras esto grabe, se ve en la barra de
 * notificaciones, con el tiempo corriendo.
 */
class ServicioGrabacion : Service() {

    private var grabadora: Grabadora? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PARAR) {
            terminar()
            return START_NOT_STICKY
        }

        crearCanal()
        val aviso = construirAviso()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_AVISO, aviso, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ID_AVISO, aviso)
        }

        try {
            grabadora = Grabadora(this).apply { empezar() }
            comenzadoEn = System.currentTimeMillis()
            grabando = true
            ultimoFallo = null
        } catch (e: Exception) {
            // Si no se puede grabar hay que decirlo, no quedarse con un
            // servicio vivo que no graba nada: eso seria exactamente la
            // mentira que esto viene a evitar.
            ultimoFallo = e.message ?: "no se pudo empezar a grabar"
            grabando = false
            stopSelf()
        }
        return START_STICKY
    }

    private fun terminar() {
        try {
            ficheroListo = grabadora?.parar()
        } catch (e: Exception) {
            ultimoFallo = e.message
            ficheroListo = null
        }
        grabando = false
        grabadora = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (grabando) terminar()
        super.onDestroy()
    }

    private fun construirAviso(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CANAL)
            .setContentTitle("Grabando la clase")
            .setContentText("Puede usar el móvil para otras cosas. Toque para volver.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(abrir)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Grabación en curso", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Aviso mientras se está grabando una clase." }
        )
    }

    companion object {
        private const val CANAL = "clases_grabando"
        private const val ID_AVISO = 77
        const val PARAR = "es.guiamayores.clases.PARAR"

        /** Estado compartido con la pantalla. */
        @Volatile var grabando: Boolean = false
            private set
        @Volatile var ficheroListo: File? = null
        @Volatile var ultimoFallo: String? = null
        @Volatile var comenzadoEn: Long = 0L

        fun arrancar(c: Context) {
            ficheroListo = null
            ultimoFallo = null
            val i = Intent(c, ServicioGrabacion::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i)
            else c.startService(i)
        }

        fun parar(c: Context) {
            c.startService(Intent(c, ServicioGrabacion::class.java).apply { action = PARAR })
        }

        fun segundosGrabando(): Long =
            if (comenzadoEn == 0L) 0 else (System.currentTimeMillis() - comenzadoEn) / 1000
    }
}
