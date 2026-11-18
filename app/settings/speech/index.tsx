import { Canvas, CanvasPath, Row, Screen, Section, SettingsChoices, Stack, Text, navigate, useAction, useSnapshot } from "ink";
import { arrowOutward } from "ink/icons";
import { addSpeechModel, speechPage, state, update, type State } from "../../../lib/atlas";

export const speechModelLabel = ({ speechModel, speechModels }: State) =>
  speechModels.find(({ id }) => id === speechModel)?.name ?? "None";

/** Starts an import after a file is picked. A new model is chosen once it is added. */
export async function addDownloaded(from: "model" | "failure") {
  try {
    return await addSpeechModel();
  } catch {
    if (from === "model") navigate("/settings/speech/add-failed");
    return false;
  }
}

const progressWidth = 590;

function ImportProgress({ percent, secondsLeft }: NonNullable<State["speechModelImport"]>) {
  const remaining = secondsLeft === null ? null : secondsLeft >= 60
    ? `About ${Math.ceil(secondsLeft / 60)} min left`
    : `About ${Math.max(5, Math.ceil(secondsLeft / 5) * 5)} s left`;
  return <Stack gap={10}>
    <Text>{`Adding model… ${percent}%`}</Text>
    <Canvas width={progressWidth} height={8}>
      <CanvasPath points={[[4, 4], [progressWidth - 4, 4]]} stroke="#8e8e93" strokeWidth={8} />
      {percent > 0 && <CanvasPath points={[[4, 4], [4 + (progressWidth - 8) * percent / 100, 4]]} strokeWidth={8} />}
    </Canvas>
    {remaining && <Text tone="muted">{remaining}</Text>}
  </Stack>;
}

export default function SpeechModelPage() {
  const result = useSnapshot(state);
  const save = useAction(update);
  if (result.status !== "ready") return <Screen title="Model" />;
  const settings = result.data;
  const installed = settings.speechModels.filter(({ installed }) => installed).map(({ id, name }) => ({ value: id, label: name }));
  return <Screen title="Model">
    {installed.length > 0 && <SettingsChoices value={settings.speechModel ?? ""} options={installed}
      onChange={speechModel => save.run({ speechModel })} />}
    <Section title="More models">
      <Row title="Download models" endIcon={arrowOutward} url={speechPage} />
      {settings.speechModelImport ? <ImportProgress {...settings.speechModelImport} /> : <>
        {settings.speechModelImportFailed && <Text>Could not add the model. Choose a file from the speech to text page.</Text>}
        <Row title="Add a downloaded model" onPress={() => void addDownloaded("model")} />
      </>}
    </Section>
  </Screen>;
}
