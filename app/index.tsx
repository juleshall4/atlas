import { Field, Screen, Section, useSnapshot } from "ink";
import { markNotesSeen, state } from "../lib/atlas";
import { hasReleaseNotes } from "../lib/release-notes";
import { WhatsNew } from "../lib/whats-new";

export default function Home() {
  const result = useSnapshot(state);
  if (result.status !== "ready") return <Screen title="Atlas" />;
  const { version, whatsNew } = result.data;
  if (whatsNew && hasReleaseNotes(version)) return <WhatsNew version={version} onClose={() => void markNotesSeen()} />;
  return <Screen title="Atlas">
    <Section title="Features">
      <Field label="Keyboard" href="/settings/keyboard" />
      <Field label="Shortcuts" href="/settings/shortcuts" />
      <Field label="Audio" href="/settings/audio" />
    </Section>
    <Section title="Help">
      <Field label="Guide" href="/settings/guide" />
      <Field label="Send feedback" href="/settings/feedback" />
      {hasReleaseNotes(version) && <Field label="What's new" href="/settings/whats-new" />}
      <Field label="About" href="/settings/about" />
    </Section>
  </Screen>;
}
