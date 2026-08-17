package wtf.mazy.peel.ui.dialog

import android.text.format.DateFormat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import wtf.mazy.peel.util.HtmlDateTime
import java.time.LocalDate
import java.time.LocalTime

enum class DateTimePickerType { DATE, MONTH, WEEK, TIME, DATETIME_LOCAL }

data class DateTimePickerRequest(
    val type: DateTimePickerType,
    val value: String?,
    val min: String?,
    val max: String?,
)

class DateTimePickerSession internal constructor(internal val activity: FragmentActivity) {

    private var current: DialogFragment? = null
    internal var settled = false
        private set

    internal fun show(fragment: DialogFragment, tag: String) {
        current = fragment
        fragment.show(activity.supportFragmentManager, tag)
    }

    internal fun settle() {
        settled = true
        current = null
    }

    fun dismiss() {
        if (settled) return
        settled = true
        current?.dismissAllowingStateLoss()
        current = null
    }
}

fun showDateTimePickerDialog(
    activity: FragmentActivity,
    request: DateTimePickerRequest,
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
): DateTimePickerSession {
    val session = DateTimePickerSession(activity)
    val finish: (String) -> Unit = { if (!session.settled) { session.settle(); onResult(it) } }
    val cancel: () -> Unit = { if (!session.settled) { session.settle(); onCancel() } }

    when (request.type) {
        DateTimePickerType.TIME -> session.showTimePicker(
            initial = HtmlDateTime.time(request.value) ?: LocalTime.now(),
            onPicked = { finish(HtmlDateTime.formatTime(it)) },
            onCancel = cancel,
        )

        DateTimePickerType.DATETIME_LOCAL -> {
            val initial = HtmlDateTime.dateTime(request.value)
            session.showDatePicker(
                initial = initial?.toLocalDate() ?: LocalDate.now(),
                min = HtmlDateTime.dateTime(request.min)?.toLocalDate(),
                max = HtmlDateTime.dateTime(request.max)?.toLocalDate(),
                onPicked = { date ->
                    session.showTimePicker(
                        initial = initial?.toLocalTime() ?: LocalTime.now(),
                        onPicked = { finish(HtmlDateTime.formatDateTime(date, it)) },
                        onCancel = cancel,
                    )
                },
                onCancel = cancel,
            )
        }

        else -> {
            val parse: (String?) -> LocalDate? = when (request.type) {
                DateTimePickerType.MONTH -> HtmlDateTime::month
                DateTimePickerType.WEEK -> HtmlDateTime::week
                else -> HtmlDateTime::date
            }
            val format: (LocalDate) -> String = when (request.type) {
                DateTimePickerType.MONTH -> HtmlDateTime::formatMonth
                DateTimePickerType.WEEK -> HtmlDateTime::formatWeek
                else -> HtmlDateTime::formatDate
            }
            session.showDatePicker(
                initial = parse(request.value) ?: LocalDate.now(),
                min = parse(request.min),
                max = parse(request.max),
                onPicked = { finish(format(it)) },
                onCancel = cancel,
            )
        }
    }
    return session
}

private fun DateTimePickerSession.showDatePicker(
    initial: LocalDate,
    min: LocalDate?,
    max: LocalDate?,
    onPicked: (LocalDate) -> Unit,
    onCancel: () -> Unit,
) {
    val validators = buildList {
        min?.let { add(DateValidatorPointForward.from(HtmlDateTime.toUtcMillis(it))) }
        max?.let { add(DateValidatorPointBackward.before(HtmlDateTime.toUtcMillis(it))) }
    }
    val constraints = CalendarConstraints.Builder()
        .apply { if (validators.isNotEmpty()) setValidator(CompositeDateValidator.allOf(validators)) }
        .build()

    val selection = initial.coerceAtLeast(min ?: initial).coerceAtMost(max ?: initial)

    val picker = MaterialDatePicker.Builder.datePicker()
        .setSelection(HtmlDateTime.toUtcMillis(selection))
        .setCalendarConstraints(constraints)
        .build()

    var picked: LocalDate? = null
    picker.addOnPositiveButtonClickListener { picked = HtmlDateTime.toLocalDate(it) }
    picker.addOnDismissListener { picked?.let(onPicked) ?: onCancel() }
    show(picker, "PeelDatePicker")
}

private fun DateTimePickerSession.showTimePicker(
    initial: LocalTime,
    onPicked: (LocalTime) -> Unit,
    onCancel: () -> Unit,
) {
    val picker = MaterialTimePicker.Builder()
        .setTimeFormat(
            if (DateFormat.is24HourFormat(activity)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H
        )
        .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
        .setHour(initial.hour)
        .setMinute(initial.minute)
        .build()

    var picked: LocalTime? = null
    picker.addOnPositiveButtonClickListener { picked = LocalTime.of(picker.hour, picker.minute) }
    picker.addOnDismissListener { picked?.let(onPicked) ?: onCancel() }
    show(picker, "PeelTimePicker")
}
