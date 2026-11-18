import starlight from '@astrojs/starlight';
import { defineConfig } from 'astro/config';

export default defineConfig({
	site: 'https://apps.julian-hall.com',
	integrations: [
		starlight({
			title: 'Julian Hall',
			description: 'Guides for apps for the Unihertz Titan 2 Elite.',
			favicon: '/favicon.svg',
			markdown: { headingLinks: false },
			customCss: ['./src/styles/atlas.css'],
			components: {
				Head: './src/components/Head.astro',
				Header: './src/components/Header.astro',
				MobileMenuFooter: './src/components/MobileMenuFooter.astro',
				Sidebar: './src/components/Sidebar.astro',
				PageTitle: './src/components/PageTitle.astro',
				ThemeSelect: './src/components/Empty.astro',
			},
			// One group for each app. The sidebar shows only the group for the app you're in.
			sidebar: [
				{
					label: 'Atlas',
					items: [
						{ label: 'Get started', link: '/atlas/' },
						{ label: "What's new", slug: 'atlas/whats-new' },
						{
							label: 'Keyboard',
							items: [
								'atlas/keyboard/swiping',
								'atlas/keyboard/keys',
								'atlas/keyboard/your-words',
								'atlas/keyboard/languages',
								'atlas/keyboard/shortcuts',
							],
						},
						{ label: 'Display', items: ['atlas/display/appearance'] },
						{ label: 'Audio', items: ['atlas/audio/speaker', 'atlas/audio/calls'] },
						{ label: 'Downloads', items: ['atlas/downloads/languages', 'atlas/downloads/speech'] },
						{ label: 'Help', items: ['atlas/help/problems', 'atlas/help/feedback'] },
					],
				},
				{
					label: 'Maia',
					items: [{ label: 'Get started', link: '/maia/' }],
				},
			],
		}),
	],
});
