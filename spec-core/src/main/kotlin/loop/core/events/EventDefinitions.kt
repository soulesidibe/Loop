package loop.core.events

/** Every event kind Loop records, each defined once (bl-80). */
object EventDefinitions {
    val all: List<EventDefinition> =
        listOf(
            // The attempt and its turns.
            AttemptStarted,
            AttemptFinished,
            Phase,
            TurnStarted,
            TurnCompleted,
            TurnFailed,
            TurnInputRequired,
            AttemptStalled,
            InterruptGraceExpired,
            SessionStopFailed,
            SessionRotated,
            RunnerOutput,
            PromptRecordFailed,
            RunnerOutputRecordFailed,
            HookFinished,
            // Context occupancy and rate limits, apart from spend.
            ContextUsage,
            ContextWindowUnknown,
            RateLimit,
            // Spend.
            Usage,
            TokenCapReached,
            TokenThreshold,
            BudgetWarning,
            BudgetReached,
            // The tracker.
            IssueMoved,
            IssueCommented,
            IssueLabeled,
            IssueMoveRequested,
            IssueCommentRequested,
            ToolRequestFailed,
            ToolRequestDropped,
            HumanTouched,
            // Results and their verification.
            ResultSubmitted,
            ResultInvalid,
            ResultDiscarded,
            ResultRequested,
            EvidenceRecordFailed,
            CheckStarted,
            CheckFinished,
            Verdict,
            JudgmentCalls,
            AcceptanceCommandsFrozen,
            AcceptanceCommandsChanged,
            AcceptanceCommandsEmpty,
            AcceptanceCommandsAccepted,
            DiffBaseUnpinned,
            // Delivery.
            ApprovalRequested,
            ApprovalGiven,
            BranchPushed,
            BranchPushFailed,
            PullRequestOpened,
            PullRequestUpdated,
            PullRequestFound,
            DeliveryFailed,
            BaseFetched,
            BaseFetchFailed,
            MergeCheckStarted,
            MergeCheckFinished,
            MergeRequeued,
            MergeRoundSkipped,
            MergeConflictEarly,
            EarlyCheckFailed,
            CiWaiting,
            CiFinished,
            DeliveryQueuePaused,
            DeliveryQueueResumed,
            PullRequestMerged,
            MergeRiskAssessed,
            AutoMergeFallback,
            // Retries and parks.
            RetryScheduled,
            RetryDropped,
            IssueParked,
            IssueUnparked,
            CandidateSkipped,
            // The run.
            Preflight,
            ConfigReloaded,
            ConfigReloadFailed,
            RunStopped,
            RunnerHoldStarted,
            RunnerHoldLifted,
            ClaimReleased,
        )

    private val byName: Map<String, EventDefinition> = all.associateBy { it.name }

    /** The definition of [kind], or null for a kind Loop does not know. */
    fun of(kind: String): EventDefinition? = byName[kind]
}
