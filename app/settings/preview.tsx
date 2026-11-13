import { Canvas, Screen, Stack } from "ink";
import { TryArea } from "../../lib/try";

export default function Preview() {
  return <Screen title="Preview">
    <Stack align="stretch">
      <Canvas width={1} height={12} />
      <TryArea placeholder="Swipe a word on the keyboard" />
    </Stack>
  </Screen>;
}
