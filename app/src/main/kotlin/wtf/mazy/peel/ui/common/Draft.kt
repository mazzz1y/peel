package wtf.mazy.peel.ui.common

/** A screen's working copy of an immutable value, edited by replacing it. */
class Draft<T : Any>(initial: T) {
    var value: T = initial
        private set

    fun update(transform: (T) -> T) {
        value = transform(value)
    }
}
