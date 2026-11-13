import { Screen, Section, Toggle, useAction, useSnapshot } from "ink";
import { state, update } from "../../../lib/atlas";

const subtitle = "Hold Alt and push a key to trigger action.";

// The actions that Alt and a key do in every app, by their setting names.
const editing = [
  ["undo", "Undo", "Z"], ["redo", "Redo", "Y"], ["select_all", "Select all", "A"], ["copy", "Copy", "C"],
  ["cut", "Cut", "X"], ["paste", "Paste", "V"], ["delete_word", "Delete word", "Backspace"],
];

export default function Actions() {
  const result = useSnapshot(state);
  const save = useAction(update);
  if (result.status !== "ready") return <Screen title="Actions" subtitle={subtitle} />;
  const on = result.data.actions;
  const row = ([id, label, key]: string[]) => <Toggle key={id} label={`${label} · ${key}`} value={on.includes(id)}
    onChange={value => save.run({ actions: value ? [...on, id] : on.filter(action => action !== id) })} />;

  // Like the Apps list: the actions that are on come first.
  const active = editing.filter(([id]) => on.includes(id));
  const others = editing.filter(([id]) => !on.includes(id));
  return <Screen title="Actions" subtitle={subtitle}>
    {active.length ? <>
      <Section title="On">{active.map(row)}</Section>
      {others.length > 0 && <Section title="Off">{others.map(row)}</Section>}
    </> : others.map(row)}
  </Screen>;
}
