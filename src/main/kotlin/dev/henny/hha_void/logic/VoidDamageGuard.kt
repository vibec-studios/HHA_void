package dev.henny.hha_void.logic

/**
 * Gemeinsamer Reentrancy-Schutz: Schaden, den Void-Systeme (Void Link,
 * Mace-Schock) selbst austeilen, darf keine weiteren Void-Systeme triggern —
 * sonst kaskadiert die Verteilung endlos.
 */
object VoidDamageGuard {

    var active = false
        private set

    inline fun guarded(block: () -> Unit) {
        val before = active
        setActive(true)
        try {
            block()
        } finally {
            setActive(before)
        }
    }

    @PublishedApi
    internal fun setActive(value: Boolean) {
        active = value
    }
}
