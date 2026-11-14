# Writing style

All text people read in Atlas follows this guide: the in-app guide and the guide site, setup, settings, errors and `release-notes.txt`.

Atlas uses the GOV.UK style. It is plain English that is clear and quick to read, without sounding like a machine. The rules below are the parts that apply to Atlas, taken from these sources:

- [Writing guidelines](https://guidance.publishing.service.gov.uk/writing-to-gov-uk-standards/writing-guidelines/)
- [A to Z style guide](https://guidance.publishing.service.gov.uk/writing-to-gov-uk-standards/style-guides/a-to-z-style-guide/)
- [Design System: error messages](https://design-system.service.gov.uk/components/error-message/) and [buttons](https://design-system.service.gov.uk/components/button/)

Rules marked **Atlas** are this project's own additions.

## Tone

- Write as if you are helping one person, face to face. Be brisk but not terse, and human but not chatty.
- Call the reader "you".
- Use the active voice: "Atlas saves your words", not "Your words are saved".
- Do not say "please", "sorry" or "oops". No jokes and no exclamation marks.
- Do not use adjectives that sell, such as "powerful", "seamless" or "amazing". Say what the feature does.
- Keep text gender neutral. Use "they" and "their".

## Plain English

- Use short, common words: "use" not "utilise", "help" not "assist", "about" not "approximately", "start" not "initiate".
- Use the words people search for. The search terms in `lib/guide.ts` show which words people use.
- Avoid words ending in "-ion" and "-ment" where a verb works: "when you install" not "on installation".
- Delete "in order to". Write "to".
- Avoid metaphors and idioms. They do not say what you actually mean.
- Explain a technical term the first time you use it, or do not use it.
- Use "can" for something optional. Use "need to" for something required. Avoid hedges like "may be able to".

### Words to avoid

| Avoid | Use |
|---|---|
| utilise, leverage, deploy | use |
| initiate, commence | start |
| facilitate, empower | say what actually happens, or "let" |
| impact | affect, change |
| key (meaning important) | important, or delete it |
| robust | describe what makes it reliable |
| streamline | simplify |
| going forward | from now on |
| in order to | to |
| click | select, or press or touch (see [Actions](#actions)) |
| e.g., i.e., etc. | for example, such as, that is |
| screen (meaning a page) | page |

## Sentences and paragraphs

- Put the most important information first, in each page, paragraph, sentence, heading and link.
- Keep sentences to 25 words or fewer. Split longer ones.
- Keep paragraphs to 5 sentences or fewer. For guide rows, aim for 1 to 3 sentences.
- One idea per sentence, but join two closely related steps when that reads more naturally: "Rest your finger on the space bar until you feel a click, then slide left or right."
- Use positive contractions: "you'll", "it's", "you're".
- Do not use negative contractions. Write "cannot", "do not" and "is not". People find "can't" and "don't" harder to read.
- Do not use "should've", "could've", "would've" or "they've".

## Headings and titles

- Use sentence case everywhere: "Turn off swiping", not "Turn Off Swiping". Capitalise only proper nouns and names of settings.
- Make headings describe the content. Do not use "Introduction" or "Overview".
- Start a task heading with a verb: "Move the cursor", "Pick another word".
- Do not write headings as questions.
- Do not end a heading with a full stop.
- Page titles are 65 characters or fewer and contain no dashes or slashes. Use a colon if a title needs splitting.
- Page summaries are 160 characters or fewer and end with a full stop.
- On a page, the title is the first-level heading. Section headings start at the second level.

## Lists and steps

- Use bullets for things in no order. Use numbered steps for a process.
- Bullets have a lead-in line, start in lower case, have no semicolons and have no full stop after the last bullet. Use more than one bullet.
- Each step is a full sentence and ends with a full stop.

## Links

- Link text describes where the link goes. Never use "click here", "here" or "more".
- Front-load link text: "Send feedback about Atlas", not "If you have feedback, send it here".
- Start link text with a verb when the link leads to a task.
- Say when a link leaves the site: "Download Atlas on GitHub".

## Interface text

### Buttons

- Sentence case, and describe the action: "Allow overlay", "Set up keyboard", "Send feedback".
- Add words if the action is unclear: "Turn on Atlas keyboard", not "Turn on".

### Settings

- **Atlas:** Name a setting by what it controls, not by how it works inside: "Swipe typing", "Backlight on touch".
- **Atlas:** Write a setting's name exactly as it appears in the app, with the same capital letter, when you refer to it in text: "set Swipe typing to off".
- When you refer to an interface element outside an instruction, put it in single quotes: the 'Allow' button. In an instruction, use the name only: "Select Allow".
- Use bold only for interface elements in text that tells someone what to do. Do not use bold for emphasis.

### Errors and problems

- Say what happened and how to fix it.
- Be specific. Do not write "Something went wrong" or "An error occurred".
- Use an instruction where you can: "Turn on Atlas in Android keyboard settings".
- Do not use technical codes, or blaming words such as "forbidden", "illegal" or "you forgot".
- Do not say "please" or "sorry". "Please" implies a choice, and "sorry" does not fix the problem.
- If the person cannot fix the problem, say what happens next instead of showing an error.

## Actions

**Atlas:** Use the same verb every time for the same physical action.

| Action | Verb |
|---|---|
| A physical key | press ("Press Alt") |
| A physical key held down | hold ("Hold Alt and press Z") |
| Something on the touchscreen | touch, or select for menu items and options |
| A finger moving across the keys | swipe, or slide for the cursor |
| A finger on the keys without pressing | rest ("Rest your finger on the space bar") |

Do not use "click" for something on the screen. Use "click" only for the physical click a key gives.

## Numbers, dates and units

- Use numerals for numbers, including 2 to 9: "3 words".
- Use "one" in normal text, unless it is a step, a date or a list item. Use "once" and "twice" for repeated actions.
- Write out a number that starts a sentence.
- Use a comma in numbers over 999: "1,000".
- Use "to" in ranges, not a dash: "2 to 5 seconds".
- Dates have no ordinal and no comma: "14 November 2026".
- Times use the 12-hour clock: "5:30pm", "midday", "midnight".
- Put no space between a number and an abbreviated unit: "500ms", "50%".
- Spell out "first" to "ninth". Use "10th" and above.

## Punctuation and spelling

- Use UK English: "colour", "organise", "licence" (noun), "cancelled".
- Use "and", not "&".
- No full stops in abbreviations: "USB", not "U.S.B.". Explain an abbreviation the first time you use it, unless most people know it.
- Use one space after a full stop.
- Use double quotes only for words someone said or typed. Example of typed text: Push Sym, then type "pizza".
- Do not use italics.
- Do not use capitals for whole words or sentences. Short section labels styled in uppercase by the theme are fine.
- Do not write "(s)" for an optional plural. Use the plural.

## Release notes

- **Atlas:** Write one line per change, starting with what people can now do or what is fixed: "You can now pick a skin tone for emoji." "Fixed swipes that typed the wrong word after a pause."
- **Atlas:** Do not include internal changes that people cannot see.

## Check before you commit

- Does each sentence put the important part first?
- Is any sentence over 25 words?
- Are there any negative contractions, "please", "sorry", "click" or exclamation marks?
- Is every setting name written exactly as it appears in the app?
- Does the same action use the same verb everywhere?
