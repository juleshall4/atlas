import { Field, Screen, Section, Toggle, useAction, useSnapshot } from "ink";
import { state, update, type State } from "../../../lib/atlas";
import { setupDone, setupLeft } from "../setup";

const appsLabel = ({ shortcuts }: State) =>
  shortcuts.length === 0 ? "None" : shortcuts.length === 1 ? "1 app" : `${shortcuts.length} apps`;
const keysLabel = ({ actions }: State) => actions.length === 0 ? "None" : `${actions.length} on`;

export default function Shortcuts() {
  const result = useSnapshot(state);
  const save = useAction(update);
  if (result.status !== "ready") return <Screen title="Shortcuts" />;
  const settings = result.data;
  return <Screen title="Shortcuts">
    <Section title="Quick open">
      {!setupDone(settings, "quickOpen") && <Field label="Finish setup"
        href={{ path: "/settings/setup", params: { feature: "quickOpen" } }}>{setupLeft(settings, "quickOpen")}</Field>}
      <Toggle label="Quick open" value={settings.openApps} onChange={openApps => save.run({ openApps })} />
      {settings.openApps && <>
        <Field label="Apps" href="/settings/shortcuts/apps">{appsLabel(settings)}</Field>
        <Toggle label="Animation" value={settings.shortcutAnimation}
          onChange={shortcutAnimation => save.run({ shortcutAnimation })} />
      </>}
    </Section>
    <Section title="Actions">
      {!setupDone(settings, "actions") && <Field label="Finish setup"
        href={{ path: "/settings/setup", params: { feature: "actions" } }}>{setupLeft(settings, "actions")}</Field>}
      <Field label="Keys" href="/settings/shortcuts/actions">{keysLabel(settings)}</Field>
    </Section>
  </Screen>;
}
