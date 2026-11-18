import { Field, Screen } from "ink";
import { returnToData } from "../../../lib/transfer";

export default function Imported() {
  return <Screen title="Words imported">
    <Field label="Done" onPress={returnToData}>{""}</Field>
  </Screen>;
}
