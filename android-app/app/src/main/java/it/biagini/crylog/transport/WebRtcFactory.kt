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

        // Perche WebRTC veda l interfaccia della tailnet.
        //
        // Il monitor di rete di Android elenca le reti che gli passa
        // ConnectivityManager, e la tun di Tailscale non compare: misurato sui
        // candidati ICE, dove di 100.x non c era traccia e il media viaggiava
        // sulla LAN. Disattivandolo, WebRTC torna a enumerare le interfacce dal
        // sistema e la tun rientra. Le notifiche di cambio rete non servono
        // piu: ListenService ha il suo NetworkCallback.
        val options = PeerConnectionFactory.Options().apply {
            // Tutto tranne il loopback: 127.0.0.1 non porta da nessuna parte e
            // sarebbero due controlli di connettivita buttati per sessione.
            networkIgnoreMask = ADAPTER_TYPE_LOOPBACK
            disableNetworkMonitor = true
        }

        val created = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        audioModule = audio
        factory = created
        return created
    }

    /** Maschera dei tipi di interfaccia: il bit del loopback in libwebrtc. */
    private const val ADAPTER_TYPE_LOOPBACK = 1 shl 4

}
