package wtf.mazy.peel.ui.extensions

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import wtf.mazy.peel.R
import java.util.concurrent.atomic.AtomicLong

class ExtensionPopupBottomSheet : BottomSheetDialogFragment() {

    private var session: GeckoSession? = null
    private var geckoView: GeckoView? = null
    private var title: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = arguments?.getLong(ARG_TOKEN) ?: -1L
        val pending = reclaim(token)
        session = pending?.session
        title = pending?.title
        if (session == null) dismissAllowingStateLoss()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also {
            (it as BottomSheetDialog).dismissWithAnimation = true
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_extension_popup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = title ?: getString(R.string.extensions)
        toolbar.setNavigationOnClickListener { dismissAllowingStateLoss() }

        geckoView = view.findViewById(R.id.popup_gecko_view)
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface,
            tv,
            true
        )
        geckoView?.coverUntilFirstPaint(tv.data)
        session?.let { geckoView?.setSession(it) }
    }

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        bottomSheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        bottomSheet.requestLayout()
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.isDraggable = false
        behavior.isFitToContents = false
        behavior.halfExpandedRatio = 0.90f
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheet.post {
            behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }

    override fun onDestroyView() {
        geckoView?.releaseSession()
        geckoView = null
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        session?.close()
        session = null
        super.onDismiss(dialog)
    }

    fun dismissImmediately() {
        (dialog as? BottomSheetDialog)?.dismissWithAnimation = false
        dismissAllowingStateLoss()
    }

    private data class Pending(val session: GeckoSession, val title: String?)

    companion object {
        internal const val TAG = "ExtensionPopupSheet"
        private const val ARG_TOKEN = "token"

        private val nextToken = AtomicLong(0L)
        private val pendingByToken = mutableMapOf<Long, Pending>()

        @Synchronized
        private fun stash(session: GeckoSession, title: String?): Long {
            val token = nextToken.incrementAndGet()
            pendingByToken[token] = Pending(session, title)
            return token
        }

        @Synchronized
        private fun reclaim(token: Long): Pending? = pendingByToken.remove(token)

        private fun newInstance(token: Long): ExtensionPopupBottomSheet {
            return ExtensionPopupBottomSheet().apply {
                arguments = Bundle().apply { putLong(ARG_TOKEN, token) }
            }
        }

        private fun safeShow(fm: FragmentManager, token: Long) {
            try {
                if (fm.isStateSaved || fm.isDestroyed) {
                    throw IllegalStateException("FragmentManager unavailable")
                }
                newInstance(token).show(fm, TAG)
            } catch (_: Exception) {
                reclaim(token)?.session?.close()
            }
        }

        fun showExistingSession(
            activity: FragmentActivity,
            session: GeckoSession,
            title: String?,
        ) {
            val token = stash(session, title)
            safeShow(activity.supportFragmentManager, token)
        }
    }
}
