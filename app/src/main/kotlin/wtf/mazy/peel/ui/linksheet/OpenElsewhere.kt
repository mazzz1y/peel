package wtf.mazy.peel.ui.linksheet

import android.content.ComponentName
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import wtf.mazy.peel.model.LinkChoice
import wtf.mazy.peel.ui.dialog.AddWebAppDialog
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.openInSystem
import wtf.mazy.peel.util.shareText

/** Opens a link outside the asking window; [onDone] runs once nothing more is shown for it. */
fun AppCompatActivity.openElsewhere(choice: LinkChoice.Elsewhere, onDone: () -> Unit = {}) {
    when (choice) {
        is LinkChoice.App -> BrowserLauncher.launch(choice.app, this, choice.url)
        is LinkChoice.Incognito -> BrowserLauncher.launchIncognito(this, choice.url)
        is LinkChoice.System -> openInSystem(
            Intent(Intent.ACTION_VIEW, choice.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
        )
        // Peel's own share target is left out, so the link can't come back into the sheet.
        is LinkChoice.Share -> shareText(
            choice.url,
            choice.title,
            excluded = listOf(ComponentName(this, Const.SHARE_RECEIVER_ALIAS)),
        )

        is LinkChoice.Add -> {
            AddWebAppDialog.show(this, prefill = choice.url, onConfirmed = onDone, onCancelled = onDone)
            return
        }
    }
    onDone()
}
