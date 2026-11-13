import { Field, Screen, Section, Toggle, useAction, useSnapshot } from "ink";
import { state, update, type State } from "../../lib/atlas";
import { liftLabel } from "./lift";
import { skinToneLabel } from "./skin-tone";
import { spellingLabel } from "./spelling";
import { tapKeysLabel } from "./tap-keys";
import { vibrationLabel } from "./vibration";

const wordsLabel = ({ words }: State) => words.length === 1 ? "1 word" : `${words.length} words`;

export default function Keyboard() {
  const result = useSnapshot(state);
  const save = useAction(update);
  if (result.status !== "ready") return <Screen title="Keyboard" />;
  const settings = result.data;
  return <Screen title="Keyboard">
    <Section title="Typing">
      <Toggle label="Suggestion bar" value={settings.suggestionBar}
        onChange={suggestionBar => save.run({ suggestionBar })} />
      <Toggle label="Auto-capitalisation" value={settings.autoCapitalise}
        onChange={autoCapitalise => save.run({ autoCapitalise })} />
      <Toggle label="Auto-punctuation" value={settings.autoPunctuation}
        onChange={autoPunctuation => save.run({ autoPunctuation })} />
      <Field label="Spelling" href="/settings/spelling">{spellingLabel(settings)}</Field>
      <Field label="Skin tone" href="/settings/skin-tone">{skinToneLabel(settings)}</Field>
      <Field label="Your words" href="/settings/data">{wordsLabel(settings)}</Field>
    </Section>
    <Section title="Swipe typing">
      <Toggle label="Swipe typing" value={settings.swipeTyping}
        onChange={swipeTyping => save.run({ swipeTyping })} />
      {settings.swipeTyping && <>
        <Field label="Tap keys" href="/settings/tap-keys">{tapKeysLabel(settings)}</Field>
        <Field label="Continue after a lift" href="/settings/lift">{liftLabel(settings)}</Field>
        <Field label="Vibration" href="/settings/vibration">{vibrationLabel(settings)}</Field>
      </>}
    </Section>
    <Section title="Touchpad">
      <Toggle label="Cursor control" value={settings.cursorControl}
        onChange={cursorControl => save.run({ cursorControl })} />
      <Toggle label="Backlight on touch" value={settings.touchLight}
        onChange={touchLight => save.run({ touchLight })} />
    </Section>
  </Screen>;
}
