import { useEffect, useState } from "react";
import { Row, Screen, useAction, useSnapshot } from "ink";
import { add, close } from "ink/icons";
import { captureKey, state, tapKeyLabel, update, type State } from "../../lib/atlas";

export const tapKeysLabel = ({ tapKeys }: State) => tapKeys ? [...tapKeys].map(tapKeyLabel).join(", ") : "Off";

export default function TapKeys() {
  const result = useSnapshot(state);
  const save = useAction(update);
  const [adding, setAdding] = useState(false);
  const keys = result.status === "ready" ? result.data.tapKeys : "";
  useEffect(() => {
    if (!adding) return;
    return captureKey(key => {
      if (key === "Backspace") return;
      setAdding(false);
      if (!keys.includes(key)) save.run({ tapKeys: keys + key });
    });
  }, [adding, keys]);
  return <Screen title="Tap keys" subtitle="Type these keys with a light tap.">
    <Row title={adding ? "Press a letter or Space" : "Add key"} endIcon={adding ? undefined : add}
      onPress={() => setAdding(!adding)} />
    {[...keys].map(key => <Row key={key} title={tapKeyLabel(key)}
      endAction={{ icon: close, onPress: () => save.run({ tapKeys: keys.replace(key, "") }) }} />)}
  </Screen>;
}
