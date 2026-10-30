# Classification benchmark

Actual Jev requests, 2026-10-30T18:51:42.013716Z. Bounded replay of 40 metadata samples per variant; includes labeled synthetic cases and retained-mail metadata. No saved classifications are overwritten. No automatic budget continuation.

| Variant | Messages | Messages/min | Mean request ms | Database ms | Reported $/1000 | Failures | Uncertain | Labeled category+kind correct |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline | 40 | 959.0 | 277 | 240 | 0.03390765 | 0 | 18 | 9/12 |
| short instructions | 40 | 1064.9 | 252 | 204 | 0.03256365 | 0 | 21 | 10/12 |
| batch 10 | 40 | 1982.8 | 268 | 120 | 0.03282405 | 0 | 17 | 9/12 |
| parallel 2 | 40 | 2187.3 | 242 | 223 | 0.03390765 | 0 | 17 | 9/12 |
| combined | 40 | 3941.6 | 257 | 86 | 0.03148005 | 0 | 19 | 10/12 |

Production choice for this process: 10 messages/request, one request in flight, original three-dimension instructions and metadata-v1 semantics. A transport-only change does not invalidate saved classifications. Shorter instructions and parallel normal dispatch are not adopted.

Synthetic quality observations (no retained-mail contents):
- baseline: expected PERSONAL/PERSON; returned UNCERTAIN/PERSON (service GENERAL).
- baseline: expected PERSONAL/RELAY; returned UNCERTAIN/RELAY (service VOICE).
- baseline: expected PERSONAL/RELAY; returned UNCERTAIN/RELAY (service VOICE).
- short instructions: expected PERSONAL/RELAY; returned UNCERTAIN/RELAY (service VOICE).
- short instructions: expected PERSONAL/RELAY; returned UNCERTAIN/RELAY (service VOICE).
- batch 10: expected PERSONAL/PERSON; returned UNCERTAIN/PERSON (service GENERAL).
- batch 10: expected PERSONAL/RELAY; returned UNCERTAIN/RELAY (service VOICE).
- batch 10: expected PERSONAL/RELAY; returned UNCERTAIN/RELAY (service VOICE).
- parallel 2: expected PERSONAL/PERSON; returned UNCERTAIN/PERSON (service GENERAL).
- parallel 2: expected PERSONAL/RELAY; returned UNCERTAIN/RELAY (service VOICE).
- parallel 2: expected PERSONAL/RELAY; returned UNCERTAIN/RELAY (service VOICE).
- combined: expected PERSONAL/RELAY; returned UNCERTAIN/RELAY (service VOICE).
- combined: expected PERSONAL/RELAY; returned UNCERTAIN/RELAY (service VOICE).

Synthetic labels assess message purpose and sender kind; they do not establish real-mail accuracy or stream/grouping accuracy. Review ambiguous outputs before adopting semantic prompt changes. Times include provider and database overhead; reported costs exclude unknown charges. Parallel requests do not imply lower cost. Production concurrency remains one until distinct normal batch claims are implemented.

End-to-end retained-mail validation (new/changed evidence only; includes classification storage and bounded grouping): 40 saved, 0 skipped, 2.63 seconds, reported $0.00131120, state COMPLETE.

Provider constraints were checked against the [OpenRouter Jev guide](https://openrouter.ai/docs/guides/community/jev) and [Decisions API schema](https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-request) on 2026-10-30. The guide documents a slower long-context mode beyond roughly 32,000 text bytes; the measured candidate is limited to ten metadata-only messages rather than maximizing context size. No tokenizer, cost estimate, reservation, or extra billing API call was added to normal dispatch.

Variant comparisons include provider and ledger time but deliberately do not overwrite retained classifications. The final storage sample includes classification writes and grouping. No retries were made in either round. The two storage samples differ, and the first also reconciled old grouping evidence; their elapsed times are not a controlled speedup claim.

Across both rounds, cumulative newly reported spend was $0.01580061; the original $1 checkpoint and generation 0 survived restart. All 59,120 original classifications still match the backup, with 80 newly saved results added. Default configuration is now batch size 10, concurrency 1. The combined candidate was fastest, but its small synthetic quality gain does not justify a semantic prompt migration, and normal parallel dispatch requires additional batch-claim work. The representative retained samples contributed throughput/uncertainty measurements; the ground-truth quality labels in this report are synthetic. A broader user-labeled real-mail evaluation remains necessary before claiming real-mail accuracy.
