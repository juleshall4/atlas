import { resource } from "ink";
import { callNative, onNativeMessage } from "ink/native";

export type HapticPreset = "normal" | "strong";
export type Spelling = "british" | "american";
export type SkinTone = "default" | "light" | "medium-light" | "medium" | "medium-dark" | "dark";

export type State = {
  version: string;
  /** Atlas was updated and its release notes haven't been closed yet. */
  whatsNew: boolean;
  phone: string;
  android: string;
  enabled: boolean;
  selected: boolean;
  /** Allowed to display over other apps, which keeps the touchpad with Atlas above text selection popups. */
  overlay: boolean;
  /** The Titan's Scroll assistant switch is on, which powers the keyboard touchpad. */
  scrollAssistant: boolean;
  /** Atlas's accessibility service is on, so one back swipe closes the bar and goes back in any app. */
  backService: boolean;
  learning: boolean;
  /** The letters and space a light touch types, such as `"AI "`. */
  tapKeys: string;
  autoCapitalise: boolean;
  /** Typing gets full stops from two spaces, no space before punctuation, and apostrophes such as "don't". */
  autoPunctuation: boolean;
  suggestionBar: boolean;
  /** Resting a finger on Space starts cursor mode: lean towards either end to move the cursor. */
  cursorControl: boolean;
  /** A slide on the keys outside a text field turns on the keyboard light. */
  touchLight: boolean;
  /** A swipe across the touchpad types a word. */
  swipeTyping: boolean;
  liftOff: boolean;
  liftOffMillis: number;
  haptics: boolean;
  hapticPreset: HapticPreset;
  spelling: Spelling;
  /** The skin tone of emoji with hands and people. */
  skinTone: SkinTone;
  words: { word: string; description: string }[];
  blockedWords: string[];
  /** Fn and a letter open that letter's app. */
  openApps: boolean;
  /** Apps from shortcuts open with Android's animation. Off, they show at once. */
  shortcutAnimation: boolean;
  /** The actions, such as undo, that a held Alt and a key do. With an action off, its key types the Alt symbol. */
  actions: string[];
  /** Each letter with an app, from A to Z. */
  shortcuts: Shortcut[];
  /** The speaker is tuned for media, such as music, video and games. */
  music: boolean;
  /** The speaker is tuned for calls in apps such as WhatsApp. */
  calls: boolean;
  /** The adb command that lets Atlas tune calls, or null when it has been run. */
  callsCommand: string | null;
};

export type Shortcut = { letter: string; app: string; name: string };

/** An app with a launcher icon. `icon` is a picture of it, or null when it cannot be drawn. */
export type App = { app: string; name: string; icon: string | null };

export type Settings = Pick<State, "learning" | "tapKeys" | "autoCapitalise" | "autoPunctuation" | "suggestionBar" | "cursorControl" | "touchLight" | "swipeTyping" | "liftOff" | "liftOffMillis" | "haptics" | "hapticPreset" | "spelling" | "skinTone" | "openApps" | "shortcutAnimation" | "actions" | "music" | "calls">;

/** Touchpad points from 0 to 1 across the keyboard and down its four rows. */
export type Point = [x: number, y: number];

export type TraceEvent = { event: "touch" | "lift"; points: Point[] };

const atlas = (operation: string, payload: object = {}) => callNative("atlas", operation, payload);

export const state = resource({
  key: () => ["atlas"],
  load: async (): Promise<State> => JSON.parse(await atlas("state")),
})();

// The swipe engine loads in the background; refresh once it is ready or has failed.
onNativeMessage("atlas-state", () => void state.refresh());

export async function update(changes: Partial<Settings>) {
  for (const [key, value] of Object.entries(changes)) await atlas("set", { key, value });
  await state.refresh();
}

export async function addWord(word: string) {
  try {
    await atlas("addWord", { word });
  } finally {
    await state.refresh();
  }
}

export async function removeWord(word: string) {
  try {
    await atlas("removeWord", { word });
  } finally {
    await state.refresh();
  }
}

export async function blockWord(word: string) {
  try {
    await atlas("blockWord", { word });
  } finally {
    await state.refresh();
  }
}

export async function unblockWord(word: string) {
  try {
    await atlas("unblockWord", { word });
  } finally {
    await state.refresh();
  }
}

export async function clearWords() {
  try {
    await atlas("clearWords");
  } finally {
    await state.refresh();
  }
}

export async function transferLearning(mode: "import" | "export") {
  try {
    const result = await callNative("atlas", "transferLearning", { mode }, { timeoutMs: 600_000 });
    return result === "true" ? mode : null;
  } finally {
    if (mode === "import") await state.refresh();
  }
}

/** Hides the release notes until the next update. */
export async function markNotesSeen() {
  try {
    await atlas("notesSeen");
  } finally {
    await state.refresh();
  }
}

export const copy = (text: string) => atlas("copy", { text });

export const enableKeyboard = () => atlas("enable");
export const chooseKeyboard = () => atlas("choose");
export const allowOverlay = () => atlas("overlay");
export const openScrollAssistant = () => atlas("scrollAssistant");
export const allowBack = () => atlas("backService");

/** Gives the letter to the app, which loses any letter it had. Null takes the letter's app away. */
export async function setShortcut(letter: string, app: string | null) {
  try {
    await atlas("shortcut", { letter, app });
  } finally {
    await state.refresh();
  }
}

export const apps = resource({
  key: () => ["apps"],
  load: async (): Promise<App[]> => JSON.parse(await atlas("apps")),
})();

export const tapKeyLabel = (key: string) => key === " " ? "Space" : key;

/** Waits for letter keys, Space or Backspace until the returned function is called. */
export function captureKey(pressed: (key: string) => void) {
  const stop = onNativeMessage("atlas-key", message => pressed((message as { key: string }).key));
  void atlas("captureKey");
  return () => {
    stop();
    void atlas("releaseKey");
  };
}

const traceListeners = new Set<(event: TraceEvent) => void>();
let trace: TraceEvent = { event: "lift", points: [] };
let stopMessages: (() => void) | undefined;

/** Plays native swipe feedback on the current screen until the returned function is called. */
export function previewTouchpad() {
  void atlas("preview");
  return () => {
    void atlas("unpreview");
  };
}

/** Follows the keyboard's touchpad activity until the returned function is called. */
export function watchTrace(listener: (event: TraceEvent) => void) {
  traceListeners.add(listener);
  if (!stopMessages) {
    stopMessages = onNativeMessage("atlas-trace", message => {
      const event = message as TraceEvent & { reset: boolean };
      trace = { event: event.event, points: event.reset ? event.points : trace.points.concat(event.points) };
      for (const receive of traceListeners) receive(trace);
    });
    void atlas("watch");
  }
  listener(trace);
  let stopped = false;
  return () => {
    if (stopped) return;
    stopped = true;
    traceListeners.delete(listener);
    if (traceListeners.size === 0) {
      stopMessages?.();
      stopMessages = undefined;
      trace = { event: "lift", points: [] };
      void atlas("unwatch");
    }
  };
}
