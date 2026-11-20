# Test fixtures

| File | What | Licence |
|---|---|---|
| `acm_imwut.pdf` | Liu et al., "E-Tactile Flow: Exploring A Novel Path of Pain Relief through Interactive Electrotactile Stimulation", Proc. ACM IMWUT 10(3), 2026, https://doi.org/10.1145/3831989 | CC BY 4.0, unchanged |
| `chapters.m4b`, `chapters_mp4.m4a` | six seconds of test audio with chapter marks, made for the tests | Apache-2.0, like the code |

Not in the repository: `icra.pdf`, `icra.txt`, `icra_chapters.json`, `hace_de.txt`, `hace_de_chapters.json`. They are
an unpublished paper and an unpublished proposal. The tests that read them are skipped when the files are missing
(`Fixture.file` in Kotlin, `.enabled(if: Fixture.hasPrivate)` in Swift), so a fresh clone runs fewer tests than the
authors' machines do.
