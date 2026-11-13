import { Screen, Toggle, useAction, useSnapshot } from "ink";
import { state, update } from "../../lib/atlas";

export default function Audio() {
  const result = useSnapshot(state);
  const save = useAction(update);
  if (result.status !== "ready") return <Screen title="Audio" />;
  const settings = result.data;
  return <Screen title="Audio" subtitle="Tunes the speaker. Headphones and Bluetooth do not change.">
    <Toggle label="Media" value={settings.music} onChange={music => save.run({ music })} />
    <Toggle label="Calls" value={settings.calls} onChange={calls => save.run({ calls })} />
  </Screen>;
}
