# Atlas guide site

The Atlas guide as a website, built with [Astro](https://astro.build/) and [Starlight](https://starlight.astro.build/).

## Run it

```sh
cd docs
bun install
bun run dev
```

`bun run build` builds the site into `docs/dist`.

## Edit it

- Pages are in `src/content/docs`. The sidebar order is in `astro.config.mjs`.
- Write all text to [writing-style.md](../writing-style.md).
- To show a setting as Atlas shows it, use the `Setting` component in an `.mdx` page:

  ```mdx
  import Setting from '../../../components/Setting.astro';

  <Setting path={['Keyboard']} name="Swipe typing" on />
  ```

- The Atlas theme is in `src/styles/atlas.css`.
