package it.biagini.crylog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import it.biagini.crylog.parent.RemoteVideo
import it.biagini.crylog.transport.WebRtcFactory
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Il video dalla cameretta.
 *
 * `SurfaceViewRenderer` è una View classica: Compose non ha un equivalente
 * perché WebRTC disegna direttamente su una superficie OpenGL.
 *
 * Il renderer si stacca in `onRelease`, altrimenti WebRTC continuerebbe a
 * disegnare su una superficie che non esiste più.
 */
@Composable
fun VideoView(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(WebRtcFactory.eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
            }
        },
        update = { renderer -> RemoteVideo.attach(renderer) },
        onRelease = { renderer ->
            RemoteVideo.detach(renderer)
            renderer.release()
        },
    )
}
