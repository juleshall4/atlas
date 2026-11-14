import { Field, Screen, Section, useRouteParams, useSnapshot } from "ink";
import { allowBack, allowOverlay, chooseKeyboard, enableKeyboard, openScrollAssistant, state, type State } from "../../lib/atlas";

/** Each feature asks only for the steps it needs. */
export type Feature = "keyboard" | "quickOpen" | "actions";

// Actions run in the keyboard. Quick open reads keys through Accessibility, so it does not need the keyboard.
const all: { label: string; done: (s: State) => boolean; onPress: () => void; features: Feature[] }[] = [
  { label: "Enable Atlas", done: s => s.enabled, onPress: enableKeyboard, features: ["keyboard", "actions"] },
  { label: "Choose Atlas", done: s => s.selected, onPress: chooseKeyboard, features: ["keyboard", "actions"] },
  { label: "Allow overlay", done: s => s.overlay, onPress: allowOverlay, features: ["keyboard"] },
  { label: "Turn on Scroll assistant", done: s => s.scrollAssistant, onPress: openScrollAssistant, features: ["keyboard"] },
  { label: "Turn on Atlas in Accessibility", done: s => s.backService, onPress: allowBack, features: ["keyboard", "quickOpen"] },
];

const steps = (feature: Feature) => all.filter(step => step.features.includes(feature));

const status = (done: boolean) => done ? "Done" : "Not done";

export const setupDone = (settings: State, feature: Feature = "keyboard") =>
  steps(feature).every(step => step.done(settings));

/** How many steps are still to do, such as "2 left". */
export const setupLeft = (settings: State, feature: Feature = "keyboard") =>
  `${steps(feature).filter(step => !step.done(settings)).length} left`;

export default function Setup() {
  const result = useSnapshot(state);
  const param = useRouteParams().feature;
  const feature: Feature = param === "quickOpen" || param === "actions" ? param : "keyboard";
  if (result.status !== "ready") return <Screen title="Setup" />;
  const settings = result.data;
  return <Screen title="Setup">
    <Section title="Steps">
      {steps(feature).map(step =>
        <Field key={step.label} label={step.label} onPress={step.onPress}>{status(step.done(settings))}</Field>)}
    </Section>
  </Screen>;
}
