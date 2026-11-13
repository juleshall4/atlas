import { Field, Screen, Section, Toggle, useAction, useSnapshot } from "ink";
import { state, update } from "../../../lib/atlas";

export default function Shortcuts() {
  const result = useSnapshot(state);
  const save = useAction(update);
  if (result.status !== "ready") return <Screen title="Shortcuts" />;
  const settings = result.data;
  return <Screen title="Shortcuts">
    <Section title="Quick open">
      <Toggle label="Quick open" value={settings.openApps} onChange={openApps => save.run({ openApps })} />
      {settings.openApps && <>
        <Field label="Apps" href="/settings/shortcuts/apps">Fn + letter</Field>
        <Toggle label="Animation" value={settings.shortcutAnimation}
          onChange={shortcutAnimation => save.run({ shortcutAnimation })} />
      </>}
    </Section>
    <Section title="Actions">
      <Field label="Keys" href="/settings/shortcuts/actions">Alt + key</Field>
    </Section>
  </Screen>;
}
