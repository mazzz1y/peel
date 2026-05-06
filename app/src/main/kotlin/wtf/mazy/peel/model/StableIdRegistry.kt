package wtf.mazy.peel.model

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

object StableIdRegistry {
    private val map = ConcurrentHashMap<String, Long>()
    private val counter = AtomicLong(1L)

    fun idFor(key: String): Long = map.computeIfAbsent(key) { counter.getAndIncrement() }
}

object SyncExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}
