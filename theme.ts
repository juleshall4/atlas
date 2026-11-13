import { defineTheme, defineToggle } from "ink/theme";

export default defineTheme({
  fonts: { body: "./assets/fonts/Inter-Regular.otf", display: "./assets/fonts/Inter-Medium.otf" },
  colours: { background: "#000000", foreground: "#ffffff", muted: "#7f7f84" },
  text: { size: 45 },
  spacing: { inset: 92, gap: 69, rowGap: 52 },
  // Ink clips the first row's top edge at the scroll viewport.
  hitSlop: { top: 16, right: 18, bottom: 30, left: 18 },
  header: { layout: "large", size: 74, top: 21, bottom: 51, cutout: "below", back: { position: "end", icon: "arrow" }, iconSize: 60 },
  section: { uppercase: true, size: 31, gap: 48 },
  subtitle: { size: 30, tone: "muted" },
  // Rows use the body text size, the same as fields and toggles.
  row: { size: 45 },
  scrollBar: { colour: "#8e8e93", visibility: "scrolling", track: false, width: 8, inset: 30 },
  field: "inline",
  selection: "check",
  toggle: defineToggle({
    // Ink's toggle row is 69 tall, and a taller switch is cut off at the top of a screen.
    width: 118,
    height: 69,
    render: ({ on, disabled, colours }) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 130 76"><g opacity="${disabled ? 0.4 : 1}">
      <rect width="130" height="76" rx="38" fill="${on ? "#8e8e93" : "#3a3a3c"}"/>
      <circle cx="${on ? 92 : 38}" cy="38" r="33" fill="${on ? "#f2f2f4" : colours.foreground}"/>
    </g></svg>`,
  }),
});
