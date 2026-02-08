package wtf.mazy.peel.util

import wtf.mazy.peel.model.DataManager

object EntryPointUtils {
    @JvmStatic
    fun entryPointReached() {
        DataManager.instance.loadAppData()
    }
}
