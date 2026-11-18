import { Row, Screen } from "ink";
import { arrowOutward } from "ink/icons";
import { transfer } from "../../../lib/transfer";

export default function ImportFailed() {
  return <Screen title="Couldn't import" subtitle="Choose an Atlas backup file.">
    <Row title="Try again" endIcon={arrowOutward} onPress={() => void transfer("import", "failure")} />
  </Screen>;
}
