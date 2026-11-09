package wtf.mazy.peel.model

import kotlinx.coroutines.CancellationException

sealed interface DataLoad {
    data object Loading : DataLoad
    data object Ready : DataLoad
    data class Failed(val cause: Throwable) : DataLoad
}

/**
 * Thrown to whoever waits for the data when it could not be opened. A cancellation, so waiting
 * background work simply stops; screens learn about the failure from [DataManager.load].
 */
class DataUnavailableException(cause: Throwable) :
    CancellationException("Peel's data could not be opened") {
    init {
        initCause(cause)
    }
}
