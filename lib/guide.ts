export type GuideRow = {
  title: string;
  text: string;
  /** Hidden search words: what people type that the text does not say. */
  terms: string[];
};

export const guide: { title: string; rows: GuideRow[] }[] = [
  {
    title: "Swiping", rows: [
      { title: "Swipe a word", text: "Move your finger across the letters. Do not push the keys. Lift your finger to type the word.",
        terms: ["swipe typing", "slide typing", "glide typing", "gesture typing", "trace", "drag", "draw", "shape writing", "one finger", "fast typing", "type faster", "without pressing", "write words", "how to type"] },
      { title: "Turn off swiping", text: "To stop swipes from typing words, set Swipe typing to off in Settings. Tap keys also stop. Cursor control continues to operate.",
        terms: ["turn off swipe", "disable swipe", "switch off swipe", "no swipe", "accidental swipe", "unwanted words", "random words", "typing by itself", "ghost typing", "physical keys only"] },
      { title: "Continue after a lift", text: "If your finger leaves the keyboard for a short time, the swipe continues. Change this time in Settings.",
        terms: ["slip recovery", "lift", "slip", "slipped", "lost contact", "finger came off", "let go", "cut off", "stops halfway", "half word", "timeout", "delay", "break", "gap", "pause", "sensitivity"] },
      { title: "Pick another word", text: "Touch a word on the strip above the keyboard.",
        terms: ["wrong word", "bad word", "not the word", "different word", "other words", "fix word", "change word", "replace word", "choose", "alternatives", "options", "candidates", "suggestion bar", "top bar"] },
      { title: "Move the cursor", text: "Rest your finger on the space bar until you feel a click. Then move your finger to the left or right.",
        terms: ["cursor control", "arrow keys", "caret", "pointer", "navigate", "go back", "move left", "move right", "edit text", "fix typo", "jump", "position", "hold space", "long press", "slide space", "trackpad"] },
    ],
  },
  {
    title: "Keys", rows: [
      { title: "Tap keys", text: "Touch these keys lightly to type them. Select the keys in Settings. Tap keys operate only when Swipe typing is on.",
        terms: ["light touch", "soft press", "touch to type", "no press", "without clicking", "tap to type", "brush", "sensitive keys", "touch keyboard"] },
      { title: "Keyboard light", text: "Outside a text field, slide your finger on the keys to turn on the keyboard light. To stop this, set Backlight on touch to off in Settings.",
        terms: ["backlight", "back light", "lights", "lit", "glow", "illuminate", "dark", "night", "see keys", "turn off light", "light comes on", "battery"] },
      { title: "Full stop", text: "Push the space bar two times.",
        terms: ["period", "dot", "point", "punctuation", "double space", "double tap space", "two spaces", "end sentence", "finish sentence", "auto punctuation"] },
      { title: "Shift and Alt", text: "Push one time for the next letter only. Push two times quickly to lock. Push again to unlock. When you hold Alt, letters do not type symbols. Use it for shortcuts.",
        terms: ["caps lock", "capital letters", "capitals", "uppercase", "upper case", "big letters", "lowercase", "small letters", "all caps", "shouting", "numbers", "digits", "stuck", "sticky keys", "modifier"] },
      { title: "Word suggestions", text: "The strip shows words and spelling corrections as you type. Touch a word to type it.",
        terms: ["autocorrect", "auto correct", "spell check", "spellcheck", "misspelled", "correction", "predictions", "predictive text", "next word", "autocomplete", "auto complete", "typo", "mistake", "suggestion bar", "top bar"] },
      { title: "Delete a word", text: "Push and hold Alt, then push Backspace. You can also use Ctrl and Backspace.",
        terms: ["erase", "remove", "clear", "rub out", "back space", "delete whole word", "delete faster", "remove word", "wipe", "control"] },
      { title: "Actions", text: "Push and hold Alt, then push a key. Z is undo. Y is redo. A selects all text. C is copy. X is cut. V is paste. Backspace deletes a word. To see the list, open Settings and select Shortcuts and Keys. To type symbols with a held Alt, set Actions to off.",
        terms: ["shortcuts", "undo", "redo", "copy", "paste", "cut", "select all", "ctrl", "control", "ctrl z", "control z", "keyboard shortcut", "go back", "take back", "clipboard", "hold alt"] },
      { title: "More symbols", text: "When Alt is on, the strip shows symbols. Touch a symbol to type it. Swipe left or right on the keyboard to see more.",
        terms: ["punctuation", "characters", "special characters", "question mark", "exclamation", "comma", "apostrophe", "quotes", "brackets", "at sign", "hashtag", "hash", "dollar", "pound", "euro", "currency", "slash", "dash", "hyphen", "underscore", "ampersand", "percent", "accents", "maths", "math"] },
      { title: "Emoji", text: "Sym operates like Shift. The strip shows emoji, with recent emoji first. Swipe left or right on the keyboard to see more, or up or down for the next group.",
        terms: ["emojis", "emoticons", "smiley", "smileys", "faces", "emoji keyboard", "hearts", "thumbs up", "stickers", "reactions", "icons", "pictures", "sym key"] },
      { title: "Find an emoji", text: "Push Sym, then type a word, for example \"pizza\". The strip shows the emoji that match. Push Enter to type the first emoji, or touch an emoji. Push Sym to stop. If no emoji match, the space bar types your words as text.",
        terms: ["emoji search", "search emoji", "find emoji", "look up emoji", "type emoji name", "emoji name", "sym and type", "no emoji found"] },
      { title: "Skin tone", text: "To change the skin tone of emoji with hands and people, open Settings, then Keyboard, then Skin tone.",
        terms: ["skin colour", "skin color", "emoji colour", "emoji color", "hand colour", "dark skin", "light skin", "brown", "yellow emoji", "fitzpatrick"] },
      { title: "Change the keyboard", text: "Push and hold Sym. Then select a different keyboard from the list.",
        terms: ["switch keyboard", "change keyboard", "other keyboard", "another keyboard", "keyboard list", "keyboard picker", "input method", "go back", "hold sym", "long press sym"] },
      { title: "Quick open", text: "Hold Fn and push a letter to open an app. You can also push Fn and then push the letter in 1 second. To give an app a letter, open Settings and select Shortcuts and Apps. Touch the app, then push the letter. To open apps with no animation, open Shortcuts and set Animation to off.",
        terms: ["app shortcuts", "open apps", "hyperkey", "hyper", "launcher", "launch", "open app", "start app", "quick open", "switch app", "keyboard shortcut", "hotkey", "hot key", "raycast", "leader key", "fn key", "func key", "programmable"] },
    ],
  },
  {
    title: "Your words", rows: [
      { title: "New words", text: "When you use a new word 3 times, Atlas learns it. A word that you add yourself stays. To see, add or block words, open Settings and select Keyboard and Your words.",
        terms: ["dictionary", "learn", "learned", "remember words", "custom words", "add word", "block word", "ban word", "remove word", "unwanted word", "keeps suggesting", "slang", "names", "nicknames", "swear words", "personal", "word list", "saved words"] },
      { title: "Backup", text: "To keep a copy of your words, open Your words and select Export words. To add the words again, select Import words.",
        terms: ["back up", "save", "restore", "transfer", "move words", "copy words", "new phone", "reset", "reinstall", "lose words", "lost words", "file", "keep words"] },
    ],
  },
  {
    title: "Problems", rows: [
      { title: "Check the setup", text: "If a part of Atlas does not operate correctly, open Settings. If it shows Finish setup, select it and do the steps that are not done.",
        terms: ["not working", "doesnt work", "stopped working", "broken", "bug", "glitch", "help", "fix", "enable", "turn on", "switch on", "activate", "permission", "install", "set up", "default keyboard", "choose keyboard", "switch keyboard", "keyboard missing", "keyboard not showing", "troubleshoot", "problem", "issue"] },
      { title: "Sym does not show emoji", text: "In the phone Settings, open Shortcut keys. Set Sym key to Programmable key.",
        terms: ["emoji not working", "no emoji", "emoji missing", "sym key", "sym button", "symbol key", "shortcut", "programmable", "wrong key"] },
      { title: "Swiping does not operate in an app", text: "Open Scroll assistant and find the app. Make sure that it shows Sliding Mode 1.",
        terms: ["swipe not working", "swiping broken", "cant swipe", "no swipe", "swipe ignored", "only one app", "some apps", "certain apps", "scrolls instead", "scroll assistant", "sliding mode", "touch scroll"] },
    ],
  },
];

