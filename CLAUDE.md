# User Preferences

- Never commit anything (e.g. `git commit`) unless explicitly asked to in the current request.
- Basic-level testing only, scoped to business logic (e.g. `:domain` use cases, clustering/matching logic, `:tools` parameter validation, `:fsops` operation validation). Do not write tests for UI/Compose screens, ViewModels, DI wiring, or Android framework glue unless explicitly asked to. Keep test infrastructure minimal — no elaborate test frameworks/harnesses beyond what's needed to unit-test plain Kotlin logic.
