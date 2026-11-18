import { Row, Screen, Section, SettingsChoices, navigate, useAction, useSnapshot } from "ink";
import { arrowOutward } from "ink/icons";
import { addLanguage, languagesPage, state, update, type Settings, type State } from "../../../lib/atlas";

/** English comes as two choices, one for each spelling. */
const english = [
  { value: "en-GB", label: "English (British)", settings: { language: "en", spelling: "british" } },
  { value: "en-US", label: "English (American)", settings: { language: "en", spelling: "american" } },
] as const;

const chosen = ({ language, spelling }: State) =>
  language !== "en" ? language : spelling === "american" ? "en-US" : "en-GB";

export const languageLabel = (settings: State) =>
  english.find(({ value }) => value === chosen(settings))?.label
    ?? settings.languages.find(({ code }) => code === settings.language)?.name ?? english[0].label;

/** Opens the file picker. Cancelling it stays on this page, and a new language is chosen once it is added. */
export async function addDownloaded(from: "language" | "failure") {
  try {
    await addLanguage();
  } catch {
    if (from === "language") navigate("/settings/language/add-failed");
  }
}

export default function LanguagePage() {
  const result = useSnapshot(state);
  const save = useAction(update);
  if (result.status !== "ready") return <Screen title="Language" />;
  const settings = result.data;
  const downloaded = settings.languages.filter(({ code, installed }) => installed && code !== "en")
    .map(({ code, name }) => ({ value: code, label: name }));
  const choose = (value: string): Partial<Settings> =>
    english.find(option => option.value === value)?.settings ?? { language: value };
  return <Screen title="Language">
    <SettingsChoices value={chosen(settings)} options={[...english, ...downloaded]} onChange={value => save.run(choose(value))} />
    <Section title="More languages">
      <Row title="Download languages" endIcon={arrowOutward} url={languagesPage} />
      <Row title="Add a downloaded language" onPress={() => void addDownloaded("language")} />
    </Section>
  </Screen>;
}
