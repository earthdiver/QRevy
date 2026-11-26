package qrevy.web

import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.session.*
import org.junit.Test
import kotlin.test.*

class QuickRecoveryHistoryTest {
    @Test fun automaticWarpHistoryCanBeExportedWithDefaultSettings() {
        val quad = ImageQuad(PointD(10.0,10.0),PointD(170.0,10.0),PointD(170.0,170.0),PointD(10.0,170.0))
        val warp = ThinPlateWarp(listOf(PointD(0.0,0.0),PointD(1.0,0.0),PointD(0.0,1.0)).map {
            WarpLandmark(it,PointD(0.0,0.0))
        })
        for (curved in listOf(false,true)) {
            val g = GridGeometry(1,FinderRole.TOP_LEFT,34.0,34.0,8.0,0.0,quad,
                if(curved) GeometryModel.THIN_PLATE_SPLINE else GeometryModel.PROJECTIVE,if(curved) warp else null)
            val saved = PreprocessSettings(version=1,distortionCorrection=false)
            val attempt = GeometryAttempt(GeometryHypothesis(emptyList(),g,0.0),QrMatrix(21),MatrixSampler.SamplingStats(128.0,255.0,0.0),emptyList())
            val history = QuickRecovery.historyState(saved,attempt,null)
            val settings = history["settings"] as Map<*,*>
            assertEquals(curved,settings["curved"])
            assertEquals(curved,settings["warp"] != null)
            val restored = saved.copy(manualQuad=g.moduleCenterQuad(),logicalGridAssigned=true,
                distortionCorrection=settings["curved"] as Boolean,previewGeometry=g.takeIf{settings["warp"] != null})
            val payload = RecoverySessionCodec.Payload("automatic-warp",1L,UiMode.ANALYSIS,
                listOf(RecoveryHistoryEntry(kind=HistoryEventKind.AUTO_RECOVERY,label="Automatic",
                    snapshot=RecoverySnapshot(g,null,null,-1,restored))),0)
            val decoded = RecoverySessionCodec.decode(RecoverySessionCodec.encode(payload))
            assertEquals(restored,decoded.historyEntries.single().snapshot.preprocessSettings)
        }
    }
}
