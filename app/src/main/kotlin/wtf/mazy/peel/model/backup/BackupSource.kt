package wtf.mazy.peel.model.backup

import wtf.mazy.peel.model.ParsedBackup

sealed interface BackupSource {
    data class Plain(val parsed: ParsedBackup) : BackupSource

    class Protected(
        val params: BackupCryptoParams,
        val associatedData: ByteArray,
        val payload: ByteArray,
    ) : BackupSource

    /** Claims to be protected but is unusable, so no password can open it. */
    data object Damaged : BackupSource

    data object NotABackup : BackupSource
}
