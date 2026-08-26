package it.biagini.crylog.transport

import android.content.Context
import android.util.Log
import org.webrtc.Camera2Enumerator
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * La fotocamera del Nursery Node.
 *
 * Si accende solo quando un Parent Node chiede il video e si spegne appena
 * smette di guardare: tenerla attiva scalda il telefono e consuma batteria per
 * riprendere, di notte, una stanza buia.
 *
 * Risoluzione modesta di proposito. Serve a vedere se il bambino è sveglio e
 * come è messo, non a leggere un libro: 640x480 basta, scalda meno e lascia a
 * WebRTC margine per adattare il bitrate da solo.
 */
class CameraSource(private val context: Context) {

    private var capturer: VideoCapturer? = null
    private var source: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null

    val isRunning: Boolean get() = capturer != null

    fun start(factory: PeerConnectionFactory, eglContext: org.webrtc.EglBase.Context): VideoTrack? {
        val enumerator = Camera2Enumerator(context)

        // La posteriore ha ottica e sensore migliori, ed è quella che punta
        // verso la culla quando il telefono è appoggiato in verticale.
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: run {
                Log.e(TAG, "nessuna fotocamera disponibile")
                return null
            }

        val created = enumerator.createCapturer(deviceName, null) ?: run {
            Log.e(TAG, "fotocamera non apribile: $deviceName")
            return null
        }

        val videoSource = factory.createVideoSource(created.isScreencast)
        val textureHelper = SurfaceTextureHelper.create("crylog-capture", eglContext)

        created.initialize(textureHelper, context, videoSource.capturerObserver)
        runCatching { created.startCapture(WIDTH, HEIGHT, FPS) }
            .onFailure {
                Log.e(TAG, "avvio cattura fallito: ${it.message}")
                textureHelper.dispose()
                videoSource.dispose()
                return null
            }

        capturer = created
        source = videoSource
        helper = textureHelper

        return factory.createVideoTrack("video", videoSource)
    }

    fun stop() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        source?.dispose()
        source = null
        helper?.dispose()
        helper = null
    }

    private companion object {
        const val TAG = "CryLogCamera"
        const val WIDTH = 640
        const val HEIGHT = 480
        const val FPS = 15
    }
}
