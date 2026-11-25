# Estimated cost for runners that report none

Loop records a dollar cost only when the runner reports one. The Codex and acp runners report token counts but no cost, so their attempts carry no cost and every `budgets.*_usd` budget spends nothing for them. Computing an estimated cost from a price table is out of scope.

## Why this is out of scope

Loop keeps no price table on purpose (the spend budget section of `docs/spec-decisions.md`). A shipped table goes stale with every price change and every new model, and a wrong table would make a dollar budget park work too early or too late.

On a subscription login, a ChatGPT login for Codex or a Google login for `agy`, the estimate is an API price equivalent, not money spent, so it would mislead more than it informs. Token budgets already hold for every runner, and `loop run` warns at startup when a workflow sets a dollar budget on a runner that reports no cost.

## Prior requests

- bl-211, "Estimated cost for the runners that report none, Codex and Gemini"