// Words a question carries that a row does not need: "how do i delete a word".
const filler = new Set([
  "a", "an", "and", "the", "to", "of", "in", "on", "for", "with", "at", "or", "so", "be", "is", "are", "am", "it", "its", "this", "that", "there",
  "how", "what", "why", "where", "when", "which", "who", "do", "does", "did", "can", "could", "i", "im", "me", "my", "you", "your",
  "get", "want", "need", "way", "please", "pls", "plz", "help",
]);

// "won't show" searches like "not showing".
const negations = new Set(["wont", "cant", "cannot", "doesnt", "dont", "didnt", "isnt", "arent", "aint", "never", "no"]);

function words(text: string): string[] {
  return text.normalize("NFKD").replace(/\p{M}/gu, "").toLowerCase().replace(/['’]/g, "").split(/[^a-z0-9]+/)
    .filter(Boolean).map(word => negations.has(word) ? "not" : word);
}

/** "deleting", "deleted" and "delete" share "delet". */
function stem(word: string): string {
  const cut = word.replace(/(ing|ed|es|s|e)$/, "");
  return cut.length >= 3 ? cut : word;
}

/** One letter wrong, missing, extra, or two neighbours swapped. */
function oneEdit(a: string, b: string): boolean {
  if (Math.abs(a.length - b.length) > 1) return false;
  let i = 0;
  while (i < a.length && a[i] === b[i]) i++;
  const [x, y] = [a.slice(i), b.slice(i)];
  return x.slice(1) === y.slice(1) || x.slice(1) === y || x === y.slice(1)
    || (x[0] === y[1] && x[1] === y[0] && x.slice(2) === y.slice(2));
}

/** How well `token` matches `word`, from 1 (the same word) to 0 (no match). */
function matchWord(token: string, word: string, typos: boolean): number {
  if (token === word) return 1;
  if (stem(token) === stem(word)) return 0.9;
  if (token.length >= 2 && word.startsWith(token)) return 0.8;
  // A typo keeps the first letter; shorter words match too much.
  if (typos && token.length >= 4 && token[0] === word[0]
    && [token.length, token.length - 1, token.length + 1].some(n => oneEdit(token, word.slice(0, n)))) return 0.5;
  return 0;
}

function bestMatch(token: string, field: string[], typos: boolean): number {
  return Math.max(0, ...field.map(word => matchWord(token, word, typos)));
}

/** Small words and single letters: the search ignores them. */
function optional(token: string): boolean {
  return token.length < 2 || filler.has(token);
}

function score(row: GuideRow, section: string, tokens: string[]): number {
  // Hidden words count less and forgive no typos: a loose match there would show a row without a visible reason.
  const fields: [string[], number, boolean][] = [
    [words(row.title), 1, true], [words(row.text), 0.8, true], [words(section), 0.6, true], [words(row.terms.join(" ")), 0.8, false],
  ];
  let total = 0;
  for (const token of tokens) {
    const best = Math.max(...fields.map(([field, weight, typos]) => weight * bestMatch(token, field, typos)));
    if (best === 0) return 0;
    total += best;
  }
  return total / tokens.length;
}

/**
 * The guide rows that match `query`, best first, or null when the query has no word to search for yet.
 * A weaker match shows only when no row matches well.
 */
export function searchGuide(query: string): GuideRow[] | null {
  const tokens = words(query).filter(token => !optional(token));
  if (!tokens.length) return null;
  const scored = guide.flatMap(section => section.rows.map(row => ({ row, score: score(row, section.title, tokens) })))
    .filter(result => result.score > 0)
    .sort((a, b) => b.score - a.score);
  const top = scored[0]?.score ?? 0;
  return scored.filter(result => result.score >= top * 0.85).map(result => result.row);
}
