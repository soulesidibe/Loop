---
id: bl-09
title: "Kotlin compile daemon may still be shared across attempts"
state: todo
priority: 3
labels: [ready-for-human]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone.

## What

Found by bl-01's agent in run a2e09f2d. bl-01 gives each Attempt its own Gradle daemon pool through `org.gradle.daemon.registry.base`. The Kotlin compile daemon is found through its own run folder, not the Gradle daemon registry, so two Attempts, or an Attempt and a build in the main checkout, may still share one Kotlin daemon. The end of Attempt kill would then take it down while another build uses it.

What is known:

- Confirmed in triage: Kotlin daemons are found through run files in `~/Library/Application Support/kotlin/daemon`, shared by every build on the machine (Kotlin 2.4.21). A Kotlin daemon started by an attempt's own Gradle daemon is a descendant of the agent, so the end of Attempt kill takes it down, while another build (another attempt, the main checkout, a merge check) may be connected to it.
- Not verified: whether that other build fails, or whether it survives.
- The Kotlin Gradle plugin is expected to fall back to compiling without the daemon when it dies, which would cost time, not fail the build. Also not verified.
- `--no-daemon` turns off the Gradle daemon only. The local merge check still uses the shared Kotlin daemon.
- No build failure seen since bl-01 merged, in three merge checks and CI.

## Acceptance criteria

- [ ] Evidence by hand, in `.scratch/bl-09/evidence/`: a build A whose Gradle daemon starts the Kotlin daemon, a build B started while A's Kotlin daemon serves it, then the Kotlin daemon killed the way Loop kills descendants (`kill -9`) during B's compilation. Record whether B fails, or passes with the fallback warning.
- [ ] If B passes on the fallback: `docs/self-hosting.md` says so (shared Kotlin daemon, fallback, slower build), and the issue closes with no code change.
- [ ] If B fails: Loop's own `WORKFLOW.md` adds `-Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process` to the `GRADLE_OPTS` that bl-01 set, so an attempt compiles Kotlin inside its own Gradle daemon, which bl-01 already isolates. Evidence that the setting reaches the build (no new Kotlin daemon starts in an attempt), `OwnWorkflowTest` pins it, docs updated.
- [ ] `./gradlew --no-daemon build` passes.

## Decisions (triage)

- Verify before changing anything. The Kotlin plugin has a fallback to compiling without the daemon, and if it holds, the cost is a slower build, not a failure, which does not justify a workflow change.
- If a fix is needed, use the in-process strategy through `agent.env`, not a Kotlin daemon run folder per workspace: the in-process strategy is a documented Kotlin plugin setting, while the run folder location is not a documented option. Core stays generic.
- The experiment kills processes on the host, so it uses Kotlin daemons it started itself, never one found by name, and never runs while a Loop run or a merge check is active.
- Ready for a human, not an agent: the experiment kills daemons on the machine that runs Loop, which an attempt must not do.

## Comments

2026-11-12 filed from the bl-01 review in run a2e09f2d.

2026-11-12 triage: shared run folder confirmed, the fallback is not; verify first. Ready for a human.
2026-11-12: approved in triage.
