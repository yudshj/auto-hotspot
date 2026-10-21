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

This app was built in a conversation between a human and an AI coding agent (Claude, in an agent harness with adb access to a real Android device). The human set goals, made product decisions, ran on-device steps and reported bugs; the AI wrote all code and docs.

| Stage | Level | AI share | Decision log |
|---|---|--:|---|
| breakdown | copilot | 70% | The author stated the goal and each follow-up request / bug (auto-off, auto-on, show current network, "starting…" hint, start on boot); the agent broke them into technical tasks and asked for confirmation at key points (Shizuku authorization, reboot test). |
| design | copilot | 60% | The author set the product direction: both automations, status card showing the current network, keep the 5 s debounce. The agent chose the technical approach: Shizuku calling `ITetheringConnector`; no foreground service, instead a PendingIntent network callback + JobScheduler hand-off. |
| testing | copilot | 75% | The agent ran end-to-end tests over adb, analyzed dumpsys / logcat, checked the UI via screenshots; the author did the on-device steps (unlock, connect Wi-Fi, approve reboot) and found 3 issues in real use. |
| implementation | auto | 95% | The agent wrote all code and delegated two UI polish passes to sub-agents; the author wrote no code and only requested changes based on usage. |
| review | auto | 85% | The agent self-reviewed and reviewed sub-agent output, catching and fixing e.g. Compose not recomposing and a duplicate title bar; the author did functional acceptance only, no line-by-line code review. |
| documentation | auto | 100% | The agent wrote the README (English & Chinese) and this file. |

`breakdown` (task breakdown) is not a process defined by the specification, so it appears only in this table, not in the frontmatter. "AI share" is a rough self-estimate.
