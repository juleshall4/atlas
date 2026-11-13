# Contributing to Atlas

Thank you for your help with Atlas. This guide tells you how to report a problem, suggest an idea, and send a change.

You do not need a GitHub account to send a bug report or an idea. In Atlas, go to **Send feedback**.

## Philosophy

Atlas has these goals:

- **Local:** All data stays on the device. Do not add network calls, analytics, or tracking.
- **Simple:** Write clear code. Clear code is better than clever code.
- **Made for the Titan 2 Elite:** Each feature must operate well with the physical keyboard.

## Open an issue

1. Search the [issues](https://github.com/juleshall4/atlas/issues) first. Make sure that your issue is not a duplicate.
2. Open a new issue, and select a template:
   - **Bug:** Something does not operate correctly.
   - **Feature:** A new feature or a change to a feature.
3. Fill in the template. The template adds the label.

For a different issue, such as a question, open a blank issue. I add the label.

## Get approval

I read each issue. If I approve it, I add a comment that tells who does the work:

- **I do the work.** You do not need to do more. I link the fix to the issue.
- **You do the work.** I give the issue to you. Then you can start.

If you want to do the work, write this in the issue. Wait until I give the issue to you before you start. This makes sure that your time is not wasted on work that I cannot merge.

For a small fix, you do not need an issue or approval. Send a pull request directly. Examples of small fixes are a typo or an obvious bug that you can fix in one line.

## Set up your computer

### Prerequisites

I test the setup on macOS. Linux can also operate, but I did not test it.

- [Bun](https://bun.sh/)
- [Rust](https://rustup.rs/), to run Ink
- Java 21 (OpenJDK)
- The Android SDK
- The Kotlin compiler and Python 3, for the tests
- `adb`, to install Atlas on your device
- A Unihertz Titan 2 Elite with USB debugging on

### Steps

1. Fork the Atlas repository on GitHub.
2. Clone [Ink](https://github.com/No-Scrolling/ink) and your fork into the same folder:

   ```bash
   git clone https://github.com/No-Scrolling/ink
   git clone git@github.com:YOUR_USERNAME/atlas.git
   cd atlas
   ```

3. Install the dependencies:

   ```bash
   bun install
   ```

4. Set `JAVA_HOME`, `ANDROID_HOME`, and `KOTLIN_HOME`. For example, with Homebrew on macOS:

   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
   export PATH="$JAVA_HOME/bin:$PATH"
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   export KOTLIN_HOME=/opt/homebrew/opt/kotlin/libexec
   ```

### Native code (optional)

You need this step only if you change the C++ code in `native/`. The built native files are in `android/jniLibs/`, so other changes do not need it. This step operates only on macOS.

```bash
bash tools/setup-native.sh
```

## Make your change

1. Make a branch from `main`. Give the branch a short name, for example `fix-cursor-swipe`.
2. Make your change. Keep each pull request to one issue.
3. Follow the style of the code near your change.
4. Write all text that the user sees in simple, clear English. This includes the guide, settings, and release notes. Before I merge your change, I edit this text to [ASD-STE100 Simplified Technical English](https://www.asd-ste100.org/).
5. Run the checks:

   ```bash
   bun run check
   ```

6. Run the tests for the parts that you changed:

   | Part | Test |
   | --- | --- |
   | Words and suggestions | `bash tests/words/run.sh` |
   | Emoji | `bash tests/emoji/run.sh` |
   | App shortcuts | `bash tests/shortcuts/run.sh` |
   | Swipe recognition | `bash tests/swipe/run.sh` |
   | Swipe preview | `bash tests/preview/run.sh` |
   | Keyboard show and hide | `APP=com.atlas.debug python3 tests/ime-window/run.py <app package>` |
   | App screens | `APP=com.atlas.debug bun run check:phone` |

   The last four tests need your Titan 2 Elite connected over USB.

## Test on your device

Each pull request must have a test on a real Titan 2 Elite. An emulator does not have the physical keyboard.

1. Build and install Atlas Debug:

   ```bash
   bash tools/debug.sh --install
   ```

   Atlas Debug installs next to Atlas. It does not change your saved words.

2. Do the steps from the issue again. Make sure that the problem does not occur.
3. Make sure that swipe typing, suggestions, and cursor movement still operate correctly.

## Open a pull request

1. Push your branch to your fork.
2. Open a pull request into `main`.
3. In the description, write these items:
   - A link to the approved issue, for example `Closes #12`. A small fix does not need an issue.
   - What you changed.
   - How you tested it on the Titan 2 Elite.
   - A screen recording, if the change is visible.
   - How you used AI, if you used it. Refer to [AI use](#ai-use).
4. Write short commit messages in lowercase, for example `fix cursor jump on swipe`.

I read each pull request. I can ask for changes before I merge it.

## AI use

Pull requests made with AI help are welcome. Use the tools that help you, and tell me how you used them.

In the pull request description, write these items:

- If you used AI (yes or no).
- The tools that you used, for example Claude Code, GitHub Copilot, or ChatGPT.
- How much you used them, for example "wrote the tests", "helped find the bug", or "wrote most of the code".

Do not add an AI agent as a co-author in your commits. For example, do not add a `Co-Authored-By: Claude` line. You are the author of your commits.

## License

Atlas uses the GPL-3.0-only license. When you send a contribution, you agree that it uses the same license.
