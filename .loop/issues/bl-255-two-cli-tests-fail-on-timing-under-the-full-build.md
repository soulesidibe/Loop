---
id: bl-255
title: "Two cli tests fail on timing under the full build"
state: todo
priority: null
labels: [needs-triage]
blocked_by: []
---

During the bl-246 verification, `./gradlew --no-daemon build` failed on two cli tests that the bl-246 diff does not reach (it changes nothing in cli/src/main, WorkspaceManager.kt or scripts/).

1. `CheckResourceTest` "two verifications each get their own resource as LOOP_RESOURCE env, a third waits in the queue without a failed check" failed at CheckResourceTest.kt:120 with "waited 1.738358s, the check took 2071 ms". The test assumes the third check runs faster than the 1.5 s the opener thread holds `go`. Under the load of the full build, the check's own duration went over that wait.
2. `WorktreeHooksTest` "eight hooks take over a dead process's lock together" failed in round 4: an after_create hook "could not take the lock ... within 20 s, held by pid 15341". Eight hooks share one lock, and under load the queue behind it outlasted the 20 s wait of the hook script.

Both classes passed when run alone twice right after (4 and 15 tests, no failures). Possible fixes: give the resource test's opener a longer hold, or compare against the hold time instead of the check's duration; for the hooks test, a longer lock wait for the test, or fewer rounds or hooks under load.

Follow up of bl-246 (default:bl-246), filed by Loop from attempt d4c1ca4b-76fd-4d6c-972e-8149ba2800a9.
