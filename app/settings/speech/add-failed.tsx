import { Row, Screen, back } from "ink";
import { addDownloaded } from "./index";

export default function AddFailed() {
  return <Screen title="Could not add the model" subtitle="Choose a file from the speech to text page.">
    <Row title="Try again" onPress={async () => {
      if (await addDownloaded("failure")) back();
    }} />
  </Screen>;
}
