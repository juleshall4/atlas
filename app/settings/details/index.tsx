import { Field, Row, Screen, Section, useAction, useSnapshot } from "ink";
import { add, close } from "ink/icons";
import { saveDetails, state } from "../../../lib/atlas";

const MAX_EMAILS = 3;

const subtitle = "The suggestion bar shows these when a form asks for them. They stay on your phone.";

export default function YourDetails() {
  const result = useSnapshot(state);
  const save = useAction(saveDetails);
  if (result.status !== "ready") return <Screen title="Your details" subtitle={subtitle} />;
  const details = result.data.details;
  const edit = (detail: string) => ({ path: "/settings/details/edit", params: { detail } });
  return <Screen title="Your details" subtitle={subtitle}>
    <Section title="Name">
      <Field label="First name" href={edit("firstName")}>{details.firstName || "None"}</Field>
      <Field label="Last name" href={edit("lastName")}>{details.lastName || "None"}</Field>
    </Section>
    <Section title="Email addresses">
      {details.emails.map(email => <Row key={email} title={email}
        endAction={{ icon: close, onPress: () => save.run({ ...details, emails: details.emails.filter(other => other !== email) }) }} />)}
      {details.emails.length < MAX_EMAILS && <Row title="Add email address" endIcon={add} href={edit("email")} />}
    </Section>
    <Section title="Phone">
      <Field label="Phone number" href={edit("phone")}>{details.phone || "None"}</Field>
    </Section>
  </Screen>;
}
