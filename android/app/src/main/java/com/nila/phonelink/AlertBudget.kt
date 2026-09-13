package com.nila.phonelink

/**
 * How many times one situation is allowed to wake the receiving phone.
 *
 * The guardian sends an alert at every rung of its ladder, and a long episode
 * has a lot of rungs. Relayed one for one, the phone in the parent's room
 * sounded again and again for a baby somebody was already on their way to --
 * which is how an alarm becomes something you silence on reflex, and then
 * something you silence on the night it mattered.
 *
 * So a situation gets a small number of soundings and then falls quiet. It does
 * not fall quiet about a situation that has got *worse*: an escalation is news,
 * and news gets its own budget. And it does not stay quiet forever -- a cry
 * that ends, a guardian that says it has stopped, or a long enough silence all
 * mean the next alert is about something new.
 *
 * Pure, and tested, because this is a product judgement that fails silently. A
 * phone that has quietly stopped sounding looks exactly like a quiet night.
 */
class AlertBudget(private val clock: () -> Long = System::currentTimeMillis) {

    companion object {
        /**
         * Soundings allowed per situation, by severity.
         *
         * More for the serious ones, because being wrong about them costs more:
         * three for something worth looking up from what you are doing, five
         * for something that needs somebody now. Level 1 never reaches this --
         * [LinkProtocol.tierFor] ignores it -- and is written here as zero so
         * that a future caller which does reach it gets the same answer.
         */
        fun repeatsFor(severity: Int): Int = when (severity) {
            4 -> 5
            3 -> 4
            2 -> 3
            else -> 0
        }

        /**
         * After this long without an alert, whatever it was is over.
         *
         * The backstop for the case the other two resets do not cover: a
         * guardian that keeps finding things to say all night without the state
         * ever coming back to calm. Ten minutes is long enough that it cannot
         * re-sound within one episode, and short enough that the small hours do
         * not pass in silence because of something that happened at nine.
         */
        const val QUIET_MS = 10 * 60 * 1000L
    }

    /** The worst this situation has been, which is whose budget is spent. */
    private var peak = 0
    private var sounded = 0
    private var lastAtMs = 0L

    /**
     * May this alert be heard?
     *
     * True at most [repeatsFor] times per situation. Counted rather than timed,
     * because the thing being limited is how often a person is interrupted, not
     * how often the other phone speaks.
     */
    fun allow(severity: Int): Boolean {
        val now = clock()
        if (lastAtMs != 0L && now - lastAtMs > QUIET_MS) settled()

        if (severity > peak) {
            // Worse than anything so far. That is new, and it is the kind of
            // new this whole feature exists for, so it starts again with the
            // larger budget its level earns.
            peak = severity
            sounded = 0
        }

        if (sounded >= repeatsFor(peak)) return false
        sounded++
        lastAtMs = now
        return true
    }

    /**
     * Nothing is wrong any more, so the next thing that is wrong is new.
     *
     * Called for a heartbeat carrying no severity and for a guardian that says
     * it has stopped on purpose.
     */
    fun settled() {
        peak = 0
        sounded = 0
        lastAtMs = 0L
    }

    /** For the notification text: how many soundings this situation has left. */
    val remaining: Int get() = (repeatsFor(peak) - sounded).coerceAtLeast(0)
}
