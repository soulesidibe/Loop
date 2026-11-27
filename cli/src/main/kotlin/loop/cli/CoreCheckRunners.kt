package loop.cli

import loop.core.verify.CloneRoots
import loop.core.verify.CloneScope
import loop.core.verify.CoreCheckRunner
import loop.core.workflow.WorkflowFile

/**
 * bl-254: the core check runner of `loop run` and `loop replay` under [workflow]. With `workspace.git: clone`, the git
 * of `diff_policy` and `changed_line_coverage` in a clone workspace checks the clone's `.git` layout first and is pinned
 * to the clone, the same rule as `WorkspaceGit`. In `hooks` mode it is today's runner.
 */
internal fun coreCheckRunnerOf(workflow: WorkflowFile): CoreCheckRunner = CoreCheckRunner(clones = CloneRoots.of(CloneScope.of(workflow)))
