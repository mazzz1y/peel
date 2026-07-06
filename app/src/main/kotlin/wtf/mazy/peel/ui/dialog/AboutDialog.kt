package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.BuildConfig
import wtf.mazy.peel.R

object AboutDialog {

    private const val GITHUB_URL = "https://github.com/mazzz1y/peel"
    private const val FDROID_URL = "https://mazzz1y.github.io/fdroid/repo"
    private const val GOOGLE_PLAY_URL =
        "https://play.google.com/store/apps/details?id=wtf.mazy.peel"
    private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.txt"

    fun show(activity: Activity) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.aboutVersion).text =
            activity.getString(R.string.about_version, BuildConfig.VERSION_NAME)
        view.findViewById<TextView>(R.id.aboutEngine).text =
            activity.getString(R.string.about_engine, BuildConfig.GECKOVIEW_VERSION)
        view.findViewById<View>(R.id.aboutGithub).setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, GITHUB_URL.toUri()))
        }
        view.findViewById<View>(R.id.aboutFdroid).setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, FDROID_URL.toUri()))
        }
        view.findViewById<View>(R.id.aboutGooglePlay).setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, GOOGLE_PLAY_URL.toUri()))
        }
        view.findViewById<View>(R.id.aboutLicense).setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, LICENSE_URL.toUri()))
        }
        MaterialAlertDialogBuilder(activity)
            .setView(view)
            .show()
    }
}
