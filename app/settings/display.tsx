import { Field, Screen, Section, useSnapshot } from "ink";
import { state } from "../../lib/atlas";
import { appearanceLabel } from "./appearance";

export default function Display() {
  const result = useSnapshot(state);
  if (result.status !== "ready") return <Screen title="Display" />;
  return <Screen title="Display">
    <Section title="Colours">
      <Field label="Appearance" href="/settings/appearance">{appearanceLabel(result.data)}</Field>
    </Section>
  </Screen>;
}
