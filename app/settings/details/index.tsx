import { useState } from "react";
import { Field, Row, Screen, Section, Stack, TextInput, useAction, useSnapshot } from "ink";
import { add, close } from "ink/icons";
import { saveDetails, state, type Details } from "../../../lib/atlas";

const MAX_EMAILS = 3;

const subtitle = "Suggested when a form asks for them.";

type Detail = "phone" | "email";

export default function YourDetails() {
  const result = useSnapshot(state);
  if (result.status !== "ready") return <Screen title="Your details" subtitle={subtitle} />;
  return <DetailList details={result.data.details} />;
}

function DetailList({ details }: { details: Details }) {
  const [editing, setEditing] = useState<Detail>();
  const [value, setValue] = useState("");
  const [error, setError] = useState<string>();
  const remove = useAction(saveDetails);
  const save = useAction(async (entered: string) => {
    const text = entered.trim();
    if (editing === "email") {
      if (text) {
        // 254 characters is the longest an email address can be.
        if (text.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(text)) return setError("Enter an email address in the correct format, like name@example.com");
        if (details.emails.some(email => email.toLowerCase() === text.toLowerCase())) return setError("You have already added this email address");
        await saveDetails({ ...details, emails: [...details.emails, text] });
      }
    } else if (editing && text !== details.phone) await saveDetails({ ...details, phone: text });
    setEditing(undefined);
  });
  const start = (detail: Detail) => {
    setValue(detail === "email" ? "" : details.phone);
    setError(undefined);
    setEditing(detail);
  };
  // Drawn like the row it replaces and at its height, so nothing below moves.
  const input = (placeholder: string) => <TextInput value={value} placeholder={placeholder}
    onChange={text => { setValue(text); setError(undefined); }} onSubmit={text => save.run(text)} onBlur={text => save.run(text)}
    action="done" autoFocus autoCapitalise={false} error={error}
    style={{ border: "none", paddingX: 0, paddingY: 0, minHeight: 46, fontSize: 30, placeholderColour: "muted" }} />;
  return <Screen title="Your details" subtitle={subtitle}>
    <Section title="Email addresses">
      {details.emails.map(email => <Row key={email} title={email}
        endAction={{ icon: close, onPress: () => remove.run({ ...details, emails: details.emails.filter(other => other !== email) }) }} />)}
      {details.emails.length < MAX_EMAILS && (editing === "email"
        ? input("Email address")
        : <Row title="Add email address" endIcon={add} onPress={() => start("email")} />)}
    </Section>
    <Section title="Phone">
      {editing === "phone"
        ? input("Phone number")
        // The press sits on a Stack because a pressable Field draws an arrow, and this opens no page.
        : <Stack onPress={() => start("phone")}><Field label="Phone number">{details.phone || "None"}</Field></Stack>}
    </Section>
  </Screen>;
}
