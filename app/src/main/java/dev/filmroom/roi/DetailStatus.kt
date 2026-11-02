package dev.filmroom.roi

import dev.filmroom.engine.DetailUnavailable
import dev.filmroom.engine.NativeWorkUnavailable
import java.io.IOException

/** Allowlisted UI diagnostics only: never exception messages, paths, source IDs or parameters. */
enum class DetailPhase { ZoomRequired, WaitingForPreview, Settling, Preparing, Refining, Ready, Comparing, Paused, Fallback }
enum class DetailFailure {
    Unsupported, MemoryBudget, MemoryAdmission, MemoryPressure, StorageBudget, AllocationFailed, SourceValidation, SourceRead, EngineUnavailable, DataNotReady, InvalidResult, RenderFailed
}
data class DetailStatus(val phase:DetailPhase,val width:Int=0,val height:Int=0,val readyCells:Int=0,val failure:DetailFailure?=null) {
    fun label():String=when(phase){
        DetailPhase.ZoomRequired->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b04a7122dd82)
        DetailPhase.WaitingForPreview->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_084663d93fc9)
        DetailPhase.Settling->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_fe1ca7194034)
        DetailPhase.Preparing->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2dd14bedbfd6)
        DetailPhase.Refining->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_fdaf1df9701c, maxOf(width,height))
        DetailPhase.Ready->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e3f7debc58f1, maxOf(width,height))
        DetailPhase.Comparing->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_328b93c4c2e6)
        DetailPhase.Paused->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ccd803200ef6)
        DetailPhase.Fallback->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9f37f3d6b8c1)+when(failure){
            DetailFailure.Unsupported->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b5dc9ebea612)
            DetailFailure.MemoryAdmission->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.detail_admission)
            DetailFailure.MemoryPressure->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.detail_pressure)
            DetailFailure.StorageBudget->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.detail_storage)
            DetailFailure.AllocationFailed->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.detail_allocation)
            DetailFailure.MemoryBudget->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d4e2c673d344)
            DetailFailure.SourceValidation->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b9dd18aa35d1)
            DetailFailure.SourceRead->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f5c716cc5054)
            DetailFailure.EngineUnavailable->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_dc0ed989d876)
            DetailFailure.DataNotReady->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_49947c7bfc5f)
            DetailFailure.InvalidResult->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c6851c16d664)
            else->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_744e3354f3e5)
        }
    }
}
internal fun detailFailure(error:Throwable?):DetailFailure=when(error){
    is DetailUnavailable->when(error.code){
        "UNSUPPORTED"->DetailFailure.Unsupported
        "BUDGET_EXCEEDED","WORKING_MEMORY_BUDGET"->DetailFailure.MemoryBudget
        "STORAGE_BUDGET"->DetailFailure.StorageBudget
        "RESOURCE_PRESSURE"->DetailFailure.MemoryPressure
        "UNAVAILABLE"->DetailFailure.EngineUnavailable
        "DATA_NOT_READY"->DetailFailure.DataNotReady
        "INVALID_RESULT"->DetailFailure.InvalidResult
        else->DetailFailure.RenderFailed
    }
    is NativeWorkUnavailable->DetailFailure.MemoryAdmission
    is OutOfMemoryError->DetailFailure.AllocationFailed
    is LinkageError->DetailFailure.EngineUnavailable
    is IllegalArgumentException,is SecurityException->DetailFailure.SourceValidation
    is IOException->DetailFailure.SourceRead
    null->DetailFailure.InvalidResult
    else->DetailFailure.RenderFailed
}
