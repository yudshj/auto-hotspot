---
version: "0.1.2"
level: auto
processes:
  design: copilot
  implementation: auto
  testing: copilot
  review: auto
  documentation: auto
---

This format is based on [AI-DECLARATION.md](https://ai-declaration.md/en/0.1.2).

## Notes

This app was built in a conversation between a human and an AI coding agent (Claude, via an agent harness with adb access to a real Android device). The human set goals, made product decisions, ran on-device steps and reported bugs; the AI wrote all code and docs.

| Stage | Level | What the human did | What the AI did |
|---|---|---|---|
| breakdown | copilot | Stated the goal and each follow-up feature/bug (auto-off on listed Wi-Fi, auto-on when switching to mobile data, network status, "starting/stopping" hint, boot behavior) | Broke requests into technical tasks, asked for clarification/approval at key points |
| design | copilot | Product decisions and UX direction | Technical approach: Shizuku + `ITetheringConnector`, event-driven detection (`PendingIntent` network callback + JobScheduler hand-off) instead of a foreground service, UI structure |
| implementation | auto | — | All Kotlin/Compose code, Gradle config, resources |
| testing | copilot | Physical steps on the phone (unlock, connect Wi-Fi, approve reboot), real-world usage, bug reports | adb-driven end-to-end tests, log/dumpsys analysis, screenshots |
| review | auto | Functional acceptance only (no line-by-line code review) | Code review, including review of sub-agent output |
| documentation | auto | — | README (English & Chinese), this file |

`breakdown` (task breakdown) is not a process defined by the specification, so it is only listed in this table, not in the frontmatter.
