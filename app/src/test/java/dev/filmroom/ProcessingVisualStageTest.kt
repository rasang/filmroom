package dev.filmroom

import dev.filmroom.diagnostics.NativePhase
import dev.filmroom.ui.ProcessingVisualStage
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingVisualStageTest {
    @Test fun publicLabelsArePlainAndNeverExposePassPercentages() {
        assertEquals("读取照片", ProcessingVisualStage.READING.label)
        assertEquals("保存照片", ProcessingVisualStage.SAVING.label)
        assertEquals("取消并清理", ProcessingVisualStage.CLEANING.label)
        for (stage in ProcessingVisualStage.entries) {
            org.junit.Assert.assertTrue(stage.label.length <= 6)
            org.junit.Assert.assertFalse(stage.label.contains("%"))
        }
        assertEquals("生成胶片效果", ProcessingVisualStage.fromMessage("显影扩散 · 本遍 74% · 可取消").label)
    }

    @Test fun actualWorkerMessagesSelectOnlyTheirMatchingScene() {
        val cases = mapOf(
            "检查本机空间与预算 · 可取消" to ProcessingVisualStage.PREPARING,
            "读取照片数据 · 可取消" to ProcessingVisualStage.READING,
            "本机处理 · 胶片受光 · 本遍 27% · 可取消" to ProcessingVisualStage.EXPOSING,
            "本机处理 · 镜头柔光 · 本遍 5% · 可取消" to ProcessingVisualStage.HALATION,
            "本机处理 · 扫描锐化 · 本遍 12% · 可取消" to ProcessingVisualStage.PRINTING,
            "本机处理 · 胶片光晕 · 本遍 3% · 可取消" to ProcessingVisualStage.HALATION,
            "本机处理 · 显影扩散 · 本遍 6% · 可取消" to ProcessingVisualStage.DEVELOPING,
            "本机处理 · 形成颗粒云 · 本遍 10% · 可取消" to ProcessingVisualStage.GRAIN,
            "本机处理 · 模拟染料云 · 本遍 10% · 可取消" to ProcessingVisualStage.GRAIN,
            "转换受光密度 · 本遍 20%" to ProcessingVisualStage.DEVELOPING,
            "转换为对数曝光量 · 本遍 20%" to ProcessingVisualStage.DEVELOPING,
            "显影抑制扩散 · 本遍 20%" to ProcessingVisualStage.DEVELOPING,
            "本机处理 · 相纸与扫描 · 本遍 35% · 可取消" to ProcessingVisualStage.PRINTING,
            "正在编码并保存照片 · 可取消" to ProcessingVisualStage.SAVING,
            "正在取消并清理临时文件…" to ProcessingVisualStage.CLEANING,
            "本机处理胶片与相纸 · 可取消" to ProcessingVisualStage.PROCESSING,
            "未知状态" to ProcessingVisualStage.PROCESSING,
        )
        cases.forEach { (message, stage) -> assertEquals(message, stage, ProcessingVisualStage.fromMessage(message)) }
    }

    @Test fun percentagesCannotAdvanceTheScene() {
        for (percent in listOf(0, 1, 50, 99, 100)) {
            assertEquals(ProcessingVisualStage.GRAIN,
                ProcessingVisualStage.fromMessage("本机处理 · 颗粒成像 · 本遍 $percent% · 可取消"))
        }
        assertEquals(ProcessingVisualStage.PROCESSING, ProcessingVisualStage.fromMessage("处理完成"))
    }

    @Test fun allKnownNativePassesAreMappedWithoutPretendingCompleteMeansSaved() {
        val expected = mapOf(
            "setup" to ProcessingVisualStage.PREPARING,
            "auto_exposure" to ProcessingVisualStage.READING,
            "highlight_boost" to ProcessingVisualStage.EXPOSING,
            "camera_blur" to ProcessingVisualStage.HALATION,
            "scan_blur" to ProcessingVisualStage.PRINTING,
            "scan_glare" to ProcessingVisualStage.HALATION,
            "grain_microstructure" to ProcessingVisualStage.GRAIN,
            "print_diffusion" to ProcessingVisualStage.HALATION,
            "camera_diffusion" to ProcessingVisualStage.HALATION,
            "film_exposure" to ProcessingVisualStage.EXPOSING,
            "film_scatter" to ProcessingVisualStage.HALATION,
            "film_halation" to ProcessingVisualStage.HALATION,
            "film_log" to ProcessingVisualStage.DEVELOPING,
            "film_density" to ProcessingVisualStage.DEVELOPING,
            "dir_diffusion" to ProcessingVisualStage.DEVELOPING,
            "dir_develop" to ProcessingVisualStage.DEVELOPING,
            "grain_sampling" to ProcessingVisualStage.GRAIN,
            "grain_cloud" to ProcessingVisualStage.GRAIN,
            "grain_blur" to ProcessingVisualStage.GRAIN,
            "printing_scanning" to ProcessingVisualStage.PRINTING,
            "scan_sharpen" to ProcessingVisualStage.PRINTING,
            "complete" to ProcessingVisualStage.PROCESSING,
        )
        assertEquals(expected.keys, NativePhase.entries.map { it.id }.toSet())
        NativePhase.entries.forEach { phase ->
            val stage = requireNotNull(expected[phase.id]) { "Missing visual coverage for ${phase.id}" }
            assertEquals(phase.id, stage, ProcessingVisualStage.fromMessage("本机处理 · ${phase.chinese} · 可取消"))
        }
    }
}
