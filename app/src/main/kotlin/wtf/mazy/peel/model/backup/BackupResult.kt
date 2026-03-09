package wtf.mazy.peel.model.backup

sealed class BackupResult {
    data object Success : BackupResult()
    data object InvalidPayload : BackupResult()
    data object MissingGlobalSettings : BackupResult()
    data object Failure : BackupResult()
}
