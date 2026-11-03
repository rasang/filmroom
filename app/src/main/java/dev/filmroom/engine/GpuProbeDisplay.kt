package dev.filmroom.engine

import java.util.Locale

internal object GpuProbeDisplay {
    private const val scope = "仅比较单块光学阶段，不代表整张导出速度或最终画质；照片仍使用 CPU 输出。"
    fun state(state: GpuProbeState): String = when (state.phase) {
        GpuProbePhase.IDLE -> state.lastSummary?.displayText() ?: "尚无 GPU 诊断结果。\n$scope"
        GpuProbePhase.PLANNING -> "正在验证私有诊断快照。\n$scope"
        GpuProbePhase.WAITING_FOR_MEMORY -> "正在等待安全内存额度。\n$scope"
        GpuProbePhase.BINDING -> "正在启动独立 GPU 诊断进程。\n$scope"
        GpuProbePhase.RUNNING -> "GPU 诊断正在运行。\n$scope"
        GpuProbePhase.STOPPING -> "正在等待诊断进程确认退出；退出前不会释放内存额度或启动新诊断。"
        GpuProbePhase.FINISHED -> state.lastSummary?.displayText() ?: "GPU 诊断结束。\n$scope"
    }
    fun summary(summary: GpuProbeSummary): String {
        val status = when (summary.status) {
            GpuProbeStatus.COMPLETE -> "GPU 诊断完成"
            GpuProbeStatus.CANCELLED -> "GPU 诊断已取消"
            GpuProbeStatus.START_REVOKED -> "GPU 工作未运行，启动已撤销"
            GpuProbeStatus.TIMED_OUT -> "GPU 诊断超时，未采用诊断结果"
            GpuProbeStatus.HELPER_DIED -> "GPU 诊断连接中断，未收到有效结果"
            GpuProbeStatus.MEMORY_UNAVAILABLE -> "内存余量不足，跳过 GPU 诊断"
            GpuProbeStatus.SNAPSHOT_INVALID -> "快照无效，跳过 GPU 诊断"
            GpuProbeStatus.NATIVE_UNAVAILABLE -> "此安装包不支持 GPU 诊断"
            GpuProbeStatus.GPU_UNAVAILABLE -> "当前设备或驱动不满足 GPU 诊断要求"
            GpuProbeStatus.NATIVE_FAILED -> "GPU 诊断失败，未采用 GPU 结果"
            GpuProbeStatus.PROTOCOL_REJECTED -> "GPU 诊断结果未通过安全校验"
            GpuProbeStatus.BIND_FAILED -> "无法连接 GPU 诊断进程"
        }
        val m = summary.measurements ?: return "$status。\n$scope"
        fun number(value: Double) = String.format(Locale.ROOT, "%.4g", value)
        return "$status：Vulkan f32 FFT；${if (m.deviceType == GpuProbeDeviceType.INTEGRATED_GPU) "集成" else "独立"} GPU，厂商编号 ${m.vendor}。\n" +
            "FFT ${m.fftSide}，原 PSF 半径 ${m.radius}，对比区域 ${m.roiWidth}×${m.roiHeight}。\n" +
            "阶段耗时（含上传/同步/读回）${number(m.stageTotalMs)} ms；初始化 ${number(m.initializationMs)} ms；准备 ${number(m.preparationMs)} ms；对比 ${number(m.comparisonMs)} ms；原生诊断合计 ${number(m.totalMs)} ms。\n" +
            "计划内存峰值 ${number(m.combinedPeakBytes.toDouble() / NativeMemoryPolicy.MIB)} MiB（估计值，不是实测 RSS）。\n" +
            "最大绝对误差 ${number(m.maxAbs)}；RMS ${number(m.rms)}；最大误差/参考峰值 ${number(m.maxAbsOverPeak)}；变化样本 ${m.changedSamples}。\n" +
            "逐位一致：${if (m.bitExact) "是" else "否"}；驱动声明 f64：${if (m.f64Advertised) "是（本实验仍用 f32）" else "否"}。\n$scope"
    }
}
