package loop.core.reactions

/** bl-224: what came of a reaction's send back of an approval wait, which decides what the reaction records. */
enum class SendBackOutcome {
    /** The wait ended and the retry is scheduled on a `max_attempts` slot: the reaction records its findings. */
    Scheduled,

    /** The wait ended and that slot was the last one, so the issue parked. */
    Parked,

    /**
     * A `loop approve` or a `loop send-back` decided the wait while the reaction read GitHub: the wait is kept for the
     * human's decision, no retry is scheduled and the reaction records `reaction_skipped` with `human_decided`.
     */
    Decided,

    /** The store could not end the wait: it goes on, nothing is recorded, and a later poll reacts again. */
    Kept,
}
