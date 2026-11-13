import { useState } from "react";
import { Row, Screen, Section, TextInput } from "ink";
import { search } from "ink/icons";
import { guide, searchGuide, type GuideRow } from "../../lib/guide";

const row = ({ title, text }: GuideRow) => <Row key={title} title={title} subtitle={text} wrapSubtitle />;

export default function Guide() {
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState("");
  const results = searchGuide(query);
  // Leaving the input empty puts the Search row back.
  const leave = (value: string) => value.trim() || setSearching(false);
  // Pinned, as in Your words, so the input stays above the results.
  const header = searching
    ? <TextInput value={query} onChange={setQuery} onSubmit={leave} onBlur={leave}
      placeholder="Search the guide" action="search" autoFocus
      style={{ border: "none", paddingX: 0, paddingY: 0, minHeight: 50, fontSize: 26, placeholderColour: "muted" }} />
    : <Row title="Search" endIcon={search} onPress={() => setSearching(true)} />;
  return <Screen title="Guide" subtitle="How to use Atlas." header={header}>
    {results
      ? results.length ? results.map(row) : <Row title="No results" subtitle="Use different words." wrapSubtitle />
      : guide.map(section => <Section key={section.title} title={section.title}>{section.rows.map(row)}</Section>)}
  </Screen>;
}
