import { Field, Screen, Section, replace, useSnapshot } from "ink";
import { close } from "ink/icons";
import { state } from "../../lib/atlas";
import { setupDone, setupLeft } from "./setup";

export default function Settings() {
  const result = useSnapshot(state);
  const closeAction = { icon: close, onPress: () => replace("/") };
  if (result.status !== "ready") return <Screen title="Settings" rightAction={closeAction} />;
  const settings = result.data;
  const done = setupDone(settings);
  return <Screen title="Settings" rightAction={closeAction}>
    {!done && <Field label="Finish setup" href="/settings/setup">{setupLeft(settings)}</Field>}
    <Section title="General">
      <Field label="Keyboard" href="/settings/keyboard" />
      <Field label="Shortcuts" href="/settings/shortcuts" />
      <Field label="Audio" href="/settings/audio" />
    </Section>
    <Section title="Help">
      <Field label="Guide" href="/settings/guide" />
      <Field label="Send feedback" href="/settings/feedback" />
      {done && <Field label="Setup" href="/settings/setup">Done</Field>}
      <Field label="About" href="/settings/about" />
    </Section>
  </Screen>;
}
