import { Row, Screen, back } from "ink";
import { addDownloaded } from "./index";

export default function AddFailed() {
  return <Screen title="Couldn't add the language" subtitle="Choose a file from the languages page.">
    <Row title="Try again" onPress={() => void addDownloaded("failure").then(back)} />
  </Screen>;
}
