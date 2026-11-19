package loop.api

/** Id of a run, one working period of the Loop service. */
@JvmInline
public value class RunId(
    public val value: String,
)

/** Id of a project, derived from its `WORKFLOW.md`. */
@JvmInline
public value class ProjectId(
    public val value: String,
)

/** Id of an attempt, one execution of an agent on one issue. */
@JvmInline
public value class AttemptId(
    public val value: String,
)

/** Id of an agent session, composed as `<thread_id>-<turn_id>` per the spec. */
@JvmInline
public value class SessionId(
    public val value: String,
)

/** Id of one turn inside an agent session. */
@JvmInline
public value class TurnId(
    public val value: String,
)
