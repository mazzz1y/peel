package wtf.mazy.peel.activities

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import wtf.mazy.peel.util.withHostDensity

/**
 * Base for every Peel activity: applies the per-host density scale, so one set of dp values
 * renders correctly on handhelds, TVs and head units.
 */
abstract class PeelActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withHostDensity())
    }
}
