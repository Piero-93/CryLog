package it.biagini.crylog.transport

import android.content.Context
import android.media.AudioAttributes
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * La factory di WebRTC, una sola per processo.
 *
 * `PeerConnectionFactory.initialize` carica una libreria nativa e va chiamata
 * una volta soltanto: chiamarla due volte non è un errore recuperabile.
 *
 * Il modulo audio è tenuto qui perché in prospettiva è il punto in cui la
 * cattura del microfono si unifica con quella del rilevamento rumore — due
 * `AudioRecord` concorrenti su Android sono fragili, e questo è il posto dove
 * risolverlo quando servirà.
 */
object WebRtcFactory {

    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null

    val eglBase: EglBase by lazy { EglBase.create() }

    @Synchronized
    fun get(context: Context): PeerConnectionFactory {
        factory?.let { return it }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )

        val audio = JavaAudioDeviceModule.builder(context.applicationContext)
            // Cancellazione d'eco e soppressione rumore sono pensate per le
            // telefonate: su un baby monitor toglierebbero proprio i suoni che
            // interessa sentire.
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            // Senza questo l'audio esce dall'auricolare, come in una telefonata,
            // e col telefono appoggiato sul comodino non si sente niente. Come
            // media esce dall'altoparlante e segue il volume multimediale.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .createAudioDeviceModule()

        val created = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        audioModule = audio
        factory = created
        return created
    }
}
