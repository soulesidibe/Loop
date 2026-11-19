package loop.tracker.github

import loop.api.TrackerAdapter
import loop.api.TrackerAdapterContract
import java.net.URI

/**
 * Runs the adapter neutral TrackerAdapter contract (spec 17.3) against the GitHub tracker, with no
 * network: [SimulatedIssues] answers like the issues API with issues built from a recorded one.
 *
 * GitHub assigns the issue number, and the tracker names an issue `<repo name>-<number>`, so a seed
 * `c-2` is issue 2 of the repository `octo/c`. A seed priority is a `priority:<n>` label.
 * The label write tests run against the simulated label endpoints (m2-23).
 */
class GitHubTrackerContractTest : TrackerAdapterContract() {
    override val activeState: String = "open"

    override val otherState: String = "closed"

    override val writesLabels: Boolean = true

    override val wellFormedId: String = "1"

    override fun trackerWith(seeds: List<Seed>): TrackerAdapter {
        val issues =
            seeds.map { seed ->
                val number =
                    SEED_IDENTIFIER
                        .matchEntire(seed.identifier)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                        ?: error("a contract seed identifier must be c-<number> to map to a GitHub issue: ${seed.identifier}")
                FakeGitHub.issue(
                    number = number,
                    title = seed.title,
                    state = seed.state,
                    labels = seed.labels + listOfNotNull(seed.priority?.let { "priority:$it" }),
                    body = seed.description,
                    repo = REPO,
                )
            }
        val simulated = SimulatedIssues(issues, REPO)
        val github =
            FakeGitHub().apply {
                fallback = simulated
                writeFallback = simulated::write
            }
        return GitHubTracker(settings(), github)
    }

    override fun unreachableTracker(): TrackerAdapter = GitHubTracker(settings(), FakeGitHub().apply { unreachable = true })

    private fun settings() = GitHubTrackerSettings(owner = "octo", repo = "c", token = "contract-token", apiUrl = URI(FakeGitHub.API))

    private companion object {
        const val REPO = "octo/c"
        val SEED_IDENTIFIER = Regex("c-(\\d+)")
    }
}
