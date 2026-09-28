# Reproducible core planner timing (AC-014)

Fixture: `src/test/resources/deterministic-planner-performance-v2.json`.
It fixes 16 alternative structures, five required movement families, 80 explicitly
classified candidates, strength/timed/cardio work, history/focus/accent flags and
finite rests/set schemes. Candidate IDs and all input values are deterministic.
The engine uses production limits and the real monotonic clock.

Run with JDK 21:

```sh
./gradlew test --tests '*DeterministicPlannerPerformanceTest'
```

Ten warmup runs precede fifty measured runs. Each measured run must return a fully
formed READY plan. Elapsed `System.nanoTime` includes core enumeration and scoring;
SQL, Spring startup, network and model work are excluded. The nearest-rank p95 is
sample `ceil(0.95 * 50)`, using ascending samples. The target is strictly below
1,000 ms. The report retains all samples, p95, JVM/OS/architecture and CPU count at
`build/reports/deterministic-planner-performance.json`.

The fixture and harness are added during implementation. No timing result is claimed
until the independent tester runs the command and records the report/environment.
