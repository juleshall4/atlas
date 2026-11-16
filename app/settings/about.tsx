import { Canvas, CanvasText, Row, Screen, Stack, Text, openURL, useColourScheme, useSnapshot } from "ink";
import { arrowOutward, chevronRight } from "ink/icons";
import { state } from "../../lib/atlas";
import { hasReleaseNotes } from "../../lib/release-notes";
import lightIcon from "../../assets/atlas-glyph.png";
import darkIcon from "../../assets/atlas-glyph-dark.png";

export default function About() {
  const result = useSnapshot(state);
  const icon = useColourScheme() === "dark" ? lightIcon : darkIcon;
  return <Screen title="About">
    <Stack justify="space-between" align="stretch">
      <Stack gap="content">
        <Row image={icon} title="Atlas" subtitle={result.status === "ready" ? `Version ${result.data.version}` : undefined} />
        <Row title="Source code" endIcon={arrowOutward} onPress={() => void openURL("https://github.com/juleshall4/atlas")} />
        {result.status === "ready" && hasReleaseNotes(result.data.version) &&
          <Row title="What's new" endIcon={chevronRight} href="/settings/whats-new" />}
        <Row title="Licences" endIcon={chevronRight} href="/settings/licences" />
      </Stack>
      <Stack align="center" gap={4}>
        <Canvas width={596} height={28}>
          <CanvasText x={0} y={0} width={596} height={28} size={20} align="center" colour="#7f7f84">Powered by</CanvasText>
        </Canvas>
        <Text align="center">FUTO Swipe</Text>
      </Stack>
    </Stack>
  </Screen>;
}
