import { Row, Screen, Toggle, useAction, useSnapshot } from "ink";
import { contentCopy } from "ink/icons";
import { copy, state, update } from "../../lib/atlas";

export default function Audio() {
  const result = useSnapshot(state);
  const save = useAction(update);
  if (result.status !== "ready") return <Screen title="Audio" />;
  const settings = result.data;
  const command = settings.callsCommand;
  return <Screen title="Audio" subtitle="Tunes the speaker only.">
    <Toggle label="Media" value={settings.music} onChange={music => save.run({ music })} />
    <Toggle label="Calls" value={settings.calls && !command} disabled={command != null} onChange={calls => save.run({ calls })} />
    {command && <>
      <Row title="Calls need this adb command" subtitle={command} wrapSubtitle />
      <Row title="Copy command" endIcon={contentCopy} onPress={() => void copy(command)} />
    </>}
  </Screen>;
}
