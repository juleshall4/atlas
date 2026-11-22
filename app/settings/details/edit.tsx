import { useState } from "react";
import { Screen, TextInput, back, useAction, useRouteParams, useSnapshot } from "ink";
import { saveDetails, state, type Details } from "../../../lib/atlas";

type Detail = "firstName" | "lastName" | "phone" | "email";

const titles: Record<Detail, string> = {
  firstName: "First name", lastName: "Last name", phone: "Phone number", email: "Add email address",
};

export default function EditDetail() {
  const result = useSnapshot(state);
  if (result.status !== "ready") return <Screen title="" />;
  return <Editor details={result.data.details} />;
}

function Editor({ details }: { details: Details }) {
  const param = useRouteParams().detail;
  const detail: Detail = param === "lastName" || param === "phone" || param === "email" ? param : "firstName";
  const [value, setValue] = useState(detail === "email" ? "" : details[detail]);
  const save = useAction(async (entered: string) => {
    const text = entered.trim();
    if (detail !== "email") await saveDetails({ ...details, [detail]: text });
    else if (text) {
      // 254 characters is the longest an email address can be.
      if (text.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(text)) throw new Error("Enter an email address in the correct format, like name@example.com");
      if (details.emails.some(email => email.toLowerCase() === text.toLowerCase())) throw new Error("You have already added this email address");
      await saveDetails({ ...details, emails: [...details.emails, text] });
    }
    back();
  });
  // Pinned so the input, drawn by Android above the app, never scrolls over the title.
  const header = <TextInput value={value} onChange={setValue} onSubmit={entered => save.run(entered)}
    placeholder={detail === "email" ? "Email address" : "Type and press Enter"}
    action="done" autoFocus autoCapitalise={detail === "firstName" || detail === "lastName"}
    style={{ border: "none", paddingX: 0, paddingY: 0, minHeight: 50, fontSize: 30, placeholderColour: "muted" }}
    error={save.status === "error" ? save.error.message : undefined} />;
  return <Screen title={titles[detail]} header={header} />;
}
