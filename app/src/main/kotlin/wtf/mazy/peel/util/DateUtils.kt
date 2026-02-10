package wtf.mazy.peel.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.Objects.requireNonNull

object DateUtils {

    @JvmStatic
    fun getHourMinFormat(): SimpleDateFormat {
        return SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    @JvmStatic
    fun convertStringToCalendar(str: String?): Calendar? {
        if (str.isNullOrBlank()) return null
        return try {
            val parsedDate = getHourMinFormat().parse(str)
            parsedDate?.let { date ->
                Calendar.getInstance().also { it.time = date }
            }
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun isInInterval(low: Calendar, time: Calendar, high: Calendar): Boolean {
        val middle = Calendar.getInstance()
        middle.time = requireNonNull(getHourMinFormat().parse(getHourMinFormat().format(time.time)))

        val highCopy = high.clone() as Calendar
        if (highCopy.before(low)) {
            highCopy.add(Calendar.DATE, 1)
            if (middle.before(low)) {
                middle.add(Calendar.DATE, 1)
            }
        }
        return middle.after(low) && middle.before(highCopy)
    }
}
