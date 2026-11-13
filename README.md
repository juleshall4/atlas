<div align="center">

<img src="assets/icon.png" width="112" alt="Atlas app icon">

### Atlas

![Version 1.3.0](https://img.shields.io/badge/version-1.3.0-111111?style=flat-square)

**A toolkit to power up your Unihertz Titan 2 Elite!**

</div>

Atlas is an open-source toolkit for the Unihertz Titan 2 Elite. It currently provides two typing features for the physical keyboard: swipe typing and a keyboard. Each feature works independently. You can turn each setting on or off, so you use only the features that you want. More features are in development.

## Features

> **About the demos:** The on-screen keyboard is only a preview of your finger. Every feature works on the real keys, in any app.

### Swipe typing

<p align="center">
  <img src="assets/demo.gif" height="290" alt="Swiping across the Titan 2 Elite's physical keys to type">
  <img src="assets/demos/swipe.gif" height="290" alt="Swiping 'What a time to be alive' across the keys, with a light tap for 'a'">
  <img src="assets/screenshots/saved-words.png" height="290" alt="Saved words list with everyday words Atlas has learned">
</p>

The Titan 2 Elite's keys double as a touchpad, so you can swipe words without pressing them.

- **Swipe recognition:** On-device, powered by FUTO's models.
- **Alternative words:** Tap another word on the strip to replace a swipe.
- **Slip recovery:** A swipe survives your finger briefly leaving the keys.
- **Tap keys:** Lightly tap chosen keys to type words like "a" and "I" without breaking your swipe flow.
- **Swipe vibration:** Light haptic feedback as you swipe.
- **Personal dictionary:** Learns your words, which you can edit, export and import.

> **Languages:** Swipe typing supports only English. Each new language needs its own trained models and a lot of testing, which is a very large task.

### Keyboard

<p align="center">
  <img src="assets/demos/typing.gif" height="290" alt="Typing 'i dont think pineapple belongs on pizza' with corrections, suggestions and a double-space full stop">
  <img src="assets/demos/emoji.gif" height="290" alt="Pressing Sym, swiping down to hands and across two pages to add 🤙 to 'Call me later'">
  <img src="assets/demos/cursor.gif" height="290" alt="Moving the cursor with the space bar to add a word mid-sentence">
</p>

Atlas also improves normal typing on the physical keys, with or without swipe typing.

- **Word suggestions:** Suggestions as you type, and predictions for the next word.
- **Spelling corrections:** British or American English.
- **Auto-capitalisation:** Capitals at the start of each sentence.
- **Auto-punctuation:** Fixes "i" to "I", "dont" to "don't" and stray spaces before punctuation.
- **Emoji:** Press Sym, with your recent emoji first. Type a word to search, and pick a skin tone in Settings.
- **Symbols:** Press Alt for symbols that aren't on the keys.
- **Delete a word:** Hold Alt + Backspace.
- **Cursor control:** Rest on the space bar, then slide left or right.
- **Full stop:** Press space twice.

See **Settings > Guide** for details.

## Requirements

- Unihertz Titan 2 Elite
- Android 14 or later

## Install

The latest APK is in [releases](https://github.com/juleshall4/atlas/releases).

I recommend that you install Atlas with [Obtainium](https://github.com/ImranR98/Obtainium). Then you receive each update.

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases).
2. In Obtainium, touch **Add App**.
3. Paste this URL as the app source, then touch **Add**:

   ```text
   https://github.com/juleshall4/atlas
   ```

4. Touch **Install**.

## Privacy

All data stays on your device. Nobody, including me, can see your data.

Atlas does not learn words from password, email, URL or private fields.

Atlas shows over other apps only to read touches on the Titan 2 keys. It never reads the screen. You can turn off the silent notification that Android shows for this.

## Contributing

Thank you for your interest in Atlas! All help is welcome. Before you write code, please read the [contributing guide](CONTRIBUTING.md).

1. **Open an issue.** Tell me about bugs, ideas and questions in an issue.
2. **Use a template.** Select **Bug** or **Feature**. The template adds the label for you.
3. **Wait for a reply.** I read each issue. If I approve it, we agree who does the work.
4. **Test on a Titan 2 Elite.** Please test each pull request on a real device.
5. **Open a pull request.** Link it to the approved issue.

Not a developer? You can still help! To send a bug report or an idea, open **Settings > Send feedback** in Atlas.

## Credits

Atlas uses these projects:

- [Ink](https://github.com/No-Scrolling/ink): the app framework, an awesome tool built by my best friend, [vandamd](https://github.com/vandamd) (MIT)
- [FUTO Swipe](https://gitlab.futo.org/keyboard/swipe-library): swipe recognition (GPL-3.0)
- [FUTO Keyboard](https://github.com/futo-org/android-keyboard): word prediction (FUTO Source First)
- [FUTO Swipe models](https://huggingface.co/futo-org/futo-swipe): swipe typing models (FUTO Model Weights)
- [AOSP dictionaries](https://codeberg.org/Helium314/aosp-dictionaries/src/branch/main/dictionaries/main_en_gb.dict): British English dictionary (Apache 2.0)
- [Inter](https://github.com/rsms/inter): typeface (SIL Open Font Licence)

## License

This project uses the GNU General Public License v3.0 (GPL-3.0-only). For more data, see the [LICENSE](LICENSE) file.
